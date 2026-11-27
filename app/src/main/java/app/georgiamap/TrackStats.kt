package app.georgiamap

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Statistics computed per GPX segment; gaps between segments are never counted as distance or moving time. */
object TrackStats {
    data class Sample(val lat: Double, val lon: Double, val elevationM: Double, val timestampMs: Long)

    data class Result(
        val distanceM: Double,
        val ascentM: Double,
        val descentM: Double,
        val elapsedMs: Long,
        val movingMs: Long,
        val stoppedMs: Long,
        val averageSpeedKmh: Double,
        val maxSpeedKmh: Double,
        val pointCount: Int,
        val elevationSamples: Int
    )

    private const val MAX_PLAUSIBLE_SPEED_KMH = 250.0
    private const val MIN_MOVING_SPEED_KMH = 1.0
    private const val MAX_MOVING_INTERVAL_MS = 5 * 60 * 1000L

    fun calculate(segments: List<List<Sample>>): Result {
        var distance = 0.0
        var ascent = 0.0
        var descent = 0.0
        var moving = 0L
        var stopped = 0L
        var movingDistance = 0.0
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
                val d = distanceMeters(a.lat, a.lon, b.lat, b.lon)
                if (!d.isFinite()) continue
                val dt = b.timestampMs - a.timestampMs
                val hasShortTimedInterval = a.timestampMs > 0L &&
                    b.timestampMs > a.timestampMs && dt <= MAX_MOVING_INTERVAL_MS
                val speed = if (hasShortTimedInterval) d / dt * 3_600.0 else null

                // A timestamped jump above plausible travel speed is a GPS/GPX outlier.
                // Exclude it from distance and elevation too, not just from speed statistics.
                if (speed != null && (!speed.isFinite() || speed > MAX_PLAUSIBLE_SPEED_KMH)) continue

                distance += d
                if (a.elevationM.isFinite() && b.elevationM.isFinite()) {
                    val delta = b.elevationM - a.elevationM
                    if (delta >= 3.0) ascent += delta
                    else if (delta <= -3.0) descent -= delta
                }
                if (speed != null) {
                    maxSpeed = maxOf(maxSpeed, speed)
                    if (speed >= MIN_MOVING_SPEED_KMH) {
                        moving += dt
                        movingDistance += d
                    } else {
                        stopped += dt
                    }
                }
            }
        }
        val elapsed = if (minTime != Long.MAX_VALUE && maxTime >= minTime) maxTime - minTime else 0L
        val average = if (moving > 0L) movingDistance / moving * 3_600.0 else 0.0
        return Result(distance, ascent, descent, elapsed, moving, stopped, average, maxSpeed, pointCount, elevationSamples)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite() ||
            lat1 !in -90.0..90.0 || lat2 !in -90.0..90.0 ||
            lon1 !in -180.0..180.0 || lon2 !in -180.0..180.0) return Double.NaN
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val rawLon = lon2 - lon1
        val dl = Math.toRadians(((rawLon + 540.0) % 360.0) - 180.0)
        val h = sin(dp / 2) * sin(dp / 2) +
            cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * 6_371_008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
