import app.georgiamap.*
import java.io.File
import java.nio.ByteBuffer

fun show(title: String, r: RouteResult?) {
    println("== $title")
    if (r == null) { println("   NO ROUTE"); return }
    println("   dist=%.0f m  time=%.0f s  points=%d".format(r.distanceM, r.timeS, r.pointCount))
    for (s in r.steps) println("   [%d] %s %s  (%s) next=%.0f m".format(s.pointIndex, Maneuver.arrow(s.kind), s.text, s.street, s.distToNextM))
}

fun main() {
    nav()
    val g = RoadGraph(ByteBuffer.wrap(File("/tmp/rt/test.bin").readBytes()))
    println("nodes=${g.nodeCount} edges=${g.edgeCount}")
    val L0 = 41.7000; val O0 = 44.8000; val DA = 0.002; val DO = 0.0027
    fun p(r: Double, c: Double) = doubleArrayOf(L0 + r * DA, O0 + c * DO)
    fun go(name: String, a: DoubleArray, b: DoubleArray, prof: Int) { val x = g.route(a[0], a[1], b[0], b[1], prof); show(name, x) }
    go("car (0,0)->(3,3)", p(0.0,0.0), p(3.0,3.0), Profile.CAR)
    go("car (0,3)->(2,3) north allowed on oneway", p(0.0,3.0), p(2.0,3.0), Profile.CAR)
    go("car (2,3)->(0,3) must detour (oneway)", p(2.0,3.0), p(0.0,3.0), Profile.CAR)
    go("foot (0,0)->(3,3) diagonal footway", p(0.0,0.0), p(3.0,3.0), Profile.FOOT)
    go("bike (0,0)->(3,3)", p(0.0,0.0), p(3.0,3.0), Profile.BIKE)
    go("car mid-edge (0.5,0.5 off-road) -> (2.5,2.5)", p(0.5,0.5), p(2.5,2.5), Profile.CAR)
    go("car same edge forward (1,0.2)->(1,0.8)", p(1.0,0.2), p(1.0,0.8), Profile.CAR)
    go("car same edge backward (1,0.8)->(1,0.2)", p(1.0,0.8), p(1.0,0.2), Profile.CAR)
    go("car via roundabout (3,1)->NorthOut", p(3.0,1.0), doubleArrayOf(L0 + 3*DA + 0.0050 + 0.003, O0 + DO), Profile.CAR)
    go("car via roundabout (3,1)->EastOut", p(3.0,1.0), doubleArrayOf(L0 + 3*DA + 0.0050, O0 + DO + 0.003), Profile.CAR)
    go("car via roundabout (3,1)->WestOut", p(3.0,1.0), doubleArrayOf(L0 + 3*DA + 0.0050, O0 + DO - 0.003), Profile.CAR)
    go("car near island point -> (3,3)", doubleArrayOf(L0 + 0.0036, O0 + 0.0042), p(3.0,3.0), Profile.CAR)
}

fun nav() {
    val g = RoadGraph(java.nio.ByteBuffer.wrap(java.io.File("/tmp/rt/test.bin").readBytes()))
    val L0 = 41.7000; val O0 = 44.8000; val DA = 0.002; val DO = 0.0027
    val r = g.route(L0, O0, L0 + 3 * DA, O0 + 3 * DO, Profile.CAR)!!
    val s = NavSession(r, Profile.CAR)
    println("-- nav simulation, route %.0f m, %d steps".format(s.totalM, r.steps.size))
    for (i in 0 until r.pointCount step 1) {
        val q = s.update(r.lat(i), r.lon(i))
        println("   at pt %d: along=%.0f rem=%.0f off=%.0f nextStep=%d (%s) in %.0f m arrived=%s".format(i, q.alongM, q.remainingM, q.offRouteM, q.stepIndex, r.steps[q.stepIndex].text, q.distToStepM, q.arrived))
    }
    val q = s.update(L0 + 0.001, O0 + 0.0015)   // 100+ m off the route
    println("   off-route probe: off=%.0f limit=%.0f".format(q.offRouteM, s.offRouteLimitM))
}
