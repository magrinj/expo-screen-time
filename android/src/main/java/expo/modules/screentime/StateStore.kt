package expo.modules.screentime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.encoding.Base64

/**
 * The Android counterpart of the iOS App Group: one JSON document the React
 * Native process and the engine process (`:screentime`) both read and write.
 *
 * Why a file and not DataStore: `MultiProcessDataStoreFactory` brings
 * androidx.datastore, kotlinx-coroutines and a serializer for a few hundred
 * bytes of state, and still coordinates through a file lock underneath. This
 * is that lock, written out: an exclusive `FileChannel` lock on a sidecar
 * file around every read-decide-write (released by the kernel if the holder
 * dies, like the iOS `BlockStateLock`), a shared lock around reads, and an
 * atomic rename so a reader never sees a half-written document. The document
 * lives under `noBackupFilesDir`: desired state is per install, never
 * restored onto another device.
 *
 * Java file locks are per process: two threads of one process locking the
 * same file throw `OverlappingFileLockException`, hence the in-process
 * [ReentrantLock] taken first (re-entrant, as the iOS lock is).
 *
 * Step 1 stores the engine's desired state and failures, and the permission
 * requests already made; step 2 adds the install secret and, through
 * [SelectionStore], the selections and the handle map; step 3 adds the
 * manual block (`blocks.manual`, read by the engine process on every
 * change) and the shield configurations (`shield.default`,
 * `shield.activities.<name>`); step 4 adds the monitoring layer
 * (`monitoring`: activities, intervals, the event log, the usage cursor);
 * step 5 adds the shield actions (`shieldActions.default`,
 * `shieldActions.activities.<name>`), the temporary unlock (`unlock`, with
 * the sources it suspended), the snoozes (`snoozes`), the shield's unblocks
 * (`lifts`) and the interaction queue (`shieldInteractions`), each read and
 * written through its pure codec ([UnlockGrant], [ShieldSnoozes],
 * [ShieldLifts], [ShieldInteractionQueue]) inside one `update`.
 */
internal class StateStore(private val directory: File) {
  constructor(context: Context) : this(File(context.noBackupFilesDir, "expo_screen_time"))

  private val file = File(directory, "state.json")
  private val lockFile = File(directory, "state.lock")

  /**
   * Reads the current document (an empty one when none exists yet). Nested
   * in an `update`, it reads the document that update is changing.
   */
  fun <T> read(block: (JSONObject) -> T): T = locked(shared = true) { slot -> block(slot.open ?: load()) }

  /**
   * Applies [block] to the current document under the exclusive lock and
   * persists the result. Returns what [block] returned. Nested in an
   * `update` (any instance of this directory, same thread), it applies to
   * the outer document, which the outer section persists: a reload would
   * make the last `save` drop the other's changes.
   */
  fun <T> update(block: (JSONObject) -> T): T = locked(shared = false) { slot ->
    slot.open?.let { return@locked block(it) }
    val document = load()
    slot.open = document
    try {
      val result = block(document)
      save(document)
      result
    } finally {
      slot.open = null
    }
  }

  // MARK: - Engine

  var engineDesired: Boolean
    get() = read { it.optJSONObject(ENGINE)?.optBoolean(DESIRED, false) ?: false }
    set(value) = update { engine(it).put(DESIRED, value) }

  /**
   * Whether the service is running, written by the service itself (true
   * once it runs, false in `onDestroy`). A release sends its "look again"
   * intent only when this is true, so a release never starts the service
   * to stop it (the boot and update receivers, and every app launch, used
   * to). A crash leaves a stale true: one harmless extra intent.
   */
  var engineRunning: Boolean
    get() = read { it.optJSONObject(ENGINE)?.optBoolean(RUNNING, false) ?: false }
    set(value) = update { engine(it).put(RUNNING, value) }

  /**
   * Records why the engine could not start or stay in the foreground (the
   * system refusing `startForeground`, a `startForegroundService` from the
   * background). Diagnostic, capped; the gaps themselves are the tracker's
   * (`gaps`, [EnforcementGapTracker]).
   */
  fun recordEngineFailure(kind: String, message: String?) = update { document ->
    val failures = engine(document).optJSONArray(FAILURES) ?: JSONArray().also { engine(document).put(FAILURES, it) }
    failures.put(JSONObject().put("at", System.currentTimeMillis()).put("kind", kind).put("message", message ?: ""))
    while (failures.length() > MAX_FAILURES) failures.remove(0)
  }

  fun engineFailures(): List<JSONObject> = read { document ->
    val failures = document.optJSONObject(ENGINE)?.optJSONArray(FAILURES) ?: return@read emptyList()
    List(failures.length()) { failures.getJSONObject(it) }
  }

  // MARK: - Blocks

