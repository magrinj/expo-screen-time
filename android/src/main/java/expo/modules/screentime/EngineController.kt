package expo.modules.screentime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts and stops the engine service from the desired state in [StateStore],
 * from whichever process asks: the module (app launch, a block or schedule
 * being set, later), the restart receiver (boot, update) and the service
 * itself.
 *
 * The engine runs only while there is something to enforce: the module's
 * mutations derive the desired state ([StateStore.syncEngineDesired]) and
 * record a release for `getEnforcementGaps` ([EnforcementGapRecorder]).
 *
 * A `startForegroundService` can be refused (the app is in the background on
 * Android 12+ without an exemption, a stopped app on 15+); the refusal is
 * recorded, never thrown: the run showed the system's own sticky
 * restart being refused hours later, so every entry point here is a
 * recovery path that must not crash the caller.
 */
internal object EngineController {
  const val TAG = "ExpoScreenTime"

  /**
   * Brings the service in line with the desired state.
   *
   * Never `stopService`: a stop that lands while a just-started foreground
   * service is still spawning makes the system kill its process with
   * `ForegroundServiceDidNotStartInTimeException` and show a crash dialog,
   * whatever the service then does (seen on Android 17 when `unblockAll`
   * followed `blockSelection` within a millisecond). A release is therefore
   * a start-intent too: the service answers with `startForeground`, as the
   * system requires, re-reads the store and stops itself. It is only sent
   * when the engine process is alive, so a release never spawns the process
   * to stop it; a process still spawning reads the released state from the
   * store and stops on its own. "Alive" is the service's own flag in the
   * store ([StateStore.engineRunning]), not the process: the boot and
   * update receivers run in the engine process, and a launch often finds
   * it cached, which made every boot start the service just to stop it.
   *
   * Always SYNC, never START or STOP: the intent carries no state. Intents
   * from here are delivered later than the store writes they follow, so a
   * STOP that wrote "released" into the store would clobber a block set a
   * millisecond after it (seen in the conformance suite's block / unblock /
   * block sequence). The handler reads the store as it is when the intent
   * arrives, and the last intent always sees the latest state.
   */
  fun sync(context: Context, reason: String) {
    val app = context.applicationContext
    val desired = StateStore(app).engineDesired
    if (!desired && !StateStore(app).engineRunning) return
    val intent = Intent(app, ScreenTimeEngineService::class.java)
      .setAction(ScreenTimeEngineService.ACTION_SYNC)
      .putExtra(ScreenTimeEngineService.EXTRA_REASON, reason)
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        app.startForegroundService(intent)
      } else {
        app.startService(intent)
      }
    } catch (e: Exception) {
      // IllegalStateException ("not allowed to start service Intent … app is
      // in background", or ForegroundServiceStartNotAllowedException, its
      // 31+ subclass) and SecurityException are the documented refusals.
      Log.w(TAG, "engine start refused ($reason): ${e.javaClass.simpleName}: ${e.message}")
      val store = StateStore(app)
      store.recordEngineFailure("start:${e.javaClass.simpleName}", e.message)
      // Wanted and refused: unless the run's process is still alive (a SYNC
      // refused while the service already runs changes nothing), the engine
      // is down and a gap opens now (`getEnforcementGaps`).
      if (desired) EnforcementGapRecorder(app, store).engineRefused("start:${e.javaClass.simpleName}", certain = false)
    }
  }
}
