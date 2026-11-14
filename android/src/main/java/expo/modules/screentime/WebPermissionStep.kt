package expo.modules.screentime

import expo.modules.screentime.web.WebState
import org.json.JSONObject

/** Read the preview's status and outcome from the same persisted snapshot, without preparing a VPN. */
internal object WebPermissionStep {
  fun read(plan: PermissionSteps.Plan, document: JSONObject): PermissionSteps.Step {
    require(plan.id == PermissionSteps.StepId.vpn)
    val status = PermissionSteps.Status.valueOf(WebState.consent(document).status)
    // dialogResult is historical: a later grant, revoke or null establish supersedes it.
    val cancelled = status == PermissionSteps.Status.denied &&
      document.optJSONObject("web")?.optJSONObject("consent")?.optString("event") == WebState.Event.dialogCancelled.name
    return PermissionSteps.Step(plan.id, status, plan.required, plan.kind, detail = if (cancelled) "cancelled" else null)
  }
}
