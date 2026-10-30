package dev.feather.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.animation.PathInterpolator
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var settings: SharedPreferences
    private lateinit var layoutPrefs: SharedPreferences
    private lateinit var home: CellGrid
    private lateinit var dock: CellGrid
    private lateinit var drawer: LinearLayout
    private lateinit var search: EditText
    private lateinit var grid: GridView
    private lateinit var anchor: View
    private lateinit var homeMap: MutableMap<Int, String>
    private lateinit var dockMap: MutableMap<Int, String>
    private lateinit var blank: ColorDrawable
    private var shown: List<App> = emptyList()
    private var labels = true
    private var animate = true
    private var lowMem = true
    private var highlight = 0
    private var settingsChanged = false
    private var drawerOpen = false
    // One worker: icon rasterizing is the only off-main work.
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val back = android.window.OnBackInvokedCallback { closeDrawer(animate) }
    private val onApps = { refresh() }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settingsChanged = true }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Apps.init(this)
        settings = Prefs.get(this)
        layoutPrefs = Prefs.layout(this)
        labels = settings.getBoolean(Prefs.LABELS, true)
        animate = settings.getBoolean(Prefs.ANIM, true)
        lowMem = settings.getBoolean(Prefs.LOW_MEM, true)
        Apps.icons.maxBytes = if (lowMem) Apps.LOW_CACHE else Apps.FULL_CACHE
        val px = (settings.getInt(Prefs.ICON_DP, Prefs.D_ICON_DP) * resources.displayMetrics.density).roundToInt()
        if (px != Apps.iconPx) Apps.icons.clear()
        Apps.iconPx = px
        blank = ColorDrawable(Color.TRANSPARENT).apply { setBounds(0, 0, px, px) }
        homeMap = parseLayout(layoutPrefs.getString("home", null))
        dockMap = parseLayout(layoutPrefs.getString("dock", null))
        val attrs = theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorControlHighlight))
        highlight = attrs.getColor(0, 0x33808080)
        attrs.recycle()

        val cols = settings.getInt(Prefs.HOME_COLS, Prefs.D_COLS)
        home = CellGrid(this).apply {
            this.cols = cols
            rows = settings.getInt(Prefs.HOME_ROWS, Prefs.D_ROWS)
            setOnDragListener(dropListener)
            setOnLongClickListener { homeMenu(); true }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_apps, getString(R.string.all_apps)))
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?) =
                    if (action == R.id.action_apps) { openDrawer(false); true } else super.performAccessibilityAction(host, action, args)
            }
        }
        dock = CellGrid(this).apply {
            this.cols = cols
            setOnDragListener(dropListener)
        }
        val dockH = newIcon(false).run {
            setCompoundDrawables(null, blank, null, null)
            measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            measuredHeight
        }
        val homeColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(home, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(dock, LinearLayout.LayoutParams(-1, dockH))
        }

        val night = resources.configuration.isNightModeActive
        val dp = resources.displayMetrics.density
        search = EditText(this).apply {
            setHint(R.string.search)
            isSingleLine = true
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (56 * dp).toInt()
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = (12 * dp).toInt()
            background = GradientDrawable().apply {
                cornerRadius = 28 * dp
                setColor(getColor(if (night) android.R.color.system_neutral2_800 else android.R.color.system_neutral2_100))
            }
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { v, _, _ -> shown.firstOrNull()?.let { launch(it, v) }; true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter()
            })
        }
        val empty = TextView(this).apply {
            setText(R.string.no_apps)
            gravity = Gravity.CENTER
        }
        grid = GridView(this).apply {
            numColumns = settings.getInt(Prefs.DRAWER_COLS, Prefs.D_COLS)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = (8 * dp).toInt()
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (8 * dp).toInt())
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT)
            isFastScrollEnabled = true
            emptyView = empty
            setOnItemClickListener { _, v, p, _ -> launch(shown[p], v) }
            setOnItemLongClickListener { _, v, p, _ -> appMenu(v, shown[p], null); true }
        }
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                val r = 28 * dp
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(getColor(if (night) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_10))
            }
            visibility = View.GONE
            val m = (16 * dp).toInt()
            addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(m, m, m, m) })
            addView(FrameLayout(context).apply {
                addView(grid)
                addView(empty)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        anchor = View(this)
        val root = FrameLayout(this).apply {
            addView(homeColumn)
            addView(drawer)
            addView(anchor, 1, 1)
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                insets.getInsets(bars).run { homeColumn.setPadding(left, top, right, bottom) }
                insets.getInsets(bars or WindowInsets.Type.ime()).run { drawer.setPadding(left, top, right, bottom) }
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)

        Apps.onChange = onApps
        settings.registerOnSharedPreferenceChangeListener(settingsListener)
        refresh()
        if (state?.getBoolean("drawer") == true) openDrawer(false)
        if (!layoutPrefs.contains("home") && state == null) Toast.makeText(this, R.string.swipe_hint, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        if (settingsChanged) recreate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed: back to the home screen. Animate only if we were already visible.
        if (drawerOpen) closeDrawer(animate && hasWindowFocus())
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("drawer", drawerOpen)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_UI_HIDDEN) Apps.icons.clear()
    }

    override fun onDestroy() {
        if (Apps.onChange === onApps) Apps.onChange = null
        settings.unregisterOnSharedPreferenceChangeListener(settingsListener)
        worker.shutdownNow()
        super.onDestroy()
    }

    // --- swipe up opens the drawer, pull down at the top closes it ---

    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private val swipeSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop * 3 }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> if (!swiping) {
                val dy = e.rawY - downY
                val open = drawerOpen
                if (abs(dy) > swipeSlop && abs(dy) > abs(e.rawX - downX) &&
                    (if (open) dy > 0 && !grid.canScrollVertically(-1) else dy < 0)
                ) {
                    swiping = true
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    if (open) closeDrawer(animate) else openDrawer(animate)
                }
            }
        }
        return swiping || super.dispatchTouchEvent(e)
    }

    // --- drawer ---

    private fun openDrawer(anim: Boolean) {
        if (drawerOpen) return
        drawerOpen = true
        drawer.animate().withEndAction(null).cancel()
        if (grid.adapter == null) grid.adapter = adapter
        if (drawer.visibility != View.VISIBLE) drawer.translationY = drawer.height.toFloat()
        drawer.visibility = View.VISIBLE
        // Slide only: animating alpha would render the whole drawer into a full-screen offscreen layer.
        if (anim) drawer.animate().translationY(0f).setDuration(300).setInterpolator(EMPHASIZED_DECELERATE)
        else drawer.translationY = 0f
        if (!resources.configuration.isNightModeActive) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
        if (settings.getBoolean(Prefs.FOCUS, false) && search.requestFocus()) window.insetsController?.show(WindowInsets.Type.ime())
    }

    private fun closeDrawer(anim: Boolean) {
        if (!drawerOpen) return
        drawerOpen = false
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
        window.insetsController?.hide(WindowInsets.Type.ime())
        window.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        search.clearFocus()
        val done = {
            drawer.visibility = View.GONE
            drawer.translationY = 0f
            search.text.clear()
            grid.setSelection(0)
            if (lowMem) {
                // Drops the drawer's views and their icon bitmaps; reopening rebinds.
                grid.adapter = null
                Apps.icons.clear()
                // Icon bitmaps and other apps' resource tables hold MBs of GPU/native memory behind tiny
                // Java objects, so ART's heap-size trigger never fires. One GC returns it.
                worker.execute { Runtime.getRuntime().gc() }
            }
        }
        if (anim) drawer.animate().translationY(drawer.height.toFloat()).setDuration(200).setInterpolator(EMPHASIZED_ACCELERATE).withEndAction(done)
        else { drawer.animate().cancel(); done() }
    }

    private fun filter() {
        val q = normalize(search.text.toString().trim())
        shown = if (q.isEmpty()) Apps.list else Apps.list.filter { q in it.search }
        adapter.notifyDataSetChanged()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View =
            ((convert as? IconView) ?: newIcon(labels)).also { bind(it, shown[p]) }
    }

    // --- icons ---

    private fun newIcon(showLabel: Boolean) = IconView(this).apply {
        if (!showLabel) textSize = 0f // no text line at all, not just empty text
        // Rounded press ripple, same shape as the drag target highlight.
        val mask = GradientDrawable().apply {
            cornerRadius = 20 * resources.displayMetrics.density
            setColor(Color.WHITE)
        }
        background = RippleDrawable(ColorStateList.valueOf(highlight), null, mask)
    }

    private fun bind(v: IconView, app: App) {
        v.app = app
        if (v.textSize > 0f) v.text = app.label
        v.contentDescription = app.label
        val cached = Apps.icons[app.key]
        if (cached != null) return setIcon(v, cached)
        v.setCompoundDrawables(null, blank, null, null)
        val px = Apps.iconPx
        worker.execute {
            if (v.app !== app) return@execute // recycled before we got to it
            val b = runCatching { Apps.loadIcon(app, px) }.getOrNull() ?: return@execute
            v.post { if (v.app === app) setIcon(v, b) }
        }
    }

    private fun setIcon(v: IconView, b: Bitmap) {
        val d = BitmapDrawable(resources, b)
        d.setBounds(0, 0, Apps.iconPx, Apps.iconPx)
        if (v.app?.suspended == true) d.alpha = 100
        v.setCompoundDrawables(null, d, null, null)
    }

    // --- home & dock ---

    private fun refresh() {
        filter()
        bindGrid(home, homeMap)
        bindGrid(dock, dockMap)
    }

    private fun bindGrid(g: CellGrid, map: Map<Int, String>) {
        g.removeAllViews()
        for ((cell, key) in map) {
            if (cell >= g.cols * g.rows) continue
            val app = Apps.byKey[key] ?: continue
            val v = newIcon(g === home && labels)
            if (g === home) {
                v.typeface = MEDIUM
                v.setTextColor(Color.WHITE)
                v.setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
            }
            v.cell = cell
            bind(v, app)
            v.setOnClickListener { launch(app, it) }
            v.setOnLongClickListener {
                it.startDragAndDrop(null, View.DragShadowBuilder(it), it, 0)
                it.alpha = 0f // lifted: the shadow follows the finger, the target cell highlights
                true
            }
            g.addView(v)
        }
    }

    private fun mapFor(g: CellGrid) = if (g === home) homeMap else dockMap

    private val dropListener = View.OnDragListener { target, e ->
        val v = e.localState as? IconView ?: return@OnDragListener false
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> (target as CellGrid).run { hover = cellAt(e.x, e.y) }
            DragEvent.ACTION_DRAG_EXITED -> (target as CellGrid).hover = -1
            DragEvent.ACTION_DROP -> {
                (target as CellGrid).hover = -1
                val from = v.parent as? CellGrid ?: return@OnDragListener false
                val to = target as CellGrid
                val cell = to.cellAt(e.x, e.y)
                // Long-press without moving: show the menu instead.
                if (to === from && cell == v.cell) v.post { appMenu(v, v.app ?: return@post, from) }
                else move(from, v.cell, to, cell)
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                (target as CellGrid).hover = -1
                v.alpha = 1f
            }
        }
        true
    }

    private fun move(from: CellGrid, fromCell: Int, to: CellGrid, toCell: Int) {
        val src = mapFor(from)
        val dst = mapFor(to)
        val key = src.remove(fromCell) ?: return
        dst.put(toCell, key)?.let { src[fromCell] = it }
        saveAndRebind()
    }

    private fun place(g: CellGrid, app: App) {
        val map = mapFor(g)
        val cell = (0 until g.cols * g.rows).firstOrNull { !map.containsKey(it) }
            ?: return Toast.makeText(this, R.string.no_space, Toast.LENGTH_SHORT).show()
        map[cell] = app.key
        saveAndRebind()
    }

    private fun saveAndRebind() {
        layoutPrefs.edit().putString("home", serializeLayout(homeMap)).putString("dock", serializeLayout(dockMap)).apply()
        bindGrid(home, homeMap)
        bindGrid(dock, dockMap)
    }

    // --- actions ---

    private fun launch(app: App, v: View) {
        val bounds = Rect().also { v.getGlobalVisibleRect(it) }
        val opts = if (animate) ActivityOptions.makeClipRevealAnimation(v, 0, 0, v.width, v.height).toBundle() else null
        try {
            Apps.launcherApps.startMainActivity(app.info.componentName, app.user, bounds, opts)
        } catch (e: RuntimeException) { // uninstalled, disabled, or profile paused since the last refresh
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shortcuts(app: App): List<ShortcutInfo> = runCatching {
        if (!Apps.launcherApps.hasShortcutHostPermission()) return emptyList()
        val q = LauncherApps.ShortcutQuery()
            .setPackage(app.info.componentName.packageName)
            .setActivity(app.info.componentName)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        Apps.launcherApps.getShortcuts(q, app.user).orEmpty().sortedBy { it.rank }.take(4)
    }.getOrDefault(emptyList())

    private fun Menu.item(title: CharSequence, action: () -> Unit) = add(title).setOnMenuItemClickListener { action(); true }

    /** `from` is the home grid or dock the icon sits in, or null for the drawer. */
    private fun appMenu(v: View, app: App, from: CellGrid?) {
        val popup = PopupMenu(this, v)
        val m = popup.menu
        for (s in shortcuts(app)) m.item(s.shortLabel ?: continue) {
            runCatching { Apps.launcherApps.startShortcut(s, null, null) }
                .onFailure { Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show() }
        }
        if (from == null) {
            m.item(getString(R.string.add_home)) { place(home, app); closeDrawer(animate) }
            m.item(getString(R.string.add_dock)) { place(dock, app); closeDrawer(animate) }
        } else {
            m.item(getString(R.string.remove)) { mapFor(from).remove((v as IconView).cell); saveAndRebind() }
        }
        m.item(getString(R.string.app_info)) {
            runCatching { Apps.launcherApps.startAppDetailsActivity(app.info.componentName, app.user, null, null) }
        }
        popup.show()
    }

    private fun homeMenu() {
        anchor.translationX = downX
        anchor.translationY = downY
        val popup = PopupMenu(this, anchor)
        popup.menu.item(getString(R.string.wallpaper)) {
            runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.wallpaper))) }
        }
        popup.menu.item(getString(R.string.settings)) { startActivity(Intent(this, SettingsActivity::class.java)) }
        popup.show()
    }

    private companion object {
        // Material 3 motion curves.
        val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        val MEDIUM: Typeface = Typeface.create(null, 500, false)
    }
}
