package expo.modules.screentime

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/**
 * The shield configurations as the document holds them (`shield.default`,
 * `shield.activities.<name>`), resolved once into [ShieldAppearance]s. Pure:
 * the owner lookup is the iOS precedence of [StateStore.shieldConfig]
 * (the owning activity's config, else the default, else the built-in
 * shield), tested on the JVM.
 */
internal class ShieldConfigs private constructor(
  private val default: ShieldAppearance?,
  private val activities: Map<String, ShieldAppearance>,
) {
  fun appearance(owner: String?): ShieldAppearance =
    owner?.let { activities[it] } ?: default ?: ShieldAppearance.DEFAULT

  companion object {
    val EMPTY = ShieldConfigs(null, emptyMap())

    /** The `shield` section of [document] (null or absent: every shield is the built-in one). */
    fun from(document: JSONObject): ShieldConfigs {
      val shield = document.optJSONObject("shield") ?: return EMPTY
      val activities = HashMap<String, ShieldAppearance>()
      shield.optJSONObject("activities")?.let { all ->
        for (name in all.keys()) all.optJSONObject(name)?.let { activities[name] = ShieldAppearance.from(it) }
      }
      return ShieldConfigs(shield.optJSONObject("default")?.let(ShieldAppearance::from), activities)
    }
  }
}

/**
 * What the shield needs in memory before it is asked for, in the engine
 * process (`:screentime`, where the shield runs): the configurations and the
 * blocked apps' labels and icons. Without it, the first shield after the
 * engine started, and the first after a long idle, pay for all of it on the
 * main thread inside `onCreate` (on a Galaxy A54: 96 ms of onCreate on an
 * idle launch after 28 h, 5 ms warm): a store read under the file lock, a
 * JSON parse, the target's APK opened for its label and icon.
 *
 * - **Configurations** ([appearance]): the document is the source of truth
 *   and the module process writes it (`setShieldConfig`) without telling the
 *   engine. The cache is keyed by the document's [DocumentSignature] (one
 *   stat, strictly ordered by [StateStore.save]): on a match the resolved
 *   configs are returned, on a mismatch the document is read once and the
 *   cache replaced. The engine [refresh]es it on every poll (10 Hz while a
 *   shield can be shown), so the shield's own check almost always matches and
 *   its `onCreate` does no file I/O; a config written in the 100 ms before a
 *   shield is still read, never missed.
 * - **Faces** ([face]): the label and the icon's constant state per package,
 *   loaded by the warmer for the apps the sources shield and kept until the
 *   package or the locale changes ([dropFace], [dropFaces]). A shield over an
 *   app the warmer did not reach (an allowlist shields "every other app")
 *   loads its face on demand and caches it for the next time. The map is
 *   bounded ([MAX_CACHED_FACES], least recently used out): an icon holds its
 *   adaptive-icon layers decoded at the device's density, and the process
 *   runs around the clock.
 */
internal object ShieldCache {
  /** A blocked app as the shield shows it: its label and icon, null where the package is gone. */
  class Face(val label: String?, private val icon: Drawable?) {
    private val state = icon?.constantState

    /** A drawable of its own for one ImageView (the shared state is never handed out twice). */
    fun icon(context: Context): Drawable? = state?.newDrawable(context.resources)?.mutate() ?: icon
  }

  private class Snapshot(val signature: DocumentSignature, val configs: ShieldConfigs)

  @Volatile
  private var snapshot: Snapshot? = null
  /** Access-ordered, so the least recently shown face leaves first; every access is under its lock. */
  private val faces = object : LinkedHashMap<String, Face>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Face>): Boolean = size > MAX_CACHED_FACES
  }

  /** The configuration of the shield [owner] draws, from the cache when the document has not changed since. */
  fun appearance(store: StateStore, owner: String?): ShieldAppearance = configs(store).appearance(owner)

  /**
   * Brings the cache in line with the document if a writer saved since;
   * returns whether it was re-read. Cheap enough for the poll (one stat).
   */
  fun refresh(store: StateStore): Boolean {
    val current = snapshot
    val signature = store.signature()
    if (current != null && current.signature == signature) return false
    load(store, signature)
    return true
  }

  /** The cached configs, read now when the document changed (or was never read). */
  fun configs(store: StateStore): ShieldConfigs {
    val current = snapshot
    val signature = store.signature()
    if (current != null && current.signature == signature) return current.configs
    return load(store, signature)
  }

  private fun load(store: StateStore, signature: DocumentSignature): ShieldConfigs {
    val configs = store.read(ShieldConfigs::from)
    snapshot = Snapshot(signature, configs)
    return configs
  }

  /** Whether [packageName]'s face is loaded. */
  fun hasFace(packageName: String): Boolean = synchronized(faces) { faces.containsKey(packageName) }

  /** The face of [packageName] if loaded (and marked as recently used), else null. */
  fun cached(packageName: String): Face? = synchronized(faces) { faces[packageName] }

  /** The face of [packageName], loaded now if the warmer has not. */
  fun face(context: Context, packageName: String): Face = cached(packageName) ?: load(context, packageName)

  /** Loads (or reloads) [packageName]'s face from PackageManager; returns how long it took, in ms. */
  fun warmFace(context: Context, packageName: String): Long {
    val started = SystemClock.uptimeMillis()
    load(context, packageName)
    return SystemClock.uptimeMillis() - started
  }

  private fun load(context: Context, packageName: String): Face {
    val face = try {
      Face(LaunchableApps.label(context, packageName), LaunchableApps.icon(context, packageName))
    } catch (e: Exception) {
      // A package mid-update, a resource that fails to decode: the shield falls back to its own texts.
      Log.w(EngineController.TAG, "face of $packageName not loaded: ${e.javaClass.simpleName}: ${e.message}")
      Face(null, null)
    }
    remember(packageName, face)
    return face
  }

  /** Keeps [face] for [packageName], evicting the least recently used one over [MAX_CACHED_FACES]. */
  fun remember(packageName: String, face: Face) {
    synchronized(faces) { faces[packageName] = face }
  }

  /** The package changed (installed, updated, removed): its face is stale. */
  fun dropFace(packageName: String) {
    synchronized(faces) { faces.remove(packageName) }
  }

  /** Every face is stale (the locale changed: labels are localized). */
  fun dropFaces() = synchronized(faces) { faces.clear() }

  /** The faces loaded, for the log. */
  val faceCount: Int get() = synchronized(faces) { faces.size }

  /**
   * How many faces are kept: about 0.8 MB of PSS each on the Android 17 emulator (measured:
   * 12 faces cost 9.6 MB, almost all native heap, the icon layers decoded at the display density),
   * so 16 bound the cache at about 13 MB in a process that runs around the clock. The warmer loads
   * at most [ShieldWarmer.MAX_FACES] (the same number); beyond that, on-demand history.
   */
  const val MAX_CACHED_FACES = 16
}
