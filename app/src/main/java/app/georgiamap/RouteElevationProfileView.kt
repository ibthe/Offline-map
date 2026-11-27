package app.georgiamap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Small, dependency-free elevation chart used in the route preview. */
class RouteElevationProfileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 112, 150)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33808080
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF777777.toInt()
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val path = Path()
    private var samples: List<RouteAnalysis.Sample> = emptyList()
    private var minElevation = 0.0
    private var maxElevation = 1.0
    private var totalDistance = 1.0

    fun setProfile(result: RouteAnalysis.Result) {
        samples = result.samples.filter { it.elevationM?.isFinite() == true }
        minElevation = result.minElevationM ?: 0.0
        maxElevation = result.maxElevationM ?: (minElevation + 1.0)
        if (maxElevation - minElevation < 1.0) {
            minElevation -= 0.5
            maxElevation += 0.5
        }
        totalDistance = result.distanceM.coerceAtLeast(1.0)
        contentDescription = "Профиль высот маршрута: от ${minElevation.toInt()} до ${maxElevation.toInt()} метров"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val left = 42f * d
        val right = width - 12f * d
        val top = 14f * d
        val bottom = height - 30f * d
        if (right <= left || bottom <= top || samples.size < 2) return

        for (i in 0..2) {
            val y = top + (bottom - top) * i / 2f
            canvas.drawLine(left, y, right, y, gridPaint)
            val value = (maxElevation - (maxElevation - minElevation) * i / 2.0).toInt()
            canvas.drawText(value.toString(), 2f * d, y + 4f * d, textPaint)
        }
        canvas.drawText("0 км", left, height - 7f * d, textPaint)
        val km = totalDistance / 1000.0
        canvas.drawText(String.format(java.util.Locale.getDefault(), "%.1f км", km),
            max(left, right - 38f * d), height - 7f * d, textPaint)

        path.reset()
        var drawing = false
        for (sample in samples) {
            val elevation = sample.elevationM
            if (elevation == null) {
                drawing = false
                continue
            }
            val x = left + (right - left) * (sample.distanceM / totalDistance).toFloat()
            val y = bottom - (bottom - top) *
                ((elevation - minElevation) / (maxElevation - minElevation)).toFloat()
            if (!drawing) {
                path.moveTo(x, y)
                drawing = true
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, linePaint)
    }
}
