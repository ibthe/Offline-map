package app.georgiamap

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class RouterRegressionTest {
    private fun graph(): RoadGraph {
        val path = System.getProperty("router.test.graph")
        assumeNotNull("Set -ProuterTestGraph to run graph-backed routing tests", path)
        return RoadGraph(ByteBuffer.wrap(File(path!!).readBytes()))
    }

    private val lat0 = 41.7000
    private val lon0 = 44.8000
    private val dLat = 0.002
    private val dLon = 0.0027

    private fun point(row: Double, col: Double) =
        (lat0 + row * dLat) to (lon0 + col * dLon)

    @Test
    fun carRouteExistsAndOneWayForcesLongerReverseDetour() {
        val g = graph()
        val (aLat, aLon) = point(0.0, 3.0)
        val (bLat, bLon) = point(2.0, 3.0)
        val northbound = g.route(aLat, aLon, bLat, bLon, Profile.CAR)
        val southbound = g.route(bLat, bLon, aLat, aLon, Profile.CAR)

        assertNotNull(northbound)
        assertNotNull(southbound)
        assertTrue(southbound!!.distanceM > northbound!!.distanceM * 1.5)
    }

    @Test
    fun footProfileCanUseFootwayShortcut() {
        val g = graph()
        val (aLat, aLon) = point(0.0, 0.0)
        val (bLat, bLon) = point(3.0, 3.0)
        val car = g.route(aLat, aLon, bLat, bLon, Profile.CAR)
        val foot = g.route(aLat, aLon, bLat, bLon, Profile.FOOT)

        assertNotNull(car)
        assertNotNull(foot)
        assertTrue(foot!!.distanceM < car!!.distanceM)
    }

    @Test
    fun offRoadConnectorsAreIncludedInDistanceAndEta() {
        val g = graph()
        val (aLat, aLon) = point(0.5, 0.5)
        val (bLat, bLon) = point(2.5, 2.5)
        val route = g.route(aLat, aLon, bLat, bLon, Profile.CAR)

        assertNotNull(route)
        assertTrue(route!!.startOffM > 0.0)
        assertTrue(route.endOffM > 0.0)
        assertTrue(route.distanceM >= route.startOffM + route.endOffM)
        assertTrue(route.timeS >= (route.startOffM + route.endOffM) / 36.0)
    }

    @Test
    fun smallNamedDisconnectedRoadComponentIsPreserved() {
        val g = graph()
        val fromLat = lat0 + 0.0035
        val fromLon = lon0 + 0.0040
        val toLat = lat0 + 0.0039
        val toLon = lon0 + 0.0044
        val route = g.route(fromLat, fromLon, toLat, toLon, Profile.CAR)

        assertNotNull("short named island road should remain routable", route)
        assertTrue(route!!.distanceM < 200.0)
    }

    @Test
    fun invalidAndUnreachableRequestsReturnNoRoute() {
        val g = graph()
        assertNull(g.route(0.0, 0.0, 41.7, 44.8, Profile.CAR))
        assertNull(g.route(91.0, 44.8, 41.7, 44.8, Profile.CAR))
        assertNull(g.route(Double.NaN, 44.8, 41.7, 44.8, Profile.CAR))
        assertNull(g.route(41.7, 181.0, 41.7, 44.8, Profile.CAR))
        assertNull(g.route(41.7, 44.8, 41.7, 44.8, 99))
        assertNull(g.route(41.7, 44.8, 41.7, 44.8, Profile.CAR))

        // The small named island is routable internally but must not connect to the main grid.
        val (mainLat, mainLon) = point(1.0, 1.0)
        assertNull(g.route(41.7035, 44.8040, mainLat, mainLon, Profile.CAR))
    }

    @Test
    fun sameRoadSegmentSupportsBothTravelDirections() {
        val g = graph()
        val (aLat, aLon) = point(1.0, 0.2)
        val (bLat, bLon) = point(1.0, 0.8)

        assertNotNull(g.route(aLat, aLon, bLat, bLon, Profile.CAR))
        assertNotNull(g.route(bLat, bLon, aLat, aLon, Profile.CAR))
    }

    @Test
    fun hikingProfileCanUseFootAccessibleTrails() {
        val g = graph()
        val (aLat, aLon) = point(0.0, 0.0)
        val (bLat, bLon) = point(3.0, 3.0)
        val hike = g.route(aLat, aLon, bLat, bLon, Profile.HIKE)
        assertNotNull(hike)
        assertTrue(hike!!.timeS > 0.0)
    }

    @Test
    fun bikeProfileCanUseTheStreetNetwork() {
        val g = graph()
        val (aLat, aLon) = point(0.0, 0.0)
        val (bLat, bLon) = point(3.0, 3.0)

        assertNotNull(g.route(aLat, aLon, bLat, bLon, Profile.BIKE))
    }

    @Test
    fun shortestAndFastestPreferencesReturnValidRoutes() {
        val g = graph()
        val (aLat, aLon) = point(0.0, 0.0)
        val (bLat, bLon) = point(3.0, 3.0)
        val fastest = g.route(aLat, aLon, bLat, bLon, Profile.CAR, RoutePreference.FASTEST)
        val shortest = g.route(aLat, aLon, bLat, bLon, Profile.CAR, RoutePreference.SHORTEST)

        assertNotNull(fastest)
        assertNotNull(shortest)
        assertTrue(shortest!!.distanceM <= fastest!!.distanceM + 1.0)
        assertTrue(fastest.timeS <= shortest.timeS + 1.0)
        assertNull(g.route(aLat, aLon, bLat, bLon, Profile.CAR, 99))
    }

    @Test
    fun avoidancePreferencesChoosePavedAlternativeToRoughHikingShortcut() {
        val g = graph()
        val (aLat, aLon) = point(0.0, 0.0)
        val (bLat, bLon) = point(3.0, 3.0)
        val normal = g.route(aLat, aLon, bLat, bLon, Profile.FOOT)
        val avoidUnpaved = g.route(aLat, aLon, bLat, bLon, Profile.FOOT,
            RoutePreference.FASTEST, RouteAvoidance.UNPAVED)
        val avoidDifficult = g.route(aLat, aLon, bLat, bLon, Profile.FOOT,
            RoutePreference.FASTEST, RouteAvoidance.DIFFICULT_TRAILS)

        assertNotNull(normal)
        assertNotNull(avoidUnpaved)
        assertNotNull(avoidDifficult)
        assertTrue(avoidUnpaved!!.distanceM > normal!!.distanceM * 1.2)
        assertTrue(avoidDifficult!!.distanceM > normal.distanceM * 1.2)
        assertTrue(avoidUnpaved.warnings.isEmpty())
        assertTrue(avoidDifficult.warnings.isEmpty())
        assertNull(g.route(aLat, aLon, bLat, bLon, Profile.FOOT,
            RoutePreference.FASTEST, RouteAvoidance.ALL + 1))
    }

    @Test
    fun roundaboutInstructionsCountTheActualExit() {
        val g = graph()
        val cx = lon0 + dLon
        val cy = lat0 + 3.0 * dLat + 0.005
        val start = cy to (cx - 0.003)
        val destinations = listOf(
            point(3.0, 1.0) to "1-м съезде",
            (cy to (cx + 0.003)) to "2-м съезде",
            ((cy + 0.003) to cx) to "3-м съезде"
        )

        for ((destination, expectedExit) in destinations) {
            val route = g.route(start.first, start.second, destination.first, destination.second, Profile.CAR)
            assertNotNull("roundabout route to $expectedExit should exist", route)
            assertTrue(
                "expected roundabout instruction for $expectedExit",
                route!!.steps.any { it.kind == Maneuver.ROUNDABOUT && it.text.contains(expectedExit) }
            )
        }
    }

}
