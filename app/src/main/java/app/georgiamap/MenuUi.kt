package app.georgiamap

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.roundToInt

/** Quick action tile at the top of the menu. */
class MenuTile(val icon: Int, val label: String, val active: Boolean, val onClick: () -> Unit)

sealed class MenuRow {
    /** A row that opens something; [value] is the current setting shown on the right. */
    class Link(val title: String, val value: String? = null, val onClick: () -> Unit) : MenuRow()

    /** A row with a switch; the sheet stays open when it is toggled. */
    class Toggle(val title: String, val subtitle: String? = null, val checked: Boolean, val enabled: Boolean = true,
                 val onChange: (Boolean) -> Unit) : MenuRow()
}

class MenuSection(val title: String, val rows: List<MenuRow>)

/** Builds the main menu as a bottom sheet: quick tiles, then grouped cards. All spacing follows the 4 dp grid. */
object MenuUi {
    fun show(activity: AppCompatActivity, title: String, tiles: List<MenuTile>, sections: List<MenuSection>) {
        val dialog = BottomSheetDialog(activity)
        val ctx: Context = dialog.context
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()
        fun color(attr: Int, fallback: Int) = MaterialColors.getColor(ctx, attr, fallback)
        val primary = color(R.attr.gmTextPrimary, Color.BLACK)
        val secondary = color(R.attr.gmTextSecondary, Color.GRAY)
        val accent = color(R.attr.gmAccent, Color.BLUE)
        val divider = color(R.attr.gmDivider, Color.LTGRAY)
        val iconTint = color(R.attr.gmIconTint, Color.BLACK)
        val onContainer = color(R.attr.gmOnAccentContainer, Color.BLACK)

        fun text(style: Int, c: Int, s: String) = TextView(ctx).apply {
            setTextAppearance(style); setTextColor(c); text = s
        }

        val ripple = TypedValue().also { ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        col.addView(text(R.style.TextAppearance_Georgia_Headline, primary, title).apply {
            setPadding(dp(8), dp(4), dp(8), dp(16))
        })

        // ---- quick tiles: 3 per row, equal width, 8 dp gaps
        val tileGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tiles.chunked(3).forEachIndexed { rowIdx, chunk ->
            val tileRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val centeredPair = chunk.size == 2
            val rowItems: List<MenuTile?> = if (centeredPair) listOf(null, chunk[0], chunk[1], null)
                else listOf(chunk.getOrNull(0), chunk.getOrNull(1), chunk.getOrNull(2))
            rowItems.forEachIndexed { i, t ->
                val weight = if (centeredPair && (i == 0 || i == 3)) 0.5f else 1f
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                    if (i > 0 && !(centeredPair && i == 3)) marginStart = dp(8)
                }
                if (t == null) { tileRow.addView(View(ctx), lp); return@forEachIndexed }
                val tile = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                    setBackgroundResource(if (t.active) R.drawable.bg_tile_active else R.drawable.bg_tile)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { dialog.dismiss(); t.onClick() }
                }
                tile.addView(ImageView(ctx).apply {
                    setImageResource(t.icon)
                    setColorFilter(if (t.active) onContainer else accent)
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                tile.addView(text(R.style.TextAppearance_Georgia_Label, if (t.active) onContainer else primary, t.label).apply {
                    gravity = Gravity.CENTER; maxLines = 1
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                tileRow.addView(tile, lp)
            }
            tileGrid.addView(tileRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (rowIdx > 0) topMargin = dp(8)
            })
        }
        col.addView(tileGrid)

        // ---- grouped cards
        for (sec in sections) {
            col.addView(text(R.style.TextAppearance_Georgia_Label, secondary, sec.title.uppercase()).apply {
                setPadding(dp(8), dp(24), dp(8), dp(8))
            })
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            sec.rows.forEachIndexed { i, r ->
                if (i > 0) card.addView(View(ctx).apply { setBackgroundColor(divider) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(16) })
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    setBackgroundResource(ripple.resourceId)
                }
                val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                when (r) {
                    is MenuRow.Link -> {
                        texts.addView(text(R.style.TextAppearance_Georgia_Body, primary, r.title))
                        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        if (!r.value.isNullOrEmpty()) row.addView(text(R.style.TextAppearance_Georgia_BodySmall, secondary, r.value).apply {
                            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(150)
                        })
                        row.addView(text(R.style.TextAppearance_Georgia_Title, secondary, "›").apply {
                            setPadding(dp(12), 0, 0, 0)
                        })
                        row.setOnClickListener { dialog.dismiss(); r.onClick() }
                    }
                    is MenuRow.Toggle -> {
                        texts.addView(text(R.style.TextAppearance_Georgia_Body, if (r.enabled) primary else secondary, r.title))
                        if (!r.subtitle.isNullOrEmpty()) texts.addView(text(R.style.TextAppearance_Georgia_Label, secondary, r.subtitle).apply {
                            setPadding(0, dp(2), 0, 0)
                        })
                        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        val sw = MaterialSwitch(ctx).apply { isChecked = r.checked; isEnabled = r.enabled }
                        sw.setOnCheckedChangeListener { _, on -> r.onChange(on) }
                        row.addView(sw)
                        if (r.enabled) row.setOnClickListener { sw.toggle() }
                    }
                }
                card.addView(row)
            }
            col.addView(card)
        }

        val scroll = NestedScrollView(ctx).apply { addView(col) }
        dialog.setContentView(scroll)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundResource(R.drawable.bg_sheet)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }
}
