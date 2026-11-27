package app.georgiamap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class GeoTest {
    @Test
    fun computesAreaForSmallSquareInSquareMeters() {
        val square = listOf(
            LatLng(0.0, 0.0),
            LatLng(0.0, 0.001),
            LatLng(0.001, 0.001),
            LatLng(0.001, 0.0)
        )
        val area = Geo.areaSquareMeters(square)
        assertTrue(area in 12_000.0..12_500.0)
        assertEquals("1.23 га", Geo.fmtArea(area))
    }

    @Test
    fun areaCalculationWrapsLongitudeAtDateLine() {
        val polygon = listOf(
            LatLng(0.0, 179.9),
            LatLng(0.0, -179.9),
            LatLng(0.1, -179.9),
            LatLng(0.1, 179.9)
        )
        val area = Geo.areaSquareMeters(polygon)
        assertTrue(area in 240_000_000.0..250_000_000.0)
    }

    @Test
    fun fewerThanThreeVerticesHaveNoArea() {
        assertEquals(0.0, Geo.areaSquareMeters(listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0))), 0.0)
    }
}
