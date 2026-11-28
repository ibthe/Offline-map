package app.georgiamap

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Terrain-only analysis of a planned route.
 *
 * Elevation values are sampled from the offline DEM. Missing samples are kept missing:
 * the analyzer never fabricates zero elevations or bridges long gaps in the DEM.
 */
object RouteAnalysis {
    data class Point(val lat: Double, val lon: Double)
    data class Sample(val distanceM: Double, val elevationM: Double?)
    data class SteepSection(
        val fromM: Double,
        val toM: Double,
        val averageGradePct: Double,
        val uphill: Boolean
    )
    enum class Difficulty { EASY, MODERATE, HARD, UNKNOWN }
    data class Result(
        val distanceM: Double,
        val minElevationM: Double?,
        val maxElevationM: Double?,
        val ascentM: Double,
        val descentM: Double,
        val maxUphillGradePct: Double?,
        val maxDownhillGradePct: Double?,
        val coveragePct: Int,
        val samples: List<Sample>,
        val steepSections: List<SteepSection>,
        val difficulty: Difficulty
    ) {
        val hasElevation: Boolean get() = samples.count { it.elevationM != null } >= 2
    }

    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val MIN_ELEVATION_CHANGE_M = 3.0
    private const val STEEP_GRADE_PCT = 12.0

    /** Samples the route at a bounded interval, including both endpoints. */
    fun sample(
        points: List<Point>,
        spacingM: Double = 100.0,
        maxSamples: Int = 240,
        elevationAt: (Double, Double) -> Double?
    ): List<Sample> {
        require(spacingM.isFinite() && spacingM > 0.0)
        require(maxSamples >= 2)
        if (points.isEmpty()) return emptyList()
        require(points.all { it.lat.isFinite() && it.lon.isFinite() &&
            it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })

        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + distance(points[i - 1], points[i])
        }
        val total = cumulative.last()
        if (points.size == 1 || total < 0.5) {
            val p = points.first()
            return listOf(Sample(0.0, elevationAt(p.lat, p.lon)?.takeIf { it.isFinite() }))
        }

        val interval = max(spacingM, total / (maxSamples - 1))
        val targets = ArrayList<Double>()
        var d = 0.0
        while (d < total && targets.size < maxSamples - 1) {
            targets.add(d)
            d += interval
        }
        if (targets.isEmpty() || total - targets.last() > 0.5) targets.add(total)
        else targets[targets.lastIndex] = total

        return targets.map { target ->
            // Lower-bound search avoids rescanning the whole route for each elevation sample.
            var low = 1
            var high = cumulative.lastIndex
            while (low < high) {
                val mid = (low + high) ushr 1
                if (cumulative[mid] < target) low = mid + 1 else high = mid
            }
            val idx = low
            val segment = cumulative[idx] - cumulative[idx - 1]
            val t = if (segment > 0.0) ((target - cumulative[idx - 1]) / segment).coerceIn(0.0, 1.0) else 0.0
            val a = points[idx - 1]
            val b = points[idx]
            val lat = a.lat + (b.lat - a.lat) * t
            // Interpolate across the short arc when a route crosses the ±180° meridian.
            val lonDelta = ((b.lon - a.lon + 540.0) % 360.0) - 180.0
            val lon = ((a.lon + lonDelta * t + 540.0) % 360.0) - 180.0
            Sample(target, elevationAt(lat, lon)?.takeIf { it.isFinite() })
        }
    }

    fun calculate(samples: List<Sample>, distanceM: Double = samples.lastOrNull()?.distanceM ?: 0.0): Result {
        require(distanceM.isFinite() && distanceM >= 0.0)
        require(samples.all { it.distanceM.isFinite() && it.distanceM >= 0.0 })
        require(samples.zipWithNext().all { (a, b) ->
            a.distanceM.isFinite() && b.distanceM.isFinite() && b.distanceM >= a.distanceM
        })
        val valid = samples.mapNotNull { s -> s.elevationM?.takeIf { it.isFinite() } }
        val coverage = if (samples.isEmpty()) 0 else (valid.size * 100.0 / samples.size).roundToInt()
        if (valid.size < 2) {
            return Result(distanceM, valid.minOrNull(), valid.maxOrNull(), 0.0, 0.0,
                null, null, coverage, samples.toList(), emptyList(), Difficulty.UNKNOWN)
        }

        // Route samples are already spaced (typically 50–100 m), which reduces pixel noise
        // while retaining short steep sections that a broad median filter could erase.
        val profileSamples = samples.map { s ->
            Sample(s.distanceM, s.elevationM?.takeIf { it.isFinite() })
        }

        var ascent = 0.0
        var descent = 0.0
        var maxUp = 0.0
        var maxDown = 0.0
        val grades = ArrayList<Triple<Double, Double, Double>>() // from, to, signed grade
        for (i in 1 until profileSamples.size) {
            val a = profileSamples[i - 1]
            val b = profileSamples[i]
            val horizontal = b.distanceM - a.distanceM
            val h0 = a.elevationM
            val h1 = b.elevationM
            if (horizontal <= 0.5 || h0 == null || h1 == null) {
                grades.add(Triple(a.distanceM, b.distanceM, Double.NaN))
                continue
            }
            val delta = h1 - h0
            if (delta >= MIN_ELEVATION_CHANGE_M) ascent += delta
            if (delta <= -MIN_ELEVATION_CHANGE_M) descent -= delta
            val grade = delta / horizontal * 100.0
            grades.add(Triple(a.distanceM, b.distanceM, grade))
            if (grade > maxUp) maxUp = grade
            if (grade < 0.0 && -grade > maxDown) maxDown = -grade
        }

        val steep = ArrayList<SteepSection>()
        var start = -1.0
        var end = -1.0
        var gradeSum = 0.0
        var lengthSum = 0.0
        var uphill: Boolean? = null
        fun flush() {
            if (start >= 0 && lengthSum > 0.0 && uphill != null) {
                steep.add(SteepSection(start, end, gradeSum / lengthSum, uphill!!))
            }
            start = -1.0
            end = -1.0
            gradeSum = 0.0
            lengthSum = 0.0
            uphill = null
        }
        for ((from, to, grade) in grades) {
            val isSteep = grade.isFinite() && abs(grade) >= STEEP_GRADE_PCT
            val direction = grade > 0.0
            if (!isSteep || (uphill != null && uphill != direction)) {
                flush()
            }
            if (isSteep) {
                if (start < 0.0) start = from
                end = to
                val length = to - from
                gradeSum += abs(grade) * length
                lengthSum += length
                uphill = direction
            }
        }
        flush()

        val ascentPerKm = if (distanceM > 0.0) ascent / (distanceM / 1000.0) else 0.0
        val difficulty = when {
            coverage < 80 -> Difficulty.UNKNOWN
            ascentPerKm >= 100.0 || maxUp >= 25.0 || maxDown >= 30.0 -> Difficulty.HARD
            ascentPerKm >= 50.0 || maxUp >= 15.0 || maxDown >= 20.0 -> Difficulty.MODERATE
            else -> Difficulty.EASY
        }
        return Result(distanceM, valid.minOrNull(), valid.maxOrNull(), ascent, descent,
            maxUp.takeIf { it > 0.0 }, maxDown.takeIf { it > 0.0 }, coverage,
            profileSamples, steep, difficulty)
    }

    fun distance(a: Point, b: Point): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
