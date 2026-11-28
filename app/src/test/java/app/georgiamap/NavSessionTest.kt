package app.georgiamap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavSessionTest {
    @Test
    fun singlePointRouteDoesNotIndexPastGeometry() {
        val route = RouteResult(
            doubleArrayOf(41.7, 44.8),
            0.0,
            0.0,
            listOf(RouteStep(Maneuver.ARRIVE, "Вы прибыли", "", 41.7, 44.8, 0, 0.0))
        )
        val session = NavSession(route, Profile.FOOT)

        val atDestination = session.update(41.7, 44.8)
        assertTrue(atDestination.arrived)
        assertEquals(0.0, atDestination.remainingM, 0.0)
        assertEquals(0, atDestination.stepIndex)

        val away = session.update(41.701, 44.8)
        assertFalse(away.arrived)
        assertTrue(away.offRouteM > 100.0)
        assertTrue(away.remainingM > 100.0)
        assertTrue(away.remainingS > 0.0)
    }

    @Test
    fun tracksProgressAcrossAntimeridianAlongShortRoute() {
        val route = RouteResult(
            doubleArrayOf(0.0, 179.9, 0.0, -179.9),
            22_239.0,
            3_600.0,
            listOf(
                RouteStep(Maneuver.DEPART, "Старт", "", 0.0, 179.9, 0, 0.0),
                RouteStep(Maneuver.ARRIVE, "Финиш", "", 0.0, -179.9, 1, 0.0)
            )
        )
        val progress = NavSession(route, Profile.FOOT).update(0.0, -180.0)
        assertTrue(progress.alongM in 11_000.0..11_300.0)
        assertTrue(progress.remainingM in 11_000.0..11_300.0)
        assertTrue(progress.offRouteM < 5.0)
    }

    @Test
    fun ordinaryRouteTracksProgress() {
        val route = RouteResult(
            doubleArrayOf(41.7, 44.8, 41.701, 44.8),
            110.574,
            73.716,
            listOf(
                RouteStep(Maneuver.DEPART, "Старт", "", 41.7, 44.8, 0, 0.0),
                RouteStep(Maneuver.ARRIVE, "Финиш", "", 41.701, 44.8, 1, 0.0)
            )
        )
        val session = NavSession(route, Profile.FOOT)
        val progress = session.update(41.7005, 44.8)

        assertTrue(progress.alongM in 50.0..61.0)
        assertTrue(progress.remainingM in 50.0..61.0)
        assertFalse(progress.arrived)

        val atDestination = session.update(41.701, 44.8)
        assertTrue(atDestination.arrived)
        assertEquals(0.0, atDestination.remainingM, 0.0)

        // Projection onto the final segment is not proof of arrival when GPS is far off-route.
        val besideDestination = session.update(41.702, 44.8)
        assertFalse(besideDestination.arrived)
        assertTrue(besideDestination.remainingM > 100.0)
    }

    @Test
    fun progressDoesNotRewindWhenGpsSnapsToAnEarlierRouteSegment() {
        val route = RouteResult(
            doubleArrayOf(
                0.0, 0.0,
                0.0, 0.001,
                0.001, 0.001,
                0.001, 0.002,
                0.0, 0.002
            ),
            445.0,
            600.0,
            listOf(
                RouteStep(Maneuver.DEPART, "Старт", "", 0.0, 0.0, 0, 0.0),
                RouteStep(Maneuver.ARRIVE, "Финиш", "", 0.0, 0.002, 4, 0.0)
            )
        )
        val session = NavSession(route, Profile.FOOT)

        val advanced = session.update(0.001, 0.002)
        assertTrue(advanced.alongM > 300.0)

        // The user has moved back near the start. Keep the last confirmed progress
        // and flag the reverse movement so the activity can request a new route.
        val backtracking = session.update(0.0, 0.0001)
        assertTrue(backtracking.alongM >= advanced.alongM)
        assertTrue(backtracking.remainingM <= advanced.remainingM)
        assertTrue(backtracking.offRouteM > session.offRouteLimitM)
    }

}
