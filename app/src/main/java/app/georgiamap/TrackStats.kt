package app.georgiamap

import kotlin.math.cos
import kotlin.math.hypot

/** Statistics computed per GPX segment; gaps between segments are never counted as distance or moving time. */
object TrackStats {
    data class Sample(val lat: Double, val lon: Double, val elevationM: Double, val timestampMs: Long)

    data class Result(
        val distanceM: Double,
        val ascentM: Double,
        val descentM: Double,
        val elapsedMs: Long,
        val movingMs: Long,
        val averageSpeedKmh: Double,
        val maxSpeedKmh: Double,
        val pointCount: Int,
        val elevationSamples: Int
    )

    fun calculate(segments: List<List<Sample>>): Result {
        var distance = 0.0
        var ascent = 0.0
        var descent = 0.0
        var moving = 0L
        var maxSpeed = 0.0
        var minTime = Long.MAX_VALUE
        var maxTime = 0L
        var elevationSamples = 0
        var pointCount = 0

        for (segment in segments) {
            pointCount += segment.size
            for (point in segment) {
                if (point.timestampMs > 0L) {
                    minTime = minOf(minTime, point.timestampMs)
                    maxTime = maxOf(maxTime, point.timestampMs)
                }
                if (point.elevationM.isFinite()) elevationSamples++
            }
            for (i in 1 until segment.size) {
                val a = segment[i - 1]
                val b = segment[i]
                val dx = (b.lon - a.lon) * 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2.0))
                val dy = (b.lat - a.lat) * 110_574.0
                val d = hypot(dx, dy)
                distance += d
                if (a.elevationM.isFinite() && b.elevationM.isFinite()) {
                    val delta = b.elevationM - a.elevationM
                    if (delta >= 3.0) ascent += delta
                    else if (delta <= -3.0) descent -= delta
                }
                val dt = b.timestampMs - a.timestampMs
                if (a.timestampMs > 0L && b.timestampMs > a.timestampMs && dt <= 5 * 60 * 1000L) {
                    moving += dt
                    val speed = d / dt * 3_600_000.0
                    if (speed.isFinite() && speed <= 250.0) maxSpeed = maxOf(maxSpeed, speed)
                }
            }
        }
        val elapsed = if (minTime != Long.MAX_VALUE && maxTime >= minTime) maxTime - minTime else 0L
        val average = if (moving > 0L) distance / moving * 3_600_000.0 else 0.0
        return Result(distance, ascent, descent, elapsed, moving, average, maxSpeed, pointCount, elevationSamples)
    }
}
