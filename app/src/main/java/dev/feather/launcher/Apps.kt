package dev.feather.launcher

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.text.Collator
import java.text.Normalizer

class App(val info: LauncherActivityInfo) {
    val label = info.label?.toString().orEmpty()
    val search = normalize(label)
    val user: UserHandle get() = info.user
    // User id is stable across reboots, so this key is safe to persist.
    val key = "${info.componentName.flattenToShortString()}#${info.user.hashCode()}"
    val suspended = info.applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED != 0
}

/** Process-wide app list. Holds only the application context, never an Activity. */
@SuppressLint("StaticFieldLeak")
object Apps {
    lateinit var launcherApps: LauncherApps
        private set
    var list: List<App> = emptyList()
        private set
    var byKey: Map<String, App> = emptyMap()
        private set
    /** Main-thread listener; the current MainActivity sets and clears it. */
    var onChange: (() -> Unit)? = null
    val icons = IconLru<Bitmap>(LOW_CACHE) { it.width * it.height * 4 }
    var iconPx = 0

    private lateinit var ctx: Context

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        launcherApps = ctx.getSystemService(LauncherApps::class.java)
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String, u: UserHandle) = load()
            override fun onPackageAdded(p: String, u: UserHandle) = load()
            override fun onPackageChanged(p: String, u: UserHandle) = load()
            override fun onPackagesAvailable(p: Array<String>, u: UserHandle, r: Boolean) = load()
            override fun onPackagesUnavailable(p: Array<String>, u: UserHandle, r: Boolean) = load()
            override fun onPackagesSuspended(p: Array<String>, u: UserHandle) = load()
            override fun onPackagesUnsuspended(p: Array<String>, u: UserHandle) = load()
        }, Handler(Looper.getMainLooper()))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = load()
        }, filter, Context.RECEIVER_NOT_EXPORTED)
        load()
    }

    /** Full requery: package events are rare, so this beats incremental bookkeeping. */
    fun load() {
        val collator = Collator.getInstance()
        list = ctx.getSystemService(UserManager::class.java).userProfiles.flatMap { user ->
            runCatching { launcherApps.getActivityList(null, user) }.getOrNull().orEmpty().map(::App)
        }.sortedWith(compareBy(collator) { it.label })
        byKey = list.associateBy { it.key }
        icons.clear()
        onChange?.invoke()
    }

    /** Worker thread. Rasterizes once at display size into a HARDWARE bitmap: one GPU copy, no Java heap copy. */
    fun loadIcon(app: App, px: Int): Bitmap {
        icons[app.key]?.let { return it }
        val pm = ctx.packageManager
        var d = runCatching { app.info.getIcon(ctx.resources.displayMetrics.densityDpi) }.getOrNull()
            ?: pm.defaultActivityIcon
        if (app.user != Process.myUserHandle()) d = pm.getUserBadgedIcon(d, app.user)
        val soft = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(soft))
        val bitmap = soft.copy(Bitmap.Config.HARDWARE, false) ?: soft
        if (bitmap !== soft) soft.recycle()
        icons.put(app.key, bitmap)
        return bitmap
    }

    const val LOW_CACHE = 2 shl 20
    const val FULL_CACHE = 8 shl 20
}

private val MARKS = Regex("\\p{Mn}+")

/** Lowercase, accents stripped: "Café" matches "cafe". */
fun normalize(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

/** Home layout format: one "cell key" per line. Unknown keys are kept so apps on unmounted storage or a paused profile return. */
fun parseLayout(s: String?): MutableMap<Int, String> {
    val map = HashMap<Int, String>()
    for (line in s.orEmpty().lineSequence()) {
        val i = line.indexOf(' ')
        val cell = if (i > 0) line.substring(0, i).toIntOrNull() else null
        if (cell != null && cell >= 0 && i < line.length - 1) map[cell] = line.substring(i + 1)
    }
    return map
}

fun serializeLayout(map: Map<Int, String>) = map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} ${it.value}" }

fun layoutFits(map: Map<Int, String>, capacity: Int) = map.keys.all { it < capacity }

/** Byte-bounded LRU, thread-safe. Evicted bitmaps are not recycled: a view may still draw them. */
class IconLru<V : Any>(var maxBytes: Int, private val sizeOf: (V) -> Int) {
    private val map = LinkedHashMap<String, V>(64, 0.75f, true)
    private var bytes = 0

    @Synchronized operator fun get(key: String): V? = map[key]

    @Synchronized fun put(key: String, value: V) {
        map.put(key, value)?.let { bytes -= sizeOf(it) }
        bytes += sizeOf(value)
        trim()
    }

    @Synchronized fun clear() {
        map.clear()
        bytes = 0
    }

    @Synchronized fun size() = map.size

    @Synchronized fun trim() {
        val it = map.values.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            bytes -= sizeOf(it.next())
            it.remove()
        }
    }
}
