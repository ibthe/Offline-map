package app.georgiamap

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Offline road routing over the graph produced by tools/build_routing.py (no Android dependencies). */
object Profile {
    const val CAR = 0
    const val FOOT = 1
    const val BIKE = 2
}

object Maneuver {
    const val DEPART = 0
    const val STRAIGHT = 1
    const val SLIGHT_LEFT = 2
    const val LEFT = 3
    const val SHARP_LEFT = 4
    const val SLIGHT_RIGHT = 5
    const val RIGHT = 6
    const val SHARP_RIGHT = 7
    const val UTURN = 8
    const val ROUNDABOUT = 9
    const val ARRIVE = 10

    fun arrow(kind: Int) = when (kind) {
        SLIGHT_LEFT -> "↖"
        LEFT -> "←"
        SHARP_LEFT -> "↙"
        SLIGHT_RIGHT -> "↗"
        RIGHT -> "→"
        SHARP_RIGHT -> "↘"
        UTURN -> "↶"
        ROUNDABOUT -> "↻"
        ARRIVE -> "●"
        else -> "↑"
    }
}

class RouteStep(
    val kind: Int, val text: String, val street: String,
    val lat: Double, val lon: Double, val pointIndex: Int, var distToNextM: Double
)

class RouteResult(
    val points: DoubleArray, val distanceM: Double, val timeS: Double, val steps: List<RouteStep>,
    /** distance from the requested start / end point to the road the route was attached to (0 = on the road) */
    val startOffM: Double = 0.0, val endOffM: Double = 0.0
) {
    val pointCount get() = points.size / 2
    fun lat(i: Int) = points[2 * i]
    fun lon(i: Int) = points[2 * i + 1]
}

class RoadGraph(buf: ByteBuffer) {
    private val nN: Int
    private val nM: Int
    private val nodeLat: IntArray
    private val nodeLon: IntArray
    private val eA: IntArray
    private val eB: IntArray
    private val eLen: FloatArray
    private val eFlags: IntArray
    private val eName: IntArray
    private val geomOff: IntArray
    private val gLat: IntArray
    private val gLon: IntArray
    private val gridLat0: Int
    private val gridLon0: Int
    private val cell: Int
    private val rows: Int
    private val cols: Int
    private val gridOff: IntArray
    private val gridEdge: IntArray
    private val nameOff: IntArray
    private val nameBytes: ByteArray
    private val adjOff: IntArray
    private val adj: IntArray

    val nodeCount get() = nN
    val edgeCount get() = nM

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        buf.get(magic)
        require(String(magic, Charsets.US_ASCII) == "GRT1") { "bad routing file" }
        nN = buf.int
        nM = buf.int
        val nP = buf.int
        val nNames = buf.int
        val nameLen = buf.int
        gridLat0 = buf.int
        gridLon0 = buf.int
        cell = buf.int
        rows = buf.int
        cols = buf.int
        val nGrid = buf.int
        nodeLat = ints(buf, nN)
        nodeLon = ints(buf, nN)
        eA = ints(buf, nM)
        eB = ints(buf, nM)
        eLen = FloatArray(nM).also { buf.asFloatBuffer().get(it); buf.position(buf.position() + 4 * nM) }
        eFlags = ints(buf, nM)
        eName = ints(buf, nM)
        geomOff = ints(buf, nM + 1)
        gLat = ints(buf, nP)
        gLon = ints(buf, nP)
        gridOff = ints(buf, rows * cols + 1)
        gridEdge = ints(buf, nGrid)
        nameOff = ints(buf, nNames + 1)
        nameBytes = ByteArray(nameLen).also { buf.get(it) }

