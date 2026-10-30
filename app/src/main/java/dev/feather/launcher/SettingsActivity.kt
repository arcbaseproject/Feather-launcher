package dev.feather.launcher

import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt
import com.google.android.material.R as MaterialR

/** Plain views, no preference framework. Every change is written immediately; MainActivity recreates on return. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(state)
        val p = Prefs.get(this)
        val layout = Prefs.layout(this)
        val home = parseLayout(layout.getString("home", null))
        val dock = parseLayout(layout.getString("dock", null))
        fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
        fun color(attr: Int) = MaterialColors.getColor(this, attr, javaClass.simpleName)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        var card = col

        // Each section is a rounded card under its header.
        fun header(title: Int) {
            col.addView(TextView(this).apply {
                setText(title)
                setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleSmall)
                setTextColor(color(androidx.appcompat.R.attr.colorPrimary))
                setPadding(dp(16), dp(24), dp(16), dp(12))
                isAccessibilityHeading = true
            })
            card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            col.addView(MaterialCardView(this).apply {
                radius = dp(24).toFloat()
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(color(MaterialR.attr.colorSurfaceContainerLow))
                addView(card, FrameLayout.LayoutParams(-1, -2))
            })
        }

        fun toggle(title: Int, key: String, def: Boolean, summary: Int = 0) {
            card.addView(MaterialSwitch(this).apply {
                setText(title)
                setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyLarge)
                setTextColor(color(MaterialR.attr.colorOnSurface))
                minHeight = dp(64)
                switchMinWidth = dp(52)
                switchPadding = dp(16)
                isChecked = p.getBoolean(key, def)
                setOnCheckedChangeListener { _, v -> p.edit().putBoolean(key, v).apply() }
            }, LinearLayout.LayoutParams(-1, -2))
            if (summary != 0) card.addView(TextView(this).apply {
                setText(summary)
                setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
                setTextColor(color(MaterialR.attr.colorOnSurfaceVariant))
                setPadding(0, 0, 0, dp(16))
            })
        }

        fun number(title: Int, key: String, min: Int, max: Int, def: Int, fits: (Int) -> Boolean = { true }) {
            val name = getString(title)
            val label = TextView(this).apply {
                text = name
                setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyLarge)
                setTextColor(color(MaterialR.attr.colorOnSurface))
            }
            val valueLabel = TextView(this).apply {
                setTextAppearance(MaterialR.style.TextAppearance_Material3_LabelLarge)
                setTextColor(color(androidx.appcompat.R.attr.colorPrimary))
                setPaddingRelative(dp(12), 0, 0, 0)
            }
            card.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(16), 0, 0)
                addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                addView(valueLabel)
            })
            fun valueText(v: Int) = if (key == Prefs.ICON_DP) getString(R.string.icon_size_value, v) else v.toString()
            card.addView(Slider(this).apply {
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
            }, LinearLayout.LayoutParams(-1, -2))
        }

        header(R.string.appearance)
        card.addView(TextView(this).apply {
            setText(R.string.color_theme)
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyLarge)
            setTextColor(color(MaterialR.attr.colorOnSurface))
            setPadding(0, dp(16), 0, dp(12))
        })
        val themes = intArrayOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark)
        card.addView(MaterialButtonToggleGroup(this).apply {
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
        }, LinearLayout.LayoutParams(-1, -2))
        number(R.string.icon_size, Prefs.ICON_DP, 36, 72, Prefs.D_ICON_DP)
        toggle(R.string.labels, Prefs.LABELS, true)

        header(R.string.layout)
        number(R.string.home_cols, Prefs.HOME_COLS, 3, 7, Prefs.D_COLS) { cols ->
            layoutFits(home, cols * p.getInt(Prefs.HOME_ROWS, Prefs.D_ROWS)) && layoutFits(dock, cols)
        }
        number(R.string.home_rows, Prefs.HOME_ROWS, 3, 9, Prefs.D_ROWS) { rows ->
            layoutFits(home, rows * p.getInt(Prefs.HOME_COLS, Prefs.D_COLS))
        }
        number(R.string.drawer_cols, Prefs.DRAWER_COLS, 3, 7, Prefs.D_COLS)

        header(R.string.behavior)
        toggle(R.string.animations, Prefs.ANIM, true)
        toggle(R.string.autofocus, Prefs.FOCUS, false)

        header(R.string.performance)
        toggle(R.string.low_mem, Prefs.LOW_MEM, true, R.string.low_mem_desc)

        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.settings_title)
            setTitleTextAppearance(context, MaterialR.style.TextAppearance_Material3_HeadlineMedium)
            setNavigationIcon(R.drawable.ic_back)
            setNavigationContentDescription(R.string.navigate_back)
            setNavigationOnClickListener { finish() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(MaterialR.attr.colorSurface))
            addView(toolbar, LinearLayout.LayoutParams(-1, dp(72)))
            addView(ScrollView(context).apply {
                id = R.id.settings_scroll
                isFillViewport = true
                clipToPadding = false
                addView(FrameLayout(context).apply {
                    addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
                })
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                col.layoutParams = (col.layoutParams as FrameLayout.LayoutParams).apply {
                    width = minOf(resources.displayMetrics.widthPixels - i.left - i.right, dp(600))
                }
                WindowInsets.CONSUMED
            }
        })
    }
}
