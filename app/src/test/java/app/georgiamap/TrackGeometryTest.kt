package app.georgiamap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackGeometryTest {
    @Test
    fun reducesDenseStraightLineToEndpoints() {
        val points = (0..100).map { TrackGeometry.Point(41.0 + it * 0.00001, 44.0 + it * 0.00001) }
        val simplified = TrackGeometry.simplify(points, 5.0)
        assertEquals(listOf(points.first(), points.last()), simplified)
    }

    @Test
    fun preservesSharpTurnsAndEndpoints() {
        val points = listOf(
            TrackGeometry.Point(41.0, 44.0),
            TrackGeometry.Point(41.0001, 44.0001),
            TrackGeometry.Point(41.0002, 44.0002),
            TrackGeometry.Point(41.001, 44.0002),
            TrackGeometry.Point(41.002, 44.0002)
        )
        val simplified = TrackGeometry.simplify(points, 5.0)
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
        assertTrue(simplified.size >= 3)
        assertTrue(simplified.contains(points[2]))
    }

    @Test
    fun zeroLengthPolylineDoesNotCrash() {
        val points = List(20) { TrackGeometry.Point(41.0, 44.0) }
        assertEquals(listOf(points.first(), points.last()), TrackGeometry.simplify(points, 1.0))
    }

    @Test
    fun simplifiesHundredThousandPointTrackWithBoundedOutput() {
        val points = (0 until 100_000).map {
            TrackGeometry.Point(41.0 + it * 0.000001, 44.0)
        }
        val simplified = TrackGeometry.simplify(points, 1.0)
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
        assertTrue(simplified.size <= 2)
    }

    @Test
    fun invalidToleranceLeavesOriginalPoints() {
        val points = listOf(TrackGeometry.Point(41.0, 44.0), TrackGeometry.Point(41.1, 44.1))
        assertEquals(points, TrackGeometry.simplify(points, Double.NaN))
    }
}