        val deg = IntArray(nN + 1)
        for (e in 0 until nM) { deg[eA[e] + 1]++; deg[eB[e] + 1]++ }
        for (i in 0 until nN) deg[i + 1] += deg[i]
        adjOff = deg
        adj = IntArray(2 * nM)
        val fill = adjOff.copyOf()
        for (e in 0 until nM) {
            adj[fill[eA[e]]++] = e * 2          // leaving A  = forward
            adj[fill[eB[e]]++] = e * 2 + 1      // leaving B  = backward
        }
    }

    private companion object {
        fun ints(b: ByteBuffer, n: Int): IntArray {
            val a = IntArray(n)
            b.asIntBuffer().get(a)
            b.position(b.position() + 4 * n)
            return a
        }

        const val M_LAT = 110574.0
        const val SNAP_ALTERNATIVE_TOLERANCE_M = 40.0
        fun mLon(latDeg: Double) = 111320.0 * cos(Math.toRadians(latDeg))
    }

    // ------------------------------------------------------------------ edge helpers
    private fun canGo(e: Int, dir: Int, p: Int) = (eFlags[e] shr (if (dir == 0) p else 3 + p)) and 1 == 1
    private fun cls(e: Int) = (eFlags[e] shr 14) and 15
    private fun isRound(e: Int) = (eFlags[e] shr 18) and 1 == 1
    private fun isUnpaved(e: Int) = (eFlags[e] shr 19) and 1 == 1

    private fun speedMs(e: Int, p: Int): Double {
        val kmh = when (p) {
            Profile.CAR -> ((eFlags[e] shr 6) and 255).toDouble()
            Profile.FOOT -> when (cls(e)) { 9 -> 3.0; 7, 8 -> 4.2; else -> 5.0 }
            else -> {
                val v = when (cls(e)) { 8 -> 14.0; 7 -> 11.0; 0, 1 -> 14.0; 2 -> 15.0; 9 -> 4.0; else -> 17.0 }
                if (isUnpaved(e)) v * 0.7 else v
            }
        }
        return max(1.0, kmh) / 3.6
    }

    /** Approximate speed for the straight connector between the requested point and the road graph. */
    private fun connectorSpeedMs(p: Int) = when (p) {
        Profile.CAR -> 36.0
        Profile.FOOT -> 1.5
        else -> 8.0
    }

    fun nameOf(e: Int): String {
        val i = eName[e]
        if (i <= 0) return ""
        return String(nameBytes, nameOff[i], nameOff[i + 1] - nameOff[i], Charsets.UTF_8)
    }

    private fun la(i: Int) = nodeLat[i] * 1e-6
    private fun lo(i: Int) = nodeLon[i] * 1e-6

    private fun distM(la1: Double, lo1: Double, la2: Double, lo2: Double) =
        hypot((lo2 - lo1) * mLon((la1 + la2) / 2), (la2 - la1) * M_LAT)

    private fun bearing(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dx = (lo2 - lo1) * cos(Math.toRadians((la1 + la2) / 2))
        val dy = la2 - la1
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    // ------------------------------------------------------------------ snapping
    private class Snap(val e: Int, val seg: Int, val lat: Double, val lon: Double, val off: Double,
                       val geomLen: Double, val dist: Double)

    private fun edgeGeomLen(e: Int): Double {
        var s = 0.0
        for (j in geomOff[e] until geomOff[e + 1] - 1)
            s += distM(gLat[j] * 1e-6, gLon[j] * 1e-6, gLat[j + 1] * 1e-6, gLon[j + 1] * 1e-6)
        return s
    }

    /**
     * The [k] nearest usable roads to the point, nearest first. Looks as far as ~45 km: when there is no road close
     * by, the nearest one is used (the route then starts/ends with a straight "off-road" piece to it).
     */
    private fun snapK(lat: Double, lon: Double, p: Int, k: Int): List<Snap> {
        val r0 = Math.floorDiv((lat * 1e6).toInt() - gridLat0, cell)
        val c0 = Math.floorDiv((lon * 1e6).toInt() - gridLon0, cell)
        val found = ArrayList<Snap>()
        val seen = HashSet<Int>()
        val kx = mLon(lat)
        var ring = 0
        var stopAt = Int.MAX_VALUE
        while (ring <= 45 && ring <= stopAt) {
            for (r in r0 - ring..r0 + ring) {
                if (r < 0 || r >= rows) continue
                for (c in c0 - ring..c0 + ring) {
                    if (max(abs(r - r0), abs(c - c0)) != ring) continue
                    if (c < 0 || c >= cols) continue
                    val cellIdx = r * cols + c
                    for (i in gridOff[cellIdx] until gridOff[cellIdx + 1]) {
                        val e = gridEdge[i]
                        if (!seen.add(e)) continue
                        if (!canGo(e, 0, p) && !canGo(e, 1, p)) continue
                        found.add(nearestOnEdge(e, lat, lon, kx))
                    }
                }
            }
            // enough candidates: look one more ring (a closer road can sit just across a cell border), then stop
            if (found.size >= k && stopAt == Int.MAX_VALUE) stopAt = ring + 1
            ring++
        }
        found.sortBy { it.dist }
        return if (found.size > k) found.subList(0, k).toList() else found
    }

    private fun nearestOnEdge(e: Int, lat: Double, lon: Double, kx: Double): Snap {
        var bestD = Double.MAX_VALUE
        var bSeg = 0
        var bLat = 0.0
        var bLon = 0.0
        var bOff = 0.0
        var acc = 0.0
        val g0 = geomOff[e]
        for (j in g0 until geomOff[e + 1] - 1) {
            val ay = gLat[j] * 1e-6
            val ax = gLon[j] * 1e-6
            val by = gLat[j + 1] * 1e-6
            val bx = gLon[j + 1] * 1e-6
            val vx = (bx - ax) * kx
            val vy = (by - ay) * M_LAT
            val wx = (lon - ax) * kx
            val wy = (lat - ay) * M_LAT
            val l2 = vx * vx + vy * vy
            val t = if (l2 <= 0.0) 0.0 else ((wx * vx + wy * vy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(wx - t * vx, wy - t * vy)
            val segLen = hypot(vx, vy)
            if (d < bestD) {
                bestD = d; bSeg = j - g0
                bLat = ay + (by - ay) * t; bLon = ax + (bx - ax) * t
                bOff = acc + segLen * t
            }
            acc += segLen
        }
        return Snap(e, bSeg, bLat, bLon, bOff, acc, bestD)
    }

    // ------------------------------------------------------------------ search
    private var g = DoubleArray(0)
    private var prev = IntArray(0)
    private var closed = BooleanArray(0)

    private class Heap {
        var keys = DoubleArray(1024)
        var vals = IntArray(1024)
        var size = 0
        fun push(k: Double, v: Int) {
            if (size == keys.size) { keys = keys.copyOf(size * 2); vals = vals.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val p = (i - 1) / 2
                if (keys[p] <= k) break
                keys[i] = keys[p]; vals[i] = vals[p]; i = p
            }
            keys[i] = k; vals[i] = v
        }

        fun popKey() = keys[0]
        fun popVal(): Int {
            val top = vals[0]
            size--
            if (size > 0) {
                val k = keys[size]; val v = vals[size]
                var i = 0
                while (true) {
                    var c = 2 * i + 1
                    if (c >= size) break
                    if (c + 1 < size && keys[c + 1] < keys[c]) c++
                    if (keys[c] >= k) break
                    keys[i] = keys[c]; vals[i] = vals[c]; i = c
                }
                keys[i] = k; vals[i] = v
            }
            return top
        }
    }

    /** Leg of the found route: a (partial) directed edge. */
    private class Leg(val e: Int, val dir: Int, val from: Int, val to: Int, var lenM: Double, var i0: Int = 0, var i1: Int = 0)

    @Synchronized
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, p: Int): RouteResult? {
        if (p !in Profile.CAR..Profile.BIKE) return null
        if (!fromLat.isFinite() || !fromLon.isFinite() || !toLat.isFinite() || !toLon.isFinite()) return null
        if (fromLat !in -90.0..90.0 || toLat !in -90.0..90.0 ||
            fromLon !in -180.0..180.0 || toLon !in -180.0..180.0) return null
        if (distM(fromLat, fromLon, toLat, toLon) < 0.5) return null

        val ss = snapK(fromLat, fromLon, p, 4)
        val ts = snapK(toLat, toLon, p, 4)
        if (ss.isEmpty() || ts.isEmpty()) return null

        // Keep alternatives near the closest usable road. Without this bound, the
        // router could silently jump from a disconnected road/path to a different
        // component several hundred metres away using a fictitious straight connector.
        val sLimit = ss.first().dist + SNAP_ALTERNATIVE_TOLERANCE_M
        val tLimit = ts.first().dist + SNAP_ALTERNATIVE_TOLERANCE_M
        val startCandidates = ss.filter { it.dist <= sLimit }
        val endCandidates = ts.filter { it.dist <= tLimit }

        val pairs = ArrayList<Pair<Snap, Snap>>()
        for (a in startCandidates) for (b in endCandidates) pairs.add(a to b)
        pairs.sortBy { it.first.dist + it.second.dist }

        // Evaluate every candidate pair. The closest pair of roads is not necessarily
        // connected, nor does it necessarily produce the fastest complete route.
        // RouteResult.timeS includes an estimate for the straight off-road connectors.
        var best: RouteResult? = null
        var bestCost = Double.MAX_VALUE
        for ((s, t) in pairs) {
            // Connector time alone is a lower bound; skip pairs that cannot beat the best route.
            if ((s.dist + t.dist) / connectorSpeedMs(p) >= bestCost) continue
            val candidate = search(fromLat, fromLon, toLat, toLon, p, s, t) ?: continue
            val cost = candidate.timeS
            if (cost < bestCost) {
                best = candidate
                bestCost = cost
            }
        }
        return best
    }

    private fun search(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, p: Int, s: Snap, t: Snap): RouteResult? {
        if (g.size != nN) { g = DoubleArray(nN); prev = IntArray(nN); closed = BooleanArray(nN) }
        java.util.Arrays.fill(g, Double.MAX_VALUE)
        java.util.Arrays.fill(prev, -2)
        java.util.Arrays.fill(closed, false)

        val vmax = when (p) { Profile.CAR -> 36.0; Profile.FOOT -> 1.5; else -> 8.0 }
        val tkx = mLon(toLat)
        fun h(v: Int) = hypot((lo(v) - toLon) * tkx, (la(v) - toLat) * M_LAT) / vmax

        val heap = Heap()
        val sp = speedMs(s.e, p)
        if (canGo(s.e, 0, p)) {                       // leave through B
            val v = eB[s.e]; val c = (s.geomLen - s.off) / sp
            if (c < g[v]) { g[v] = c; prev[v] = -1; heap.push(c + h(v), v) }
        }
        if (canGo(s.e, 1, p)) {                       // leave through A
            val v = eA[s.e]; val c = s.off / sp
            if (c < g[v]) { g[v] = c; prev[v] = -1; heap.push(c + h(v), v) }
        }
        val tp = speedMs(t.e, p)
        val attachA = eA[t.e]
        val attachB = eB[t.e]
        val extraA = if (canGo(t.e, 0, p)) t.off / tp else Double.MAX_VALUE            // A -> T moving forward
        val extraB = if (canGo(t.e, 1, p)) (t.geomLen - t.off) / tp else Double.MAX_VALUE // B -> T moving backward

        var best = Double.MAX_VALUE
        var bestNode = -1
        var bestViaA = true
        var direct = false
        if (s.e == t.e) {
            if (s.off <= t.off && canGo(s.e, 0, p)) { best = (t.off - s.off) / sp; direct = true }
            else if (s.off >= t.off && canGo(s.e, 1, p)) { best = (s.off - t.off) / sp; direct = true }
        }

        while (heap.size > 0) {
            val f = heap.popKey()
            val u = heap.popVal()
            if (f >= best) break
            if (closed[u]) continue
            closed[u] = true
            val gu = g[u]
            if (u == attachA && extraA < Double.MAX_VALUE && gu + extraA < best) { best = gu + extraA; bestNode = u; bestViaA = true; direct = false }
            if (u == attachB && extraB < Double.MAX_VALUE && gu + extraB < best) { best = gu + extraB; bestNode = u; bestViaA = false; direct = false }
            for (i in adjOff[u] until adjOff[u + 1]) {
                val code = adj[i]
                val e = code shr 1
                val dir = code and 1
                if (!canGo(e, dir, p)) continue
                val v = if (dir == 0) eB[e] else eA[e]
                if (closed[v]) continue
                val ng = gu + eLen[e] / speedMs(e, p)
                if (ng < g[v]) { g[v] = ng; prev[v] = code; heap.push(ng + h(v), v) }
            }
        }
        if (best == Double.MAX_VALUE) return null
        return build(fromLat, fromLon, toLat, toLon, p, s, t, direct, bestNode, bestViaA)
    }

    // ------------------------------------------------------------------ result: geometry + instructions
    private class Pts {
        var a = DoubleArray(256)
        var n = 0
        fun add(lat: Double, lon: Double) {
            if (n > 0 && abs(a[2 * n - 2] - lat) < 1e-9 && abs(a[2 * n - 1] - lon) < 1e-9) return
            if (2 * n + 2 > a.size) a = a.copyOf(a.size * 2)
            a[2 * n] = lat; a[2 * n + 1] = lon; n++
        }
    }

    private fun addGeom(pts: Pts, e: Int, fromIdx: Int, toIdx: Int) {
        val g0 = geomOff[e]
        if (fromIdx <= toIdx) for (j in fromIdx..toIdx) pts.add(gLat[g0 + j] * 1e-6, gLon[g0 + j] * 1e-6)
        else for (j in fromIdx downTo toIdx) pts.add(gLat[g0 + j] * 1e-6, gLon[g0 + j] * 1e-6)
    }

    private fun build(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, p: Int, s: Snap, t: Snap,
                      direct: Boolean, bestNode: Int, bestViaA: Boolean): RouteResult {
        val pts = Pts()
        val legs = ArrayList<Leg>()
        pts.add(fromLat, fromLon)
        pts.add(s.lat, s.lon)
        fun lastIdx(e: Int) = geomOff[e + 1] - geomOff[e] - 1

        if (direct) {
            val fwd = s.off <= t.off
            val dir = if (fwd) 0 else 1
            if (fwd) { if (s.seg + 1 <= t.seg) addGeom(pts, s.e, s.seg + 1, t.seg) }
            else if (s.seg >= t.seg + 1) addGeom(pts, s.e, s.seg, t.seg + 1)
            pts.add(t.lat, t.lon)
            legs.add(Leg(s.e, dir, 0, 0, abs(t.off - s.off), 0, pts.n - 1))
        } else {
            // edges between the start attach node and the best end node
            val codes = ArrayList<Int>()
            var u = bestNode
            while (prev[u] >= 0) {
                val c = prev[u]; codes.add(c)
                val e = c shr 1
                u = if (c and 1 == 0) eA[e] else eB[e]
            }
            codes.reverse()
            val startNode = u
            val startFwd = startNode == eB[s.e]          // we left through B (moving forward along the start edge)
            if (startFwd) {
                addGeom(pts, s.e, s.seg + 1, lastIdx(s.e))
                legs.add(Leg(s.e, 0, 0, startNode, s.geomLen - s.off, 0, pts.n - 1))
            } else {
                addGeom(pts, s.e, s.seg, 0)
                legs.add(Leg(s.e, 1, 0, startNode, s.off, 0, pts.n - 1))
            }
            for (c in codes) {
                val e = c shr 1
                val dir = c and 1
                val i0 = pts.n - 1
                if (dir == 0) addGeom(pts, e, 0, lastIdx(e)) else addGeom(pts, e, lastIdx(e), 0)
                legs.add(Leg(e, dir, if (dir == 0) eA[e] else eB[e], if (dir == 0) eB[e] else eA[e], eLen[e].toDouble(), i0, pts.n - 1))
            }
            val i0 = pts.n - 1
            if (bestViaA) {              // arrived at A, walk forward to the target
                addGeom(pts, t.e, 0, t.seg)
                pts.add(t.lat, t.lon)
                legs.add(Leg(t.e, 0, bestNode, 0, t.off, i0, pts.n - 1))
            } else {                     // arrived at B, walk backward to the target
                addGeom(pts, t.e, lastIdx(t.e), t.seg + 1)
                pts.add(t.lat, t.lon)
                legs.add(Leg(t.e, 1, bestNode, 0, t.geomLen - t.off, i0, pts.n - 1))
            }
        }
        pts.add(toLat, toLon)
        val points = pts.a.copyOf(pts.n * 2)

        var dist = 0.0
        for (i in 1 until pts.n) dist += distM(points[2 * i - 2], points[2 * i - 1], points[2 * i], points[2 * i + 1])
        var time = 0.0
        for (l in legs) time += l.lenM / speedMs(l.e, p)
        time += (s.dist + t.dist) / connectorSpeedMs(p)
        legs.last().i1 = pts.n - 1
        val real = legs.filter { it.lenM >= 2.0 }
        return RouteResult(points, dist, time, instructions(points, pts.n, if (real.isEmpty()) legs else real, p, dist), s.dist, t.dist)
    }

    private fun turnKind(turn: Double): Int {
        val a = abs(turn)
        val left = turn < 0
        return when {
            a < 25 -> Maneuver.STRAIGHT
            a < 60 -> if (left) Maneuver.SLIGHT_LEFT else Maneuver.SLIGHT_RIGHT
            a < 125 -> if (left) Maneuver.LEFT else Maneuver.RIGHT
            a < 170 -> if (left) Maneuver.SHARP_LEFT else Maneuver.SHARP_RIGHT
            else -> Maneuver.UTURN
        }
    }

    private fun turnText(kind: Int) = when (kind) {
        Maneuver.SLIGHT_LEFT -> "Держитесь левее"
        Maneuver.LEFT -> "Поверните налево"
        Maneuver.SHARP_LEFT -> "Резко налево"
        Maneuver.SLIGHT_RIGHT -> "Держитесь правее"
        Maneuver.RIGHT -> "Поверните направо"
        Maneuver.SHARP_RIGHT -> "Резко направо"
        Maneuver.UTURN -> "Развернитесь"
        else -> "Продолжайте движение"
    }

    /** Bearing of the route around point index [idx], looking [forward] or backward, at least ~12 m away. */
    private fun bearingAt(pts: DoubleArray, n: Int, idx: Int, forward: Boolean): Double {
        var j = idx
        while (true) {
            val nj = if (forward) j + 1 else j - 1
            if (nj < 0 || nj >= n) break
            j = nj
            if (distM(pts[2 * idx], pts[2 * idx + 1], pts[2 * j], pts[2 * j + 1]) >= 12.0) break
        }
        if (j == idx) return 0.0
        return if (forward) bearing(pts[2 * idx], pts[2 * idx + 1], pts[2 * j], pts[2 * j + 1])
        else bearing(pts[2 * j], pts[2 * j + 1], pts[2 * idx], pts[2 * idx + 1])
    }

    private fun alternatives(node: Int, p: Int, arrived: Int, left: Int, ringOnly: Boolean): Int {
        var c = 0
        for (i in adjOff[node] until adjOff[node + 1]) {
            val code = adj[i]
            val e = code shr 1
            if (e == arrived || e == left || !canGo(e, code and 1, p)) continue
            if (ringOnly && isRound(e)) continue
            c++
        }
        return c
    }

    private fun on(name: String) = if (name.isBlank()) "" else " на $name"

    private fun instructions(pts: DoubleArray, n: Int, legs: List<Leg>, p: Int, total: Double): List<RouteStep> {
        val steps = ArrayList<RouteStep>()
        val first = legs.first()
        val fn = nameOf(first.e)
        steps.add(RouteStep(Maneuver.DEPART, if (fn.isBlank()) "Начните движение" else "Двигайтесь по $fn", fn,
            pts[0], pts[1], 0, 0.0))
        var i = 0
        while (i < legs.size - 1) {
            val a = legs[i]
            val b = legs[i + 1]
            val node = a.to
            val ptIdx = a.i1
            if (isRound(b.e) && !isRound(a.e)) {
                var passed = 0
                var j = i + 1
                var exitName = ""
                while (j < legs.size && isRound(legs[j].e)) {
                    val last = j + 1 >= legs.size || !isRound(legs[j + 1].e)
                    if (last) { if (j + 1 < legs.size) exitName = nameOf(legs[j + 1].e); break }
                    if (alternatives(legs[j].to, p, legs[j].e, legs[j + 1].e, true) > 0) passed++
                    j++
                }
                val k = passed + 1
                steps.add(RouteStep(Maneuver.ROUNDABOUT, "На круговом движении съезжайте на $k-м съезде${on(exitName)}", exitName,
                    pts[2 * ptIdx], pts[2 * ptIdx + 1], ptIdx, 0.0))
                i = max(j, i + 1)
                continue
            }
            if (!isRound(a.e)) {
                val turn = run {
                    var d = bearingAt(pts, n, b.i0, true) - bearingAt(pts, n, a.i1, false)
                    while (d > 180) d -= 360
                    while (d <= -180) d += 360
                    d
                }
                val kind = turnKind(turn)
                val alts = alternatives(node, p, a.e, b.e, false)
                if (kind != Maneuver.STRAIGHT && (alts > 0 || kind == Maneuver.UTURN)) {
                    val nm = nameOf(b.e)
                    steps.add(RouteStep(kind, turnText(kind) + on(nm), nm, pts[2 * ptIdx], pts[2 * ptIdx + 1], ptIdx, 0.0))
                }
            }
            i++
        }
        val last = n - 1
        steps.add(RouteStep(Maneuver.ARRIVE, "Вы прибыли в пункт назначения", "", pts[2 * last], pts[2 * last + 1], last, 0.0))
        // distance from each step to the next one, along the polyline
        val cum = DoubleArray(n)
        for (k in 1 until n) cum[k] = cum[k - 1] + distM(pts[2 * k - 2], pts[2 * k - 1], pts[2 * k], pts[2 * k + 1])
        for (k in 0 until steps.size - 1) steps[k].distToNextM = max(0.0, cum[steps[k + 1].pointIndex] - cum[steps[k].pointIndex])
        return steps
    }
}
