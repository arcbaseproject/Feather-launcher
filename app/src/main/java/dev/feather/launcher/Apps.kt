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
import android.graphics.Paint
import android.graphics.drawable.Drawable
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

    private fun drawable(app: App): Drawable {
        val pm = ctx.packageManager
        val d = runCatching { app.info.getIcon(ctx.resources.displayMetrics.densityDpi) }.getOrNull()
            ?: pm.defaultActivityIcon
        return if (app.user != Process.myUserHandle()) pm.getUserBadgedIcon(d, app.user) else d
    }

    /** One GPU copy, no Java heap copy. */
    private fun cacheAsHardware(key: String, soft: Bitmap): Bitmap {
        val bitmap = soft.copy(Bitmap.Config.HARDWARE, false) ?: soft
        if (bitmap !== soft) soft.recycle()
        icons.put(key, bitmap)
        return bitmap
    }

    /** Worker thread. Rasterizes once at display size into a HARDWARE bitmap. */
    fun loadIcon(app: App, px: Int): Bitmap {
        icons[app.key]?.let { return it }
        val soft = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        drawable(app).apply { setBounds(0, 0, px, px) }.draw(Canvas(soft))
        return cacheAsHardware(app.key, soft)
    }

    /** Worker thread. A 2x2 preview of the first four apps on a rounded square. */
    fun loadFolderIcon(key: String, apps: List<App>, px: Int, bg: Int): Bitmap {
        icons[key]?.let { return it }
        val soft = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(soft)
        c.drawRoundRect(0f, 0f, px.toFloat(), px.toFloat(), px * 0.3f, px * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        val cell = (px * 0.34f).toInt()
        val gap = (px * 0.06f).toInt()
        val start = (px - 2 * cell - gap) / 2
        apps.take(4).forEachIndexed { i, app ->
            val l = start + i % 2 * (cell + gap)
            val t = start + i / 2 * (cell + gap)
            drawable(app).apply { setBounds(l, t, l + cell, t + cell) }.draw(c)
        }
        return cacheAsHardware(key, soft)
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

/** A named group of app keys. Home folders sit in a layout cell as `key`; drawer folders hide their apps from the drawer list. */
class Folder(val id: Int, var name: String, val keys: MutableList<String>, val drawer: Boolean) {
    val key get() = FOLDER_PREFIX + id
}

const val FOLDER_PREFIX = "folder:"

/** One folder per line, tab-separated: id, 1 for a drawer folder, name, comma-separated app keys. */
fun parseFolders(s: String?): MutableMap<Int, Folder> {
    val map = HashMap<Int, Folder>()
    for (line in s.orEmpty().lineSequence()) {
        val f = line.split('\t')
        val id = f.getOrNull(0)?.toIntOrNull() ?: continue
        if (f.size != 4) continue
        map[id] = Folder(id, f[2], f[3].split(',').filter { it.isNotEmpty() }.toMutableList(), f[1] == "1")
    }
    return map
}

private val SEPARATORS = Regex("[\t\r\n]")

fun serializeFolders(map: Map<Int, Folder>) = map.values.sortedBy { it.id }.joinToString("\n") {
    "${it.id}\t${if (it.drawer) 1 else 0}\t${SEPARATORS.replace(it.name, " ")}\t${it.keys.joinToString(",")}"
}

fun serializeLayout(map: Map<Int, String>) = map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} ${it.value}" }

/** Home cells are `page * PAGE_STRIDE + position`, so resizing the grid never moves apps across pages. */
const val PAGE_STRIDE = 1000

fun layoutFits(map: Map<Int, String>, capacity: Int) = map.keys.all { it % PAGE_STRIDE < capacity }

/** Pages holding at least one cell, counting from page 0. */
fun pageCount(map: Map<Int, String>) = (map.keys.maxOrNull() ?: 0) / PAGE_STRIDE + 1

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
