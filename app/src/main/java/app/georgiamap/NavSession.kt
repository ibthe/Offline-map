package app.georgiamap

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class NavProgress(
    val alongM: Double, val remainingM: Double, val offRouteM: Double,
    val stepIndex: Int, val distToStepM: Double, val remainingS: Double, val arrived: Boolean
)

/** Follows a [RouteResult]: where am I on the route, what is the next manoeuvre, did I leave the route. No Android code. */
class NavSession(val route: RouteResult, val profile: Int) {
    private val n = route.pointCount
    val cum = DoubleArray(n)
    private var seg = 0
    private var furthestAlongM = 0.0

    init {
        require(n > 0) { "A navigation route must contain at least one point" }
        require(route.steps.isNotEmpty()) { "A navigation route must contain at least one step" }
        require(route.steps.all { it.pointIndex in 0 until n }) { "Route step points must reference route geometry" }
        for (i in 1 until n) cum[i] = cum[i - 1] + RouteAnalysis.distance(
            RouteAnalysis.Point(route.lat(i - 1), route.lon(i - 1)),
            RouteAnalysis.Point(route.lat(i), route.lon(i))
        )
    }

    val totalM get() = cum[n - 1]
    private val avgSpeed = max(0.5, totalM / max(1.0, route.timeS))

    val arriveRadiusM get() = if (profile == Profile.CAR) 30.0 else 15.0
    val offRouteLimitM get() = if (profile == Profile.CAR) 60.0 else 35.0

    private fun longitudeDelta(a: Double, b: Double): Double = ((a - b + 540.0) % 360.0) - 180.0

    fun update(lat: Double, lon: Double): NavProgress {
        // A degenerate route can contain a single coordinate. There is no segment
        // to project onto, so treat that coordinate as the destination.
        if (n == 1) {
            val d = hypot(
                longitudeDelta(route.lon(0), lon) * 111320.0 * cos(Math.toRadians((route.lat(0) + lat) / 2)),
                (route.lat(0) - lat) * 110574.0
            )
            return NavProgress(0.0, d, d, 0, d, d / avgSpeed, d <= arriveRadiusM)
        }
        var bestD = Double.MAX_VALUE
        var bestSeg = seg
        var bestT = 0.0
        val kx = 111320.0 * cos(Math.toRadians(lat))
        fun scan(from: Int, to: Int) {
            for (i in from until min(to, n - 1)) {
                val ax = longitudeDelta(route.lon(i), lon) * kx
                val ay = (route.lat(i) - lat) * 110574.0
                val bx = longitudeDelta(route.lon(i + 1), lon) * kx
                val by = (route.lat(i + 1) - lat) * 110574.0
                val vx = bx - ax
                val vy = by - ay
                val l2 = vx * vx + vy * vy
                val t = if (l2 <= 0.0) 0.0 else ((-ax * vx - ay * vy) / l2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * vx, ay + t * vy)
                if (d < bestD) { bestD = d; bestSeg = i; bestT = t }
            }
        }
        scan(max(0, seg - 3), min(n - 1, seg + 80))      // normal case: we are near where we were
        if (bestD > 120.0) scan(0, n - 1)                // lost: look at the whole route
        seg = bestSeg
        val projectedAlong = cum[seg] + bestT * (cum[seg + 1] - cum[seg])

        // Route progress must not run backwards when GPS snaps to an earlier segment
        // (common on loops, switchbacks and when the user retraces a short section).
        // A meaningful backwards displacement is treated as off-route so guidance can
        // recalculate instead of silently rewinding the next manoeuvre.
        val trustedProjectionM = max(60.0, offRouteLimitM * 2.0)
        val backtrackM = if (bestD <= trustedProjectionM) {
            max(0.0, furthestAlongM - projectedAlong - max(20.0, offRouteLimitM))
        } else 0.0
        if (bestD <= trustedProjectionM) furthestAlongM = max(furthestAlongM, projectedAlong)
        val along = if (bestD <= trustedProjectionM) max(projectedAlong, furthestAlongM) else furthestAlongM
        val offRoute = max(bestD, backtrackM)
        val remaining = max(0.0, totalM - along)
        var k = route.steps.size - 1
        for (i in 1 until route.steps.size) {
            if (cum[route.steps[i].pointIndex] > along + 3.0) { k = i; break }
        }
        var toStep = max(0.0, cum[route.steps[k].pointIndex] - along)
        val destinationDistance = hypot(
            longitudeDelta(route.lon(n - 1), lon) * kx,
            (route.lat(n - 1) - lat) * 110574.0
        )
        val arrived = destinationDistance <= arriveRadiusM
        val remainingForUi = when {
            arrived -> 0.0
            remaining <= arriveRadiusM -> destinationDistance
            else -> remaining
        }
        if (!arrived && remaining <= arriveRadiusM) toStep = destinationDistance
        return NavProgress(along, remainingForUi, offRoute, k, toStep, remainingForUi / avgSpeed, arrived)
    }
}
