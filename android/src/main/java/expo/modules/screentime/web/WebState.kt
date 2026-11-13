package expo.modules.screentime.web

import org.json.JSONArray
import org.json.JSONObject

/** Internal preview record. Additive keys in the existing versioned document, never a migration. */
internal object WebState {
  enum class Event { dialogGranted, dialogCancelled, restricted, prepared, established, establishDenied, establishFailed, displaced }
  enum class BootPolicy { never, whenNoVpn, always }
  enum class Start { idle, stop, waitForConsent, waitForBoot, establish }

  data class Consent(val status: String = "undetermined", val preparedBoot: Int? = null) {
    fun transition(event: Event, boot: Int?): Consent = when (event) {
      Event.dialogGranted, Event.prepared, Event.established -> Consent("granted", boot)
      Event.dialogCancelled, Event.establishDenied, Event.displaced -> Consent("denied")
      Event.restricted -> Consent("restricted")
      Event.establishFailed -> this
    }
    fun start(desired: Boolean, running: Boolean, boot: Int?): Start = when {
      !desired -> if (running) Start.stop else Start.idle
      status != "granted" -> if (running) Start.stop else Start.waitForConsent
      boot == null || preparedBoot != boot -> Start.waitForBoot
      else -> Start.establish
    }
  }

  fun record(document: JSONObject): JSONObject = document.optJSONObject("web") ?: JSONObject().also { document.put("web", it) }
  fun domains(document: JSONObject): Set<String> {
    val values = document.optJSONObject("web")?.optJSONArray("domains") ?: return emptySet()
    return (0 until values.length()).mapNotNull { values.opt(it) as? String }.toSet()
  }
  fun desired(document: JSONObject): Boolean = domains(document).isNotEmpty()
  /** A pid can host consecutive Service instances whose asynchronous cleanup overlaps. */
  fun claimService(document: JSONObject, serviceId: String) {
    record(document).put("serviceId", serviceId).put("running", false)
  }
  fun ownsService(document: JSONObject, serviceId: String): Boolean =
    document.optJSONObject("web")?.optString("serviceId") == serviceId
  fun stopped(document: JSONObject, serviceId: String): Boolean {
    if (!ownsService(document, serviceId)) return false
    record(document).put("running", false)
    return true
  }
  fun consent(document: JSONObject): Consent {
    val value = document.optJSONObject("web")?.optJSONObject("consent") ?: return Consent()
    val status = value.optString("status", "undetermined").takeIf { it in setOf("granted", "denied", "undetermined", "restricted") } ?: "undetermined"
    return Consent(status, (value.opt("preparedBootCount") as? Number)?.toInt())
  }
  fun setDomains(document: JSONObject, values: Set<String>, now: Long, boot: Int?, bootTime: Long) {
    observe(document, now, boot, bootTime)
    val wasDesired = desired(document)
    val web = record(document)
    web.put("domains", JSONArray(values.sorted())).put("desired", values.isNotEmpty())
    if (!wasDesired && values.isNotEmpty()) web.put("desiredSince", now)
    if (values.isEmpty()) {
      closeGaps(document, now)
      web.remove("desiredSince")
    } else observe(document, now, boot, bootTime)
  }
  fun event(document: JSONObject, event: Event, now: Long, boot: Int?, bootTime: Long) {
    observe(document, now, boot, bootTime)
    val next = consent(document).transition(event, boot)
    val value = record(document).optJSONObject("consent") ?: JSONObject().also { record(document).put("consent", it) }
    value.put("status", next.status).put("preparedBootCount", next.preparedBoot ?: JSONObject.NULL)
      .put("event", event.name).put("at", now)
    when (event) {
      Event.dialogGranted, Event.dialogCancelled -> value.put("dialogResult", if (event == Event.dialogGranted) "ok" else "cancelled").put("dialogAt", now)
      Event.prepared -> value.put("prepareReturnedNullAt", now)
      Event.established, Event.establishDenied -> value.put("establishOutcome", if (event == Event.established) "descriptor" else "null").put("establishAt", now)
      Event.establishFailed -> value.put("establishOutcome", "exception").put("establishAt", now)
      Event.displaced -> value.put("revokedAt", now)
      Event.restricted -> value.put("restrictedAt", now)
    }
    if (next.preparedBoot != null) value.put("lastPreparedBootCount", next.preparedBoot)
    if (next.status == "granted") closeGaps(document, now, "consent")
    observe(document, now, boot, bootTime)
  }
  fun started(document: JSONObject, now: Long, pid: Int, boot: Int?, bootTime: Long) {
    val web = record(document)
    val previous = web.optJSONObject("run")
    val sameRun = web.optBoolean("running") && previous?.optInt("pid") == pid && !previous.isNull("bootCount") && previous.optInt("bootCount") == boot &&
      previous.optString("serviceId") == web.optString("serviceId")
    event(document, Event.established, now, boot, bootTime)
    closeGaps(document, now)
    web.put("running", true).put("retries", 0).put("run", JSONObject()
      .put("start", if (sameRun) previous!!.getLong("start") else now).put("pid", pid).put("bootCount", boot ?: JSONObject.NULL)
      .put("serviceId", web.optString("serviceId")).put("heartbeat", now))
  }
  fun observe(document: JSONObject, now: Long, boot: Int?, bootTime: Long) {
    if (!desired(document)) return
    val web = record(document)
    val since = web.optLong("desiredSince", now)
    val run = web.optJSONObject("run")
    val previousBoot = listOf((run?.opt("bootCount") as? Number)?.toInt(), consent(document).preparedBoot)
      .firstOrNull { it != null && it != boot }
    if (boot == null && consent(document).status == "granted") {
      openGap(document, since, "unknown", "bootUnavailable", "current boot count unavailable; filter start cannot be confirmed")
    }
    if (boot != null && previousBoot != null) {
      openGap(document, maxOf(since, bootTime), "unknown", "reboot", "reboot: boot $boot at $bootTime, last run or preparation in boot $previousBoot")
    }
    if (consent(document).status != "granted") {
      val at = web.optJSONObject("consent")?.optLong("at", since) ?: since
      openGap(document, maxOf(since, at), "permissionRevoked", "consent", web.optJSONObject("consent")?.optString("event") ?: "never asked")
    }
  }
  fun refused(document: JSONObject, now: Long, detail: String) {
    if (desired(document)) openGap(document, maxOf(record(document).optLong("desiredSince", now), now), "unknown", "start", detail)
  }
  fun bootPolicy(document: JSONObject): BootPolicy = runCatching {
    BootPolicy.valueOf(document.optJSONObject("web")?.optString("bootPolicy", "whenNoVpn") ?: "whenNoVpn")
  }.getOrDefault(BootPolicy.whenNoVpn)
  fun shouldPrepareAtBoot(policy: BootPolicy, consent: Consent, desired: Boolean, boot: Int?, vpnSeen: Boolean, observationComplete: Boolean): Boolean =
    desired && consent.status == "granted" && boot != null && consent.preparedBoot != boot && when (policy) {
      BootPolicy.never -> false
      BootPolicy.always -> true
      BootPolicy.whenNoVpn -> observationComplete && !vpnSeen
    }

