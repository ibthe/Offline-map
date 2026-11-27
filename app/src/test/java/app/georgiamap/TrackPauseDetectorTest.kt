package app.georgiamap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPauseDetectorTest {
    @Test
    fun pausesAfterTwoMinutesWithinStationaryRadius() {
        val detector = TrackPauseDetector()
        assertEquals(TrackPauseDetector.Event.RECORD, detector.onFix(41.0, 44.0, 1_000L, 5.0))
        assertEquals(TrackPauseDetector.Event.RECORD, detector.onFix(41.0, 44.0, 31_000L, 5.0))
        assertEquals(TrackPauseDetector.Event.RECORD, detector.onFix(41.0, 44.0, 61_000L, 5.0))
        assertEquals(TrackPauseDetector.Event.RECORD, detector.onFix(41.0, 44.0, 91_000L, 5.0))
        assertEquals(TrackPauseDetector.Event.PAUSED, detector.onFix(41.0, 44.0, 121_000L, 5.0))
        assertTrue(detector.isPaused)
        assertEquals(TrackPauseDetector.Event.SKIP, detector.onFix(41.0, 44.0, 151_000L, 5.0))
    }

    @Test
    fun resumesAfterMovingAwayFromStop() {
        val detector = TrackPauseDetector()
        detector.onFix(41.0, 44.0, 1_000L, 5.0)
        detector.onFix(41.0, 44.0, 31_000L, 5.0)
        detector.onFix(41.0, 44.0, 61_000L, 5.0)
        detector.onFix(41.0, 44.0, 91_000L, 5.0)
        detector.onFix(41.0, 44.0, 121_000L, 5.0)
        assertEquals(TrackPauseDetector.Event.RESUMED,
            detector.onFix(41.0004, 44.0, 151_000L, 5.0))
        assertTrue(!detector.isPaused)
    }

    @Test
    fun walkingMovementDoesNotTriggerPause() {
        val detector = TrackPauseDetector()
        var time = 1_000L
        var lat = 41.0
        detector.onFix(lat, 44.0, time, 5.0)
        repeat(12) {
            time += 30_000L
            lat += 0.0002
            assertEquals(TrackPauseDetector.Event.RECORD,
                detector.onFix(lat, 44.0, time, 5.0))
        }
        assertTrue(!detector.isPaused)
    }

    @Test
    fun disabledAutoPauseAlwaysRecordsAcceptedFixes() {
        val detector = TrackPauseDetector(enabled = false)
        repeat(8) { i ->
            assertEquals(TrackPauseDetector.Event.RECORD,
                detector.onFix(41.0, 44.0, 1_000L + i * 30_000L, 5.0))
        }
        assertEquals(TrackPauseDetector.Event.SKIP, detector.onFix(41.0, 44.0, 2_000L, 5.0))
        assertTrue(!detector.isPaused)
    }

    @Test
    fun rejectsInvalidAndOutOfOrderFixes() {
        val detector = TrackPauseDetector()
        assertEquals(TrackPauseDetector.Event.SKIP,
            detector.onFix(Double.NaN, 44.0, 1_000L, 5.0))
        assertEquals(TrackPauseDetector.Event.RECORD,
            detector.onFix(41.0, 44.0, 2_000L, 5.0))
        assertEquals(TrackPauseDetector.Event.SKIP,
            detector.onFix(41.0, 44.0, 1_500L, 5.0))
    }
}
