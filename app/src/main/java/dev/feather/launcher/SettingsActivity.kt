package dev.feather.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TextAppearanceSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt
import com.google.android.material.R as MaterialR

private const val REPO = "https://github.com/arcbaseproject/Feather-launcher"

/**
 * Plain views, no preference framework. A top-level list opens one page at a time in this activity.
 * Every change is written immediately; MainActivity recreates on return.
 */
class SettingsActivity : AppCompatActivity() {
    private val titles = intArrayOf(R.string.settings_title, R.string.appearance, R.string.layout, R.string.behavior, R.string.performance, R.string.about)
    private var page = 0
    private lateinit var col: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var scroll: ScrollView
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = show(0)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun color(attr: Int) = MaterialColors.getColor(this, attr, javaClass.simpleName)

    override fun onCreate(state: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(state)
        onBackPressedDispatcher.addCallback(this, back)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        toolbar = MaterialToolbar(this).apply {
            setTitleTextAppearance(context, MaterialR.style.TextAppearance_Material3_HeadlineMedium)
            setNavigationIcon(R.drawable.ic_back)
            setNavigationContentDescription(R.string.navigate_back)
            setNavigationOnClickListener { if (page == 0) finish() else show(0) }
        }
        scroll = ScrollView(this).apply {
            id = R.id.settings_scroll
            isFillViewport = true
            clipToPadding = false
            addView(FrameLayout(context).apply {
                addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(MaterialR.attr.colorSurface))
            addView(toolbar, LinearLayout.LayoutParams(-1, dp(72)))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                col.layoutParams = (col.layoutParams as FrameLayout.LayoutParams).apply {
                    width = minOf(resources.displayMetrics.widthPixels - i.left - i.right, dp(600))
                }
                WindowInsets.CONSUMED
            }
        })
        // Theme changes recreate the activity; stay on the same page.
        show(state?.getInt("page") ?: 0, resetScroll = false)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("page", page)
    }

    private fun show(target: Int, resetScroll: Boolean = true) {
        page = target
        back.isEnabled = page != 0
        toolbar.setTitle(titles[page])
        col.removeAllViews()
        when (page) {
            0 -> main()
            1 -> appearance()
            2 -> layout()
            3 -> behavior()
            4 -> performance()
            5 -> about()
        }
        if (resetScroll) scroll.scrollTo(0, 0)
    }

    private fun main() {
        group(
            link(R.drawable.ic_palette, getString(R.string.appearance), getString(R.string.appearance_desc)) { show(1) },
            link(R.drawable.ic_grid, getString(R.string.layout), getString(R.string.layout_desc)) { show(2) },
            link(R.drawable.ic_tune, getString(R.string.behavior), getString(R.string.behavior_desc)) { show(3) },
            link(R.drawable.ic_bolt, getString(R.string.performance), getString(R.string.performance_desc)) { show(4) },
        )
        group(link(R.drawable.ic_info, getString(R.string.about), getString(R.string.version, version())) { show(5) })
    }

    private fun appearance() {
        val p = Prefs.get(this)
        val themes = intArrayOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark)
        val picker = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            themes.forEachIndexed { i, title ->
                addView(MaterialButton(context, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                    id = i + 1
                    setText(title)
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = dp(48)
                    setPadding(dp(8), 0, dp(8), 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            check(p.getInt(Prefs.THEME, 0) + 1)
            addOnButtonCheckedListener { _, id, checked ->
                if (checked) {
                    p.edit().putInt(Prefs.THEME, id - 1).apply()
                    Prefs.applyTheme(this@SettingsActivity, id - 1)
                }
            }
        }
        val theme = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(text(getString(R.string.color_theme), MaterialR.style.TextAppearance_Material3_BodyLarge, MaterialR.attr.colorOnSurface))
            addView(picker, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        group(theme, number(R.string.icon_size, Prefs.ICON_DP, 36, 72, Prefs.D_ICON_DP), toggle(R.string.labels, Prefs.LABELS, true))
    }

    private fun layout() {
        val p = Prefs.get(this)
        val layout = Prefs.layout(this)
        val home = parseLayout(layout.getString("home", null))
        val dock = parseLayout(layout.getString("dock", null))
        header(R.string.home_screen)
        group(
            number(R.string.home_cols, Prefs.HOME_COLS, 3, 7, Prefs.D_COLS) { cols ->
                layoutFits(home, cols * p.getInt(Prefs.HOME_ROWS, Prefs.D_ROWS)) && layoutFits(dock, cols)
            },
            number(R.string.home_rows, Prefs.HOME_ROWS, 3, 9, Prefs.D_ROWS) { rows ->
                layoutFits(home, rows * p.getInt(Prefs.HOME_COLS, Prefs.D_COLS))
            },
        )
        header(R.string.app_drawer)
        group(number(R.string.drawer_cols, Prefs.DRAWER_COLS, 3, 7, Prefs.D_COLS))
    }

    private fun behavior() = group(toggle(R.string.animations, Prefs.ANIM, true), toggle(R.string.autofocus, Prefs.FOCUS, false))

    private fun performance() = group(toggle(R.string.low_mem, Prefs.LOW_MEM, true, R.string.low_mem_desc))

    private fun about() {
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, dp(8))
            addView(ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(96), dp(96)))
            addView(text(getString(R.string.app_name), MaterialR.style.TextAppearance_Material3_HeadlineSmall, MaterialR.attr.colorOnSurface).apply {
                setPadding(0, dp(12), 0, 0)
            })
            addView(text(getString(R.string.version, version()), MaterialR.style.TextAppearance_Material3_BodyMedium, MaterialR.attr.colorOnSurfaceVariant))
            addView(text(getString(R.string.about_tagline), MaterialR.style.TextAppearance_Material3_BodyMedium, MaterialR.attr.colorOnSurfaceVariant).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(12), dp(24), 0)
            })
        })
        group(
            link(R.drawable.ic_code, getString(R.string.source_code), getString(R.string.source_code_desc)) { open(REPO) },
            link(R.drawable.ic_feedback, getString(R.string.report_issue), getString(R.string.report_issue_desc)) { open("$REPO/issues") },
            link(R.drawable.ic_license, getString(R.string.license), getString(R.string.license_desc)) { open("$REPO/blob/main/LICENSE") },
        )
    }

    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    private fun open(url: String) = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show()
    }

    private fun header(title: Int) = col.addView(text(getString(title), MaterialR.style.TextAppearance_Material3_TitleSmall, androidx.appcompat.R.attr.colorPrimary).apply {
        setPadding(dp(20), dp(20), dp(20), 0)
        isAccessibilityHeading = true
    })

    private fun text(s: CharSequence, style: Int, colorAttr: Int) = TextView(this).apply {
        text = s
        setTextAppearance(style)
        setTextColor(color(colorAttr))
    }

    /** Rows of one group sit 2dp apart; only the group's outer corners are fully rounded. */
    private fun group(vararg rows: View) {
        val big = dp(24).toFloat()
        val small = dp(4).toFloat()
        rows.forEachIndexed { i, row ->
            val top = if (i == 0) big else small
            val bottom = if (i == rows.lastIndex) big else small
            val shape = GradientDrawable().apply {
                cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
                setColor(color(MaterialR.attr.colorSurfaceContainer))
            }
            row.background = if (row.isClickable) {
                RippleDrawable(ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorControlHighlight)), shape, null)
            } else shape
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (i == 0) 12 else 2) })
        }
    }

    /** Icon in a tinted circle, title and summary. Opens a page or a link. */
    private fun link(icon: Int, title: String, summary: String, onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72)
        setPadding(dp(16), dp(12), dp(20), dp(12))
        addView(ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = ColorStateList.valueOf(color(MaterialR.attr.colorOnPrimaryContainer))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(MaterialR.attr.colorPrimaryContainer))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, MaterialR.style.TextAppearance_Material3_BodyLarge, MaterialR.attr.colorOnSurface))
            addView(text(summary, MaterialR.style.TextAppearance_Material3_BodyMedium, MaterialR.attr.colorOnSurfaceVariant))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { onClick() }
    }

    /** The whole row is the switch, so TalkBack reads title, summary and state as one control. */
    private fun toggle(title: Int, key: String, def: Boolean, summary: Int = 0): View {
        val p = Prefs.get(this)
        val label = SpannableStringBuilder(getString(title))
        if (summary != 0) {
            val start = label.length + 1
            label.append('\n').append(getString(summary))
            label.setSpan(TextAppearanceSpan(this, MaterialR.style.TextAppearance_Material3_BodyMedium), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            label.setSpan(ForegroundColorSpan(color(MaterialR.attr.colorOnSurfaceVariant)), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return MaterialSwitch(this).apply {
            text = label
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyLarge)
            setTextColor(color(MaterialR.attr.colorOnSurface))
            minHeight = dp(72)
            switchMinWidth = dp(52)
            switchPadding = dp(16)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isChecked = p.getBoolean(key, def)
            setOnCheckedChangeListener { _, v -> p.edit().putBoolean(key, v).apply() }
        }
    }

    private fun number(title: Int, key: String, min: Int, max: Int, def: Int, fits: (Int) -> Boolean = { true }): View {
        val p = Prefs.get(this)
        val name = getString(title)
        val valueLabel = text("", MaterialR.style.TextAppearance_Material3_LabelLarge, androidx.appcompat.R.attr.colorPrimary).apply {
            setPaddingRelative(dp(12), 0, 0, 0)
        }
        fun valueText(v: Int) = if (key == Prefs.ICON_DP) getString(R.string.icon_size_value, v) else v.toString()
        val slider = Slider(this).apply {
            valueFrom = min.toFloat()
            valueTo = max.toFloat()
            stepSize = 1f
            value = p.getInt(key, def).toFloat()
            contentDescription = name
            minimumHeight = dp(48)
            setLabelFormatter { valueText(it.roundToInt()) }
            valueLabel.text = valueText(value.roundToInt())
            addOnChangeListener { slider, number, fromUser ->
                val v = number.roundToInt()
                if (fromUser && v < p.getInt(key, def) && !fits(v)) {
                    slider.value = p.getInt(key, def).toFloat()
                    Toast.makeText(this@SettingsActivity, R.string.grid_too_small, Toast.LENGTH_SHORT).show()
                    return@addOnChangeListener
                }
                valueLabel.text = valueText(v)
                if (fromUser) p.edit().putInt(key, v).apply()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(name, MaterialR.style.TextAppearance_Material3_BodyLarge, MaterialR.attr.colorOnSurface), LinearLayout.LayoutParams(0, -2, 1f))
                addView(valueLabel)
            })
            addView(slider, LinearLayout.LayoutParams(-1, -2))
        }
    }
}
