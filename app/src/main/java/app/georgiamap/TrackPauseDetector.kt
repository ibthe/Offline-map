package app.georgiamap

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Detects long stationary stops without storing the GPS jitter accumulated during a pause. */
internal class TrackPauseDetector(
    private val pauseAfterMs: Long = 120_000L,
    private val stationaryRadiusM: Double = 15.0,
    private val resumeDistanceM: Double = 25.0,
    private val resumeSpeedKmh: Double = 1.5,
    private val enabled: Boolean = true
) {
    enum class Event { RECORD, PAUSED, RESUMED, SKIP }
    private data class Fix(val lat: Double, val lon: Double, val timeMs: Long, val accuracyM: Double)
    private var previous: Fix? = null
    private var stationarySinceMs = 0L
    private var stationaryAnchor: Fix? = null
    private var pauseAnchor: Fix? = null
    var isPaused: Boolean = false
        private set

    fun onFix(lat: Double, lon: Double, timeMs: Long, accuracyM: Double): Event {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
            lon !in -180.0..180.0 || timeMs <= 0L) return Event.SKIP
        val accuracy = accuracyM.takeIf { it.isFinite() && it > 0.0 } ?: 10.0
        val current = Fix(lat, lon, timeMs, accuracy)
        val prior = previous
        if (!enabled) {
            previous = current
            return Event.RECORD
        }
        if (prior == null) {
            previous = current
            return Event.RECORD
        }
        if (timeMs <= prior.timeMs) return Event.SKIP

        if (isPaused) {
            val anchor = pauseAnchor ?: prior
            val moved = distance(anchor.lat, anchor.lon, lat, lon)
            val stepDistance = distance(prior.lat, prior.lon, lat, lon)
            val dt = (timeMs - prior.timeMs) / 1000.0
            val speed = if (dt > 0.0) stepDistance / dt * 3.6 else 0.0
            previous = current
            if (moved >= maxOf(resumeDistanceM, accuracy.coerceAtMost(20.0) * 2.0) &&
                (speed >= resumeSpeedKmh || moved >= resumeDistanceM * 2.0)) {
                isPaused = false
                pauseAnchor = null
                stationaryAnchor = null
                stationarySinceMs = 0L
                return Event.RESUMED
            }
            return Event.SKIP
        }

        val anchor = stationaryAnchor
        if (anchor == null) {
            stationaryAnchor = prior
            stationarySinceMs = prior.timeMs
        } else {
            val drift = distance(anchor.lat, anchor.lon, lat, lon)
            val radius = maxOf(stationaryRadiusM, accuracy.coerceAtMost(20.0) * 1.5)
            if (drift > radius) {
                stationaryAnchor = current
                stationarySinceMs = timeMs
            } else if (timeMs - stationarySinceMs >= pauseAfterMs) {
                isPaused = true
                pauseAnchor = current
                previous = current
                return Event.PAUSED
            }
        }
        previous = current
        return Event.RECORD
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) +
            cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * 6_371_008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
