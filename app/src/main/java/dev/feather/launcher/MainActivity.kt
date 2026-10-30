package dev.feather.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.Dialog
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
import android.view.Window
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
import java.text.Collator
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
    private lateinit var folders: MutableMap<Int, Folder>
    private lateinit var blank: ColorDrawable
    /** Drawer items: App or Folder. */
    private var shown: List<Any> = emptyList()
    private var folderBg = 0
    private var hovered: View? = null
    private lateinit var dots: PageDots
    private var page = 0
    // Home/dock drag source, kept apart from the view: flipping pages mid-drag rebinds the grid.
    private var dragFrom: CellGrid? = null
    private var dragCell = -1
    private var edgeDir = 0
    private val edgePx by lazy { (32 * resources.displayMetrics.density).toInt() }
    private val flipPage = Runnable {
        // One empty page past the last lets a drag start a new page.
        val next = page + edgeDir
        if (next >= 0 && next <= pageCount(homeMap)) showPage(next)
        edgeDir = 0 // still at the edge: the next drag event schedules another flip
    }
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
        folders = parseFolders(layoutPrefs.getString("folders", null))
        folderBg = getColor(if (resources.configuration.isNightModeActive) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_100)
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
                    if (page < pageCount(homeMap) - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    if (page > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?) = when (action) {
                    R.id.action_apps -> { openDrawer(false); true }
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { showPage((page + 1).coerceAtMost(pageCount(homeMap) - 1)); true }
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { showPage((page - 1).coerceAtLeast(0)); true }
                    else -> super.performAccessibilityAction(host, action, args)
                }
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
            dots = PageDots(context)
            addView(dots, LinearLayout.LayoutParams(-1, (16 * resources.displayMetrics.density).toInt()))
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
            setOnEditorActionListener { v, _, _ -> (shown.firstOrNull() as? App)?.let { launch(it, v) }; true }
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
            setOnItemClickListener { _, v, p, _ -> open(shown[p], v) }
            setOnItemLongClickListener { _, v, p, _ ->
                when (val item = shown[p]) {
                    is App -> lift(v)
                    is Folder -> folderMenu(v, item, null)
                }
                true
            }
            setOnDragListener(drawerDrop)
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
        page = state?.getInt("page") ?: 0
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
        else if (hasWindowFocus()) showPage(0) // Home pressed while on home
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("drawer", drawerOpen)
        out.putInt("page", page)
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
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val open = drawerOpen
                if (abs(dy) > swipeSlop && abs(dy) > abs(dx) &&
                    (if (open) dy > 0 && !grid.canScrollVertically(-1) else dy < 0)
                ) {
                    takeGesture(e)
                    if (open) closeDrawer(animate) else openDrawer(animate)
                } else if (!open && abs(dx) > swipeSlop && abs(dx) > abs(dy)) {
                    val forward = (dx < 0) != (home.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                    val next = page + if (forward) 1 else -1
                    if (next in 0 until pageCount(homeMap)) {
                        takeGesture(e)
                        showPage(next)
                    }
                }
            }
        }
        return swiping || super.dispatchTouchEvent(e)
    }

    /** The rest of this gesture is ours: cancel it for the views. */
    private fun takeGesture(e: MotionEvent) {
        swiping = true
        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    // --- home pages: one grid, rebound per page, so only the visible page's icons are in memory ---

    private fun showPage(p: Int) {
        if (p == page) return
        val dir = if (p > page) 1 else -1
        page = p
        dots.current = p
        if (!animate) return bindGrid(home, homeMap)
        val w = home.width * if (home.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
        home.animate().cancel()
        home.animate().translationX(-dir * w).setDuration(120).setInterpolator(EMPHASIZED_ACCELERATE).withEndAction {
            bindGrid(home, homeMap)
            home.translationX = dir * w
            home.animate().translationX(0f).setDuration(220).setInterpolator(EMPHASIZED_DECELERATE).withEndAction(null)
        }
    }

    /** Hovering at the left or right edge while dragging flips the page after a short hold. */
    private fun edge(dir: Int) {
        if (dir == edgeDir) return
        home.removeCallbacks(flipPage)
        edgeDir = dir
        if (dir != 0) home.postDelayed(flipPage, 600)
    }

    private fun cellIn(g: CellGrid, pos: Int) = if (g === home) page * PAGE_STRIDE + pos else pos

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

    /** Searching lists matching apps, folders or not. Otherwise drawer folders replace the apps they hold. */
    private fun filter() {
        val q = normalize(search.text.toString().trim())
        shown = if (q.isNotEmpty()) Apps.list.filter { q in it.search } else {
            val drawerFolders = folders.values.filter { it.drawer && folderApps(it).isNotEmpty() }
            val grouped = drawerFolders.flatMapTo(HashSet()) { it.keys }
            (Apps.list.filter { it.key !in grouped } + drawerFolders).sortedWith(compareBy(Collator.getInstance(), ::label))
        }
        adapter.notifyDataSetChanged()
    }

    private fun folderApps(f: Folder) = f.keys.mapNotNull { Apps.byKey[it] }
    private fun folderFor(key: String?) = key?.takeIf { it.startsWith(FOLDER_PREFIX) }?.removePrefix(FOLDER_PREFIX)?.toIntOrNull()?.let { folders[it] }
    private fun label(item: Any) = if (item is App) item.label else (item as Folder).name.ifBlank { getString(R.string.folder) }

    private fun open(item: Any, v: View) = if (item is App) launch(item, v) else openFolder(item as Folder)

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

    private fun bind(v: IconView, item: Any) {
        v.item = item
        val label = label(item)
        if (v.textSize > 0f) v.text = label
        v.contentDescription = if (item is Folder) getString(R.string.folder_desc, label) else label
        // A folder's icon changes with its contents, so its cache key does too.
        val key = if (item is App) item.key else "F$folderBg:" + (item as Folder).keys.joinToString(",")
        val cached = Apps.icons[key]
        if (cached != null) return setIcon(v, cached)
        v.setCompoundDrawables(null, blank, null, null)
        val px = Apps.iconPx
        val bg = folderBg
        val apps = (item as? Folder)?.let(::folderApps)
        worker.execute {
            if (v.item !== item) return@execute // recycled before we got to it
            val b = runCatching { if (item is App) Apps.loadIcon(item, px) else Apps.loadFolderIcon(key, apps!!, px, bg) }
                .getOrNull() ?: return@execute
            v.post { if (v.item === item) setIcon(v, b) }
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
        if (dragFrom == null) page = page.coerceAtMost(pageCount(homeMap) - 1) // its last app left
        filter()
        bindGrid(home, homeMap)
        bindGrid(dock, dockMap)
    }

    private fun bindGrid(g: CellGrid, map: Map<Int, String>) {
        g.removeAllViews()
        for ((cell, key) in map) {
            if (g === home && cell / PAGE_STRIDE != page) continue
            if (cell % PAGE_STRIDE >= g.cols * g.rows) continue
            val item: Any = Apps.byKey[key] ?: folderFor(key)?.takeIf { folderApps(it).isNotEmpty() } ?: continue
            val v = newIcon(g === home && labels)
            if (g === home) {
                v.typeface = MEDIUM
                v.setTextColor(Color.WHITE)
                v.setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
            }
            v.cell = cell
            bind(v, item)
            v.setOnClickListener { open(item, it) }
            v.setOnLongClickListener { lift(it); true }
            if (g === dragFrom && cell == dragCell) v.alpha = 0f // still being dragged
            g.addView(v)
        }
        if (g === home) {
            dots.count = maxOf(pageCount(map), page + 1)
            dots.current = page
        }
    }

    private fun mapFor(g: CellGrid) = if (g === home) homeMap else dockMap

    /** Starts a drag. The view hides; the shadow follows the finger and the target highlights. */
    private fun lift(v: View) {
        dragFrom = v.parent as? CellGrid
        dragCell = (v as IconView).cell
        v.startDragAndDrop(null, View.DragShadowBuilder(v), v, 0)
        v.alpha = 0f
    }

    /** Long-press without moving still drops: show the menu instead. */
    private fun menuFor(v: IconView, from: CellGrid?) = v.post {
        if (!v.isAttachedToWindow) return@post
        v.app?.let { appMenu(v, it, from) } ?: v.folder?.let { folderMenu(v, it, from) }
    }

    private val dropListener = View.OnDragListener { target, e ->
        val v = e.localState as? IconView ?: return@OnDragListener false
        val to = target as CellGrid
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                to.hover = to.cellAt(e.x, e.y)
                if (to === home) {
                    val side = when { e.x < edgePx -> -1; e.x > to.width - edgePx -> 1; else -> 0 }
                    edge(if (to.layoutDirection == View.LAYOUT_DIRECTION_RTL) -side else side)
                }
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                to.hover = -1
                if (to === home) edge(0)
            }
            DragEvent.ACTION_DROP -> {
                to.hover = -1
                edge(0)
                val from = dragFrom ?: return@OnDragListener false
                val cell = cellIn(to, to.cellAt(e.x, e.y))
                val targetKey = mapFor(to)[cell]
                when {
                    to === from && cell == dragCell -> menuFor(v, from)
                    // An app dropped on an app or a folder groups them.
                    v.app != null && (Apps.byKey[targetKey] != null || folderFor(targetKey) != null) -> group(from, dragCell, to, cell)
                    else -> move(from, dragCell, to, cell)
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                to.hover = -1
                edge(0)
                v.alpha = 1f
                if (dragFrom != null) {
                    dragFrom = null
                    dragCell = -1
                    refresh() // shows the icon again and drops an empty page left by a cancelled drag
                }
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

    private fun group(from: CellGrid, fromCell: Int, to: CellGrid, toCell: Int) {
        val key = mapFor(from).remove(fromCell) ?: return
        val dst = mapFor(to)
        val target = dst.getValue(toCell)
        val existing = folderFor(target)
        if (existing != null) {
            if (key !in existing.keys) existing.keys += key
            saveAndRebind()
        } else {
            dst[toCell] = newFolder(target, key, drawer = false).key
            saveAndRebind()
        }
    }

    private fun newFolder(first: String, second: String, drawer: Boolean): Folder {
        val f = Folder((folders.keys.maxOrNull() ?: 0) + 1, "", mutableListOf(first, second), drawer)
        folders[f.id] = f
        return f
    }

    // --- drawer drag: drop an app on an app or a drawer folder to group them ---

    private val drawerDrop = View.OnDragListener { _, e ->
        val v = e.localState as? IconView ?: return@OnDragListener false
        if (v.parent !== grid) return@OnDragListener false
        val pos = grid.pointToPosition(e.x.toInt(), e.y.toInt())
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> hover(grid.getChildAt(pos - grid.firstVisiblePosition))
            DragEvent.ACTION_DRAG_EXITED -> hover(null)
            DragEvent.ACTION_DROP -> {
                hover(null)
                val app = v.app ?: return@OnDragListener false
                when (val target = shown.getOrNull(pos)) {
                    app -> menuFor(v, null)
                    is Folder -> {
                        detachFromDrawerFolders(app.key, target)
                        if (app.key !in target.keys) target.keys += app.key
                        saveAndRebind()
                    }
                    is App -> {
                        detachFromDrawerFolders(app.key, null)
                        detachFromDrawerFolders(target.key, null)
                        newFolder(target.key, app.key, drawer = true)
                        saveAndRebind()
                    }
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                hover(null)
                v.alpha = 1f
            }
        }
        true
    }

    /** Shows the drop target in the drawer with the item's pressed highlight. */
    private fun hover(v: View?) {
        if (v === hovered) return
        hovered?.isPressed = false
        v?.isPressed = true
        hovered = v
    }

    /** An app sits in at most one drawer folder. Searching can show a grouped app, so regrouping moves it. */
    private fun detachFromDrawerFolders(key: String, except: Folder?) {
        for (f in folders.values.toList()) {
            if (f.drawer && f !== except && f.keys.remove(key) && f.keys.size < 2) folders.remove(f.id)
        }
    }

    private fun removeFromFolder(f: Folder, key: String) {
        f.keys.remove(key)
        if (f.keys.size < 2) {
            // One app left: the folder turns back into that app.
            folders.remove(f.id)
            for (map in listOf(homeMap, dockMap)) {
                for ((cell, k) in map.entries.toList()) if (k == f.key) {
                    val last = f.keys.firstOrNull()
                    if (last != null) map[cell] = last else map.remove(cell)
                }
            }
        }
        saveAndRebind()
    }

    private fun openFolder(f: Folder, rename: Boolean = false) {
        val dp = resources.displayMetrics.density
        val apps = folderApps(f)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val name = EditText(this).apply {
            setText(f.name)
            setHint(R.string.folder)
            textSize = 22f
            gravity = Gravity.CENTER
            isSingleLine = true
            background = null
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ ->
                clearFocus()
                dialog.window?.insetsController?.hide(WindowInsets.Type.ime())
                true
            }
        }
        // As many columns as apps, up to 4, so a small folder has no empty slots.
        val cols = apps.size.coerceIn(1, 4)
        val cellW = (88 * dp).toInt()
        val cellH = newIcon(labels).run {
            setCompoundDrawables(null, blank, null, null)
            text = "A"
            measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            measuredHeight
        }
        val list = CellGrid(this).apply {
            this.cols = cols
            rows = (apps.size + cols - 1) / cols
            apps.forEachIndexed { i, app ->
                addView(newIcon(labels).also { v ->
                    v.cell = i
                    bind(v, app)
                    v.setOnClickListener { dialog.dismiss(); launch(app, v) }
                    v.setOnLongClickListener {
                        val popup = PopupMenu(this@MainActivity, v)
                        popup.menu.item(getString(R.string.remove_from_folder)) {
                            dialog.dismiss()
                            removeFromFolder(f, app.key)
                            if (folders.containsKey(f.id)) openFolder(f)
                        }
                        popup.menu.item(getString(R.string.app_info)) {
                            runCatching { Apps.launcherApps.startAppDetailsActivity(app.info.componentName, app.user, null, null) }
                        }
                        popup.show()
                        true
                    }
                })
            }
        }
        dialog.setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad })
            addView(list, LinearLayout.LayoutParams(-1, list.rows * cellH))
        })
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = 28 * dp
            setColor(getColor(if (resources.configuration.isNightModeActive) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_10))
        })
        dialog.setOnDismissListener {
            val newName = name.text.toString().trim()
            if (newName != f.name) {
                f.name = newName
                saveAndRebind()
            }
        }
        dialog.show()
        val width = cols * cellW + (32 * dp).toInt() // cells plus the content padding
        dialog.window?.setLayout(minOf(width, (resources.displayMetrics.widthPixels * 0.9f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
        if (rename) {
            name.requestFocus()
            name.selectAll()
            dialog.window?.insetsController?.show(WindowInsets.Type.ime())
        }
    }

    private fun folderMenu(v: View, f: Folder, from: CellGrid?) {
        val popup = PopupMenu(this, v)
        popup.menu.item(getString(R.string.rename)) { openFolder(f, rename = true) }
        if (from != null) {
            // The apps stay in the drawer; only the home shortcut goes.
            popup.menu.item(getString(R.string.remove)) {
                mapFor(from).remove((v as IconView).cell)
                folders.remove(f.id)
                saveAndRebind()
            }
        } else {
            popup.menu.item(getString(R.string.ungroup)) {
                folders.remove(f.id)
                saveAndRebind()
            }
        }
        popup.show()
    }

    private fun place(g: CellGrid, app: App) {
        val map = mapFor(g)
        val size = g.cols * g.rows
        // Home: first free cell on any page, or a new page. The dock has one row.
        val pages = if (g === home) pageCount(map) + 1 else 1
        val cell = (0 until pages).asSequence().flatMap { pg -> (0 until size).asSequence().map { pg * PAGE_STRIDE + it } }
            .firstOrNull { !map.containsKey(it) }
            ?: return Toast.makeText(this, R.string.no_space, Toast.LENGTH_SHORT).show()
        map[cell] = app.key
        if (g === home) page = cell / PAGE_STRIDE
        saveAndRebind()
    }

    private fun saveAndRebind() {
        layoutPrefs.edit()
            .putString("home", serializeLayout(homeMap))
            .putString("dock", serializeLayout(dockMap))
            .putString("folders", serializeFolders(folders))
            .apply()
        refresh()
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