  /**
   * The manual block in force, or null. Written by `blockSelection` and
   * `unblockAll` in the module process together with [engineDesired], in
   * one `update`, so the engine process never reads a block without the
   * desire to run, or the reverse.
   */
  var manualBlock: ManualBlock?
    get() = read { ManualBlock.fromJson(it.optJSONObject(BLOCKS)?.optJSONObject(MANUAL)) }
    set(value) = update { document ->
      val blocks = document.optJSONObject(BLOCKS) ?: JSONObject().also { document.put(BLOCKS, it) }
      if (value == null) blocks.remove(MANUAL) else blocks.put(MANUAL, value.toJson())
    }

  // MARK: - Monitoring

  /**
   * The monitoring layer (`monitoring`): activities, intervals in progress,
   * the event log and the usage stream position. Read by both processes;
   * mutated by the module (`start` / `stop` / `stopAll` / `clearEvents`,
   * [MonitoringController]) and by the engine (`advance` / `backfill`,
   * [EngineLoop]), each inside one `update` with the decision that
   * justifies the write, which is what makes the events exactly once.
   */
  var monitoring: MonitoringState
    get() = read(MonitoringDocument::read)
    set(value) = update { document -> MonitoringDocument.write(document, value) }

  /**
   * Whether the engine should run, derived from the state: a manual block
   * in force or any monitored activity (thresholds need usage observation,
   * schedule boundaries need the alarm). Written by every module mutation
   * in the same `update` as the state it follows from, so the engine
   * process never reads a block or a schedule without the desire to run,
   * or the reverse.
   */
  fun syncEngineDesired(document: JSONObject) {
    val desired = ManualBlock.fromJson(document.optJSONObject(BLOCKS)?.optJSONObject(MANUAL)) != null ||
      MonitoringDocument.read(document).activities.isNotEmpty()
    engine(document).put(DESIRED, desired)
  }

  // MARK: - Shield configuration

  /** `setShieldConfig`: the default shield, used by the manual block and as the fallback. */
  fun setDefaultShieldConfig(config: JSONObject): Unit = update { document ->
    shield(document).put(DEFAULT, config)
    Unit
  }

  /** `setShieldConfigForActivity`. */
  fun setShieldConfig(activityName: String, config: JSONObject): Unit = update { document ->
    val activities = shield(document).optJSONObject(ACTIVITIES) ?: JSONObject().also { shield(document).put(ACTIVITIES, it) }
    activities.put(activityName, config)
    Unit
  }

  /** `clearShieldConfig`: the default and every per-activity config. */
  fun clearShieldConfigs() = update { document ->
    document.remove(SHIELD)
    Unit
  }

  /**
   * The config the shield draws, with the iOS precedence (fix 8, review R5):
   * the config of the source that owns the shield ([owner], an activity
   * name; null for the manual block), else the default, else null (the
   * built-in shield). The owner is resolved by the engine from the block
   * composition (`BlockComposition.owner`: threshold > latest schedule >
   * manual) and handed to the shield.
   */
  fun shieldConfig(owner: String?): JSONObject? = read { document ->
    val shield = document.optJSONObject(SHIELD) ?: return@read null
    owner?.let { shield.optJSONObject(ACTIVITIES)?.optJSONObject(it) } ?: shield.optJSONObject(DEFAULT)
  }

  // MARK: - Shield actions

  /** `setShieldActions`: what the buttons of every shield do, unless the owning activity has its own. */
  fun setDefaultShieldActions(config: JSONObject): Unit = update { document ->
    shieldActions(document).put(DEFAULT, config)
    Unit
  }

  /** `setShieldActionsForActivity`. */
  fun setShieldActions(activityName: String, config: JSONObject): Unit = update { document ->
    val activities = shieldActions(document).optJSONObject(ACTIVITIES) ?: JSONObject().also { shieldActions(document).put(ACTIVITIES, it) }
    activities.put(activityName, config)
    Unit
  }

  /** `clearShieldActions`: the default and every per-activity config. */
  fun clearShieldActions() = update { document ->
    document.remove(SHIELD_ACTIONS)
    Unit
  }

  /**
   * The actions of the shield [owner] (an activity name; null for the manual
   * block) draws, with the iOS precedence: the owning activity's config,
   * else the default, else null (every button closes). Document-in, so the
   * shield Activity reads it inside the `update` that records the press.
   */
  fun shieldActions(document: JSONObject, owner: String?): JSONObject? {
    val actions = document.optJSONObject(SHIELD_ACTIONS) ?: return null
    return owner?.let { actions.optJSONObject(ACTIVITIES)?.optJSONObject(it) } ?: actions.optJSONObject(DEFAULT)
  }

  // MARK: - Install secret

