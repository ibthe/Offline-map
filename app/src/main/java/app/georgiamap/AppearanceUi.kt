package app.georgiamap

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

class ModeItem(val key: String, val title: String, val hint: String)
class PaletteItem(val key: String, val title: String, val hint: String, val swatch: Int, val swatch2: Int)

/** One screen for the whole look: mode (auto / light / twilight / dark) and colour theme. */
object AppearanceUi {
    fun show(
        activity: AppCompatActivity, modeKey: String, paletteKey: String,
        modes: List<ModeItem>, palettes: List<PaletteItem>,
        onMode: (String) -> Unit, onPalette: (String) -> Unit
    ) {
        val dialog = BottomSheetDialog(activity)
        val ctx: Context = dialog.context
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()
        fun color(attr: Int, fallback: Int) = MaterialColors.getColor(ctx, attr, fallback)
        val primary = color(R.attr.gmTextPrimary, Color.BLACK)
        val secondary = color(R.attr.gmTextSecondary, Color.GRAY)
        val accent = color(R.attr.gmAccent, Color.BLUE)
        val divider = color(R.attr.gmDivider, Color.LTGRAY)
        val onContainer = color(R.attr.gmOnAccentContainer, Color.BLACK)
        val ripple = TypedValue().also { ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }

        fun text(style: Int, c: Int, s: String) = TextView(ctx).apply { setTextAppearance(style); setTextColor(c); text = s }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        col.addView(text(R.style.TextAppearance_Georgia_Headline, primary, "Оформление").apply { setPadding(dp(8), dp(4), dp(8), dp(8)) })

        // ---- mode: 4 equal chips
        col.addView(text(R.style.TextAppearance_Georgia_Label, secondary, "РЕЖИМ").apply { setPadding(dp(8), dp(12), dp(8), dp(8)) })
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        modes.forEachIndexed { i, m ->
            val sel = m.key == modeKey
            val chip = TextView(ctx).apply {
                setTextAppearance(R.style.TextAppearance_Georgia_Label)
                text = m.title
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(if (sel) onContainer else secondary)
                setBackgroundResource(if (sel) R.drawable.bg_chip_selected else R.drawable.bg_chip)
                isClickable = true
                isFocusable = true
                setOnClickListener { if (!sel) { dialog.dismiss(); onMode(m.key) } }
            }
            chips.addView(chip, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        col.addView(chips)
        col.addView(text(R.style.TextAppearance_Georgia_Label, secondary, modes.firstOrNull { it.key == modeKey }?.hint ?: "").apply {
            setPadding(dp(8), dp(8), dp(8), 0)
        })

        // ---- colour themes
        col.addView(text(R.style.TextAppearance_Georgia_Label, secondary, "ЦВЕТОВАЯ ТЕМА").apply { setPadding(dp(8), dp(24), dp(8), dp(8)) })
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        palettes.forEachIndexed { i, p ->
            if (i > 0) card.addView(View(ctx).apply { setBackgroundColor(divider) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(16) })
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setBackgroundResource(ripple.resourceId)
                setOnClickListener { if (p.key != paletteKey) { dialog.dismiss(); onPalette(p.key) } }
            }
            row.addView(View(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(p.swatch, p.swatch2)).apply { shape = GradientDrawable.OVAL }
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(R.style.TextAppearance_Georgia_Body, primary, p.title))
            texts.addView(text(R.style.TextAppearance_Georgia_Label, secondary, p.hint).apply { setPadding(0, dp(2), 0, 0) })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
            if (p.key == paletteKey) row.addView(text(R.style.TextAppearance_Georgia_Title, accent, "✓"))
            card.addView(row)
        }
        col.addView(card)

        col.addView(TextView(ctx).apply {
            setTextAppearance(R.style.TextAppearance_Georgia_Title)
            text = "Готово"
            gravity = Gravity.CENTER
            setTextColor(color(R.attr.gmOnAccent, Color.WHITE))
            setBackgroundResource(R.drawable.bg_btn_primary)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(24) })

        dialog.setContentView(NestedScrollView(ctx).apply { addView(col) })
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundResource(R.drawable.bg_sheet)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }
}
