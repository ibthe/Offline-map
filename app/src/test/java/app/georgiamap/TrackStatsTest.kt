package app.georgiamap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackStatsTest {
    @Test
    fun calculatesDistanceElevationAndMovingTimeWithoutBridgingSegments() {
        val segments = listOf(
            listOf(
                TrackStats.Sample(41.0, 44.0, 100.0, 1_000L),
                TrackStats.Sample(41.001, 44.0, 110.0, 61_000L),
                TrackStats.Sample(41.002, 44.0, 105.0, 121_000L)
            ),
            listOf(
                TrackStats.Sample(41.010, 44.0, 130.0, 721_000L),
                TrackStats.Sample(41.011, 44.0, 150.0, 781_000L)
            )
        )
        val result = TrackStats.calculate(segments)
        assertTrue(result.distanceM in 320.0..340.0)
        assertEquals(30.0, result.ascentM, 0.001)
        assertEquals(5.0, result.descentM, 0.001)
        assertEquals(780_000L, result.elapsedMs)
        assertEquals(180_000L, result.movingMs)
        assertEquals(5, result.pointCount)
        assertTrue(result.averageSpeedKmh > 0.0)
        assertTrue(result.maxSpeedKmh >= result.averageSpeedKmh)
    }

    @Test
    fun ignoresMissingElevationAndUnknownTimestamps() {
        val result = TrackStats.calculate(listOf(listOf(
            TrackStats.Sample(41.0, 44.0, Double.NaN, 0L),
            TrackStats.Sample(41.001, 44.0, 120.0, 0L)
        )))
        assertEquals(0.0, result.ascentM, 0.0)
        assertEquals(0L, result.elapsedMs)
        assertEquals(0L, result.movingMs)
        assertEquals(0.0, result.maxSpeedKmh, 0.0)
    }

    @Test
    fun doesNotCountLongTimestampGapsAsMovingTime() {
        val result = TrackStats.calculate(listOf(listOf(
            TrackStats.Sample(41.0, 44.0, 10.0, 1_000L),
            TrackStats.Sample(41.001, 44.0, 10.0, 601_001L)
        )))
        assertTrue(result.distanceM > 100.0)
        assertEquals(0L, result.movingMs)
        assertEquals(600_001L, result.elapsedMs)
    }
}
