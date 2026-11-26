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

    init {
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(
            (route.lon(i) - route.lon(i - 1)) * 111320.0 * cos(Math.toRadians((route.lat(i) + route.lat(i - 1)) / 2)),
            (route.lat(i) - route.lat(i - 1)) * 110574.0
        )
    }

    val totalM get() = cum[n - 1]
    private val avgSpeed = max(0.5, totalM / max(1.0, route.timeS))

    val arriveRadiusM get() = if (profile == Profile.CAR) 30.0 else 15.0
    val offRouteLimitM get() = if (profile == Profile.CAR) 60.0 else 35.0

    fun update(lat: Double, lon: Double): NavProgress {
        // A degenerate route can contain a single coordinate. There is no segment
        // to project onto, so treat that coordinate as the destination.
        if (n == 1) {
            val d = hypot(
                (route.lon(0) - lon) * 111320.0 * cos(Math.toRadians((route.lat(0) + lat) / 2)),
                (route.lat(0) - lat) * 110574.0
            )
            return NavProgress(0.0, 0.0, d, 0, 0.0, 0.0, d <= arriveRadiusM)
        }
        var bestD = Double.MAX_VALUE
        var bestSeg = seg
        var bestT = 0.0
        val kx = 111320.0 * cos(Math.toRadians(lat))
        fun scan(from: Int, to: Int) {
            for (i in from until min(to, n - 1)) {
                val ax = (route.lon(i) - lon) * kx
                val ay = (route.lat(i) - lat) * 110574.0
                val bx = (route.lon(i + 1) - lon) * kx
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
        val along = cum[seg] + bestT * (cum[seg + 1] - cum[seg])
        val remaining = max(0.0, totalM - along)
        var k = route.steps.size - 1
        for (i in 1 until route.steps.size) {
            if (cum[route.steps[i].pointIndex] > along + 3.0) { k = i; break }
        }
        val toStep = max(0.0, cum[route.steps[k].pointIndex] - along)
        val arrived = remaining <= arriveRadiusM
        return NavProgress(along, remaining, bestD, k, toStep, remaining / avgSpeed, arrived)
    }
}
