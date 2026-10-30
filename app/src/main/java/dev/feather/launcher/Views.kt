package dev.feather.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** One app: a TextView with the icon as its top compound drawable. No nested layout. */
class IconView(ctx: Context) : TextView(ctx) {
    /** An App or a Folder. */
    var item: Any? = null
    val app get() = item as? App
    val folder get() = item as? Folder
    var cell = -1

    init {
        gravity = Gravity.CENTER_HORIZONTAL
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        textSize = 12f
        val d = resources.displayMetrics.density
        val pad = (6 * d).toInt()
        setPadding(pad, pad, pad, pad)
        compoundDrawablePadding = (4 * d).toInt()
    }
}

/** Fixed cols x rows grid. Each IconView child sits in its `cell`, centered vertically. */
class CellGrid(ctx: Context) : ViewGroup(ctx) {
    var cols = 4
    var rows = 1
    private val cw get() = width / cols
    private val ch get() = height / rows
    /** Drop target shown while dragging; -1 for none. */
    var hover = -1
        set(v) {
            if (field != v) invalidate()
            field = v
        }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val radius = 20 * resources.displayMetrics.density

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(c: Canvas) {
        if (hover < 0) return
        val col = hover % cols
        val x = ((if (layoutDirection == View.LAYOUT_DIRECTION_RTL) cols - 1 - col else col) * cw).toFloat()
        // Icon-sized square-ish box centered in the cell, where the icon will land.
        val h = minOf(ch, cw * 6 / 5).toFloat()
        val y = hover / cols * ch + (ch - h) / 2
        val inset = radius / 4
        c.drawRoundRect(x + inset, y + inset, x + cw - inset, y + h - inset, radius, radius, hoverPaint)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
        val w = MeasureSpec.makeMeasureSpec(measuredWidth / cols, MeasureSpec.EXACTLY)
        val h = MeasureSpec.makeMeasureSpec(measuredHeight / rows, MeasureSpec.AT_MOST)
        for (i in 0 until childCount) getChildAt(i).measure(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        for (i in 0 until childCount) {
            val v = getChildAt(i) as IconView
            val pos = v.cell % PAGE_STRIDE
            val col = pos % cols
            val x = (if (rtl) cols - 1 - col else col) * cw
            val y = pos / cols * ch + (ch - v.measuredHeight) / 2
            v.layout(x, y, x + cw, y + v.measuredHeight)
        }
    }

    fun cellAt(x: Float, y: Float): Int {
        val col = (x / cw).toInt().coerceIn(0, cols - 1)
        val row = (y / ch).toInt().coerceIn(0, rows - 1)
        return row * cols + if (layoutDirection == View.LAYOUT_DIRECTION_RTL) cols - 1 - col else col
    }
}

/** Home page indicator: one dot per page, the current one opaque. Draws nothing for a single page. */
class PageDots(ctx: Context) : View(ctx) {
    var count = 1
        set(v) { if (field != v) { field = v; invalidate() } }
    var current = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val r = 3.5f * resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        if (count < 2) return
        val gap = r * 4
        var x = (width - (count - 1) * gap) / 2
        for (i in 0 until count) {
            paint.alpha = if (i == current) 255 else 90
            c.drawCircle(x, height / 2f, r, paint)
            x += gap
        }
    }
}
