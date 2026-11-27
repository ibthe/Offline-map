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
}
