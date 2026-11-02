package expo.modules.screentime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 * requests already made; blocks, schedules and selections join later, in the
 * same document, each under its own key.
 */
internal class StateStore(private val directory: File) {
  constructor(context: Context) : this(File(context.noBackupFilesDir, "expo_screen_time"))

  private val file = File(directory, "state.json")
  private val lockFile = File(directory, "state.lock")

  /** Reads the current document (an empty one when none exists yet). */
  fun <T> read(block: (JSONObject) -> T): T = locked(shared = true) { block(load()) }

  /**
   * Applies [block] to the current document under the exclusive lock and
   * persists the result. Returns what [block] returned.
   */
  fun <T> update(block: (JSONObject) -> T): T = locked(shared = false) {
    val document = load()
    val result = block(document)
    save(document)
    result
  }

  // MARK: - Engine

  var engineDesired: Boolean
    get() = read { it.optJSONObject(ENGINE)?.optBoolean(DESIRED, false) ?: false }
    set(value) = update { engine(it).put(DESIRED, value) }

  /**
   * Records why the engine could not start or stay in the foreground (the
   * system refusing `startForeground`, a `startForegroundService` from the
   * background). Kept for `getEnforcementGaps`; capped.
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

  private fun <T> locked(shared: Boolean, block: () -> T): T {
    val processLock = processLock(directory)
    return processLock.withLock {
      // Re-entrant: a read nested in an update runs under the outer file
      // lock (the JVM would throw OverlappingFileLockException otherwise).
      // An update nested in a read would write under a shared lock: refused.
      if (processLock.holdCount > 1) {
        check(!(outerShared && !shared)) { "StateStore.update must not be nested in StateStore.read" }
        return@withLock block()
      }
      outerShared = shared
      directory.mkdirs()
      RandomAccessFile(lockFile, "rw").use { raf ->
        val lock = raf.channel.lock(0L, Long.MAX_VALUE, shared)
        try {
          block()
        } finally {
          lock.release()
        }
      }
    }
  }

  /** Mode of the outermost section on the thread holding the process lock. */
  @Volatile
  private var outerShared = false

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

  companion object {
    private const val ENGINE = "engine"
    private const val DESIRED = "desired"
    private const val FAILURES = "failures"
    private const val PERMISSIONS = "permissions"
    private const val REQUESTED = "requested"
    private const val ERRORS = "errors"
    private const val MAX_FAILURES = 20

    private val processLocks = HashMap<String, ReentrantLock>()

    private fun processLock(directory: File): ReentrantLock = synchronized(processLocks) {
      processLocks.getOrPut(directory.absolutePath) { ReentrantLock() }
    }
  }
}