  fun gaps(document: JSONObject, since: Long?): List<Map<String, Any?>> {
    val values = document.optJSONObject("web")?.optJSONArray("gaps") ?: return emptyList()
    return (0 until values.length()).map { values.getJSONObject(it) }.filter {
      since == null || it.isNull("to") || it.getLong("to") > since
    }.map { mapOf("from" to maxOf(it.getLong("from"), since ?: Long.MIN_VALUE),
      "to" to if (it.isNull("to")) null else it.getLong("to"), "reason" to it.getString("reason"), "scope" to "webDomains") }
  }
  private fun openGap(document: JSONObject, from: Long, reason: String, kind: String, detail: String) {
    val web = record(document)
    val gaps = web.optJSONArray("gaps") ?: JSONArray().also { web.put("gaps", it) }
    if ((0 until gaps.length()).any { gaps.getJSONObject(it).let { gap -> gap.isNull("to") && gap.optString("kind") == kind } }) return
    gaps.put(JSONObject().put("from", from).put("to", JSONObject.NULL).put("reason", reason).put("kind", kind).put("detail", detail))
    while (gaps.length() > 256) gaps.remove(0)
  }
  private fun closeGaps(document: JSONObject, now: Long, kind: String? = null) {
    val gaps = document.optJSONObject("web")?.optJSONArray("gaps") ?: return
    for (index in gaps.length() - 1 downTo 0) {
      val gap = gaps.getJSONObject(index)
      if (gap.isNull("to") && (kind == null || gap.optString("kind") == kind)) {
        if (gap.getLong("from") >= now) gaps.remove(index) else gap.put("to", now)
      }
    }
  }
}
