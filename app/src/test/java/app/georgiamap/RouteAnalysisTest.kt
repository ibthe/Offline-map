package app.georgiamap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAnalysisTest {
    @Test
    fun calculatesElevationRangeAscentDescentAndDifficulty() {
        val result = RouteAnalysis.calculate(
            listOf(
                RouteAnalysis.Sample(0.0, 100.0),
                RouteAnalysis.Sample(100.0, 120.0),
                RouteAnalysis.Sample(200.0, 140.0),
                RouteAnalysis.Sample(300.0, 110.0),
                RouteAnalysis.Sample(400.0, 90.0)
            ), 400.0
        )
        assertEquals(90.0, result.minElevationM!!, 0.0)
        assertEquals(140.0, result.maxElevationM!!, 0.0)
        assertEquals(40.0, result.ascentM, 0.0)
        assertEquals(50.0, result.descentM, 0.0)
        assertEquals(20.0, result.maxUphillGradePct!!, 0.001)
        assertEquals(30.0, result.maxDownhillGradePct!!, 0.001)
        assertEquals(RouteAnalysis.Difficulty.HARD, result.difficulty)
        assertEquals(2, result.steepSections.size)
        assertTrue(result.steepSections[0].uphill)
        assertEquals(20.0, result.steepSections[0].averageGradePct, 0.001)
        assertTrue(!result.steepSections[1].uphill)
        assertEquals(25.0, result.steepSections[1].averageGradePct, 0.001)
    }

    @Test
    fun missingElevationDoesNotBecomeZeroOrBridgeGaps() {
        val result = RouteAnalysis.calculate(
            listOf(
                RouteAnalysis.Sample(0.0, 100.0),
                RouteAnalysis.Sample(100.0, null),
                RouteAnalysis.Sample(200.0, 300.0)
            ), 200.0
        )
        assertEquals(67, result.coveragePct)
        assertEquals(0.0, result.ascentM, 0.0)
        assertEquals(0.0, result.descentM, 0.0)
        assertEquals(RouteAnalysis.Difficulty.UNKNOWN, result.difficulty)
    }

    @Test
    fun sampleKeepsEndpointsAndNeverExceedsMaximum() {
        val points = listOf(
            RouteAnalysis.Point(41.0, 44.0),
            RouteAnalysis.Point(41.01, 44.0),
            RouteAnalysis.Point(41.02, 44.0)
        )
        val samples = RouteAnalysis.sample(points, spacingM = 10.0, maxSamples = 20) { _, _ -> 123.0 }
        assertTrue(samples.size <= 20)
        assertEquals(0.0, samples.first().distanceM, 0.0)
        assertTrue(samples.last().distanceM > 2000.0)
        assertEquals(123.0, samples.first().elevationM!!, 0.0)
        assertEquals(123.0, samples.last().elevationM!!, 0.0)
    }

    @Test
    fun flatRouteHasNoSteepSections() {
        val result = RouteAnalysis.calculate((0..5).map {
            RouteAnalysis.Sample(it * 100.0, 200.0)
        }, 500.0)
        assertEquals(0.0, result.ascentM, 0.0)
        assertEquals(0.0, result.descentM, 0.0)
        assertTrue(result.steepSections.isEmpty())
        assertEquals(RouteAnalysis.Difficulty.EASY, result.difficulty)
    }

    @Test
    fun routeDistanceUsesHaversine() {
        val d = RouteAnalysis.distance(
            RouteAnalysis.Point(0.0, 0.0),
            RouteAnalysis.Point(0.0, 1.0)
        )
        assertEquals(111_195.0, d, 100.0)
    }

    @Test
    fun rejectsInvalidCoordinatesAndNonMonotonicSamples() {
        try {
            RouteAnalysis.sample(listOf(RouteAnalysis.Point(91.0, 0.0))) { _, _ -> 0.0 }
            throw AssertionError("invalid coordinate was accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            RouteAnalysis.calculate(listOf(
                RouteAnalysis.Sample(10.0, 1.0), RouteAnalysis.Sample(5.0, 2.0)
            ))
            throw AssertionError("non-monotonic distances were accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            RouteAnalysis.calculate(listOf(RouteAnalysis.Sample(Double.NaN, 1.0)), 0.0)
            throw AssertionError("non-finite first sample distance was accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
