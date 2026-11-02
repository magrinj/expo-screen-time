package expo.modules.screentime

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational

/**
 * The picture-in-picture counter-measure. Device tests measured the bypass
 *: the shield draws, and the
 * blocked app's pinned window keeps floating over it, usable. There is no
 * public API to close another app's PiP window, and no way to list it, but
 * Android keeps **one** pinned task per display: an activity entering PiP
 * dismisses the pinned task already there
 * (`RootWindowContainer.moveActivityToPinnedRootTask` removes the existing
 * pinned root task first). Qustodio ships a `PipBlockerActivity` on the
 * same idea.
 *
 * So, when the engine's follow-up finds the target paused but still
 * visible under the shield ([EngineLoop.followUp]), this translucent,
 * history-less activity in its own task enters PiP itself, which evicts the
 * blocked app's window, and finishes the moment the system confirms the
 * mode, which removes its own. Translucent and silent: at most a brief
 * small window in the corner, only when a PiP leak was detected.
 */
class PipEvictorActivity : Activity() {
  private var target: String = ""
  private var entered = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    target = intent.getStringExtra(EXTRA_TARGET) ?: ""
  }

  override fun onResume() {
    super.onResume()
    if (entered || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      finishAndRemoveTask()
      return
    }
    val ok = try {
      enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(1, 1)).build())
    } catch (e: Exception) {
      Log.w(EngineController.TAG, "pip-evict enter failed: ${e.javaClass.simpleName}: ${e.message}")
      false
    }
    Log.i(EngineController.TAG, "pip-evict target=$target requested=$ok at=${System.currentTimeMillis()}")
    if (!ok) finishAndRemoveTask()
    // Belt and braces: never linger if the mode change never comes.
    window.decorView.postDelayed({ if (!isFinishing) finishAndRemoveTask() }, 1_500)
  }

  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    if (isInPictureInPictureMode) {
      entered = true
      Log.i(EngineController.TAG, "pip-evict target=$target pinned; finishing at=${System.currentTimeMillis()}")
      finishAndRemoveTask()
    }
  }

  companion object {
    const val EXTRA_TARGET = "target"

    fun launch(context: Context, target: String) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val intent = Intent(context, PipEvictorActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        .putExtra(EXTRA_TARGET, target)
      try {
        context.startActivity(intent)
      } catch (e: Exception) {
        Log.w(EngineController.TAG, "pip-evict launch failed: ${e.javaClass.simpleName}: ${e.message}")
      }
    }
  }
}
