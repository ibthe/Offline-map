package app.georgiamap

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Display-only polyline simplification. Original recorded points remain untouched in SQLite/GPX. */
object TrackGeometry {
    data class Point(val lat: Double, val lon: Double)

    fun simplify(points: List<Point>, toleranceMeters: Double): List<Point> {
        if (points.size <= 2 || !toleranceMeters.isFinite() || toleranceMeters <= 0.0) return points
        val meanLat = points.sumOf { it.lat } / points.size
        val xScale = 111_320.0 * cos(Math.toRadians(meanLat))
        val yScale = 110_574.0
        val xs = DoubleArray(points.size) { points[it].lon * xScale }
        val ys = DoubleArray(points.size) { points[it].lat * yScale }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        val toleranceSq = toleranceMeters * toleranceMeters

        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            if (last - first < 2) continue
            val ax = xs[first]
            val ay = ys[first]
            val dx = xs[last] - ax
            val dy = ys[last] - ay
            val lengthSq = dx * dx + dy * dy
            var maxDistanceSq = -1.0
            var farthest = -1
            for (i in first + 1 until last) {
                val t = if (lengthSq > 0.0) {
                    (((xs[i] - ax) * dx + (ys[i] - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
                } else 0.0
                val px = ax + t * dx
                val py = ay + t * dy
                val ex = xs[i] - px
                val ey = ys[i] - py
                val distanceSq = ex * ex + ey * ey
                if (distanceSq > maxDistanceSq) {
                    maxDistanceSq = distanceSq
                    farthest = i
                }
            }
            if (farthest >= 0 && maxDistanceSq > toleranceSq) {
                keep[farthest] = true
                stack.addLast(first to farthest)
                stack.addLast(farthest to last)
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }
}