  /**
   * 32 random bytes drawn once per install and kept in the document, from
   * which [TokenHandles] salts the handles and [SelectionBlob] derives its
   * key. Created under the exclusive lock, so two processes starting at once
   * agree on one secret; cached per process afterwards. It goes away with the
   * document (uninstall, "clear storage"), and so do the selections it keyed.
   */
  fun installSecret(): ByteArray {
    synchronized(secrets) { secrets[directory.absolutePath] }?.let { return it }
    val existing = read { it.optJSONObject(INSTALL)?.optString(SECRET)?.takeIf { s -> s.isNotEmpty() } }
    val encoded = existing ?: update { document ->
      val install = document.optJSONObject(INSTALL) ?: JSONObject().also { document.put(INSTALL, it) }
      install.optString(SECRET).takeIf { it.isNotEmpty() }
        ?: Base64.encode(ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)).also { install.put(SECRET, it) }
    }
    return Base64.decode(encoded).also { secret -> synchronized(secrets) { secrets[directory.absolutePath] = secret } }
  }

  // MARK: - Permissions

  /** When the app last sent the user to the screen of [stepId]; null if never. */
  fun permissionRequestedAt(stepId: String): Long? = read {
    it.optJSONObject(PERMISSIONS)?.optJSONObject(REQUESTED)?.optLong(stepId, 0L)?.takeIf { at -> at > 0 }
  }

  fun markPermissionRequested(stepId: String) = update { document ->
    val requested = permissions(document).optJSONObject(REQUESTED) ?: JSONObject().also { permissions(document).put(REQUESTED, it) }
    requested.put(stepId, System.currentTimeMillis())
  }

  /** Why the last request for [stepId] failed (a screen that could not be opened); null when it succeeded. */
  fun permissionError(stepId: String): Pair<String, String>? = read {
    val error = it.optJSONObject(PERMISSIONS)?.optJSONObject(ERRORS)?.optJSONObject(stepId) ?: return@read null
    error.optString("code") to error.optString("message")
  }

  fun setPermissionError(stepId: String, code: String?, message: String?) = update { document ->
    val errors = permissions(document).optJSONObject(ERRORS) ?: JSONObject().also { permissions(document).put(ERRORS, it) }
    if (code == null) errors.remove(stepId) else errors.put(stepId, JSONObject().put("code", code).put("message", message ?: ""))
  }

  // MARK: - Plumbing

  private fun <T> locked(shared: Boolean, block: (Slot) -> T): T {
    val slot = slot(directory)
    return slot.lock.withLock {
      // Re-entrant: a read nested in an update runs under the outer file
      // lock (the JVM would throw OverlappingFileLockException otherwise).
      // An update nested in a read would write under a shared lock: refused.
      if (slot.lock.holdCount > 1) {
        check(!(slot.outerShared && !shared)) { "StateStore.update must not be nested in StateStore.read" }
        return@withLock block(slot)
      }
      slot.outerShared = shared
      directory.mkdirs()
      RandomAccessFile(lockFile, "rw").use { raf ->
        val lock = raf.channel.lock(0L, Long.MAX_VALUE, shared)
        try {
          block(slot)
        } finally {
          lock.release()
        }
      }
    }
  }

  /**
   * The per-directory state every instance of the process shares: the
   * in-process lock, the mode of the outermost section on the thread
   * holding it, and the document an `update` in progress is changing.
   */
  private class Slot {
    val lock = ReentrantLock()

    @Volatile
    var outerShared = false

    @Volatile
    var open: JSONObject? = null
  }

  private fun load(): JSONObject {
    if (!file.exists()) return JSONObject()
    return try {
      JSONObject(file.readText())
    } catch (e: Exception) {
      // A corrupt document (interrupted write before the rename landed, a
      // future format) must not take the engine or the module down.
      JSONObject()
    }
  }

  private fun save(document: JSONObject) {
    val tmp = File(directory, "state.json.tmp")
    tmp.writeText(document.toString())
    if (!tmp.renameTo(file)) {
      file.delete()
      check(tmp.renameTo(file)) { "cannot replace ${file.path}" }
    }
  }

  private fun engine(document: JSONObject) = document.optJSONObject(ENGINE) ?: JSONObject().also { document.put(ENGINE, it) }
  private fun permissions(document: JSONObject) = document.optJSONObject(PERMISSIONS) ?: JSONObject().also { document.put(PERMISSIONS, it) }
  private fun shield(document: JSONObject) = document.optJSONObject(SHIELD) ?: JSONObject().also { document.put(SHIELD, it) }
  private fun shieldActions(document: JSONObject) = document.optJSONObject(SHIELD_ACTIONS) ?: JSONObject().also { document.put(SHIELD_ACTIONS, it) }

  companion object {
    private const val ENGINE = "engine"
    private const val DESIRED = "desired"
    private const val RUNNING = "running"
    private const val FAILURES = "failures"
    private const val INSTALL = "install"
    private const val SECRET = "secret"
    private const val SECRET_BYTES = 32
    private const val PERMISSIONS = "permissions"
    private const val REQUESTED = "requested"
    private const val ERRORS = "errors"
    private const val BLOCKS = "blocks"
    private const val MANUAL = "manual"
    private const val SHIELD = "shield"
    private const val SHIELD_ACTIONS = "shieldActions"
    private const val DEFAULT = "default"
    private const val ACTIVITIES = "activities"
    private const val MAX_FAILURES = 20

    private val slots = HashMap<String, Slot>()
    private val secrets = HashMap<String, ByteArray>()

    private fun slot(directory: File): Slot = synchronized(slots) {
      slots.getOrPut(directory.absolutePath) { Slot() }
    }
  }
}
