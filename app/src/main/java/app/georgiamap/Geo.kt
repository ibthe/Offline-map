package app.georgiamap

import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.util.Locale
import kotlin.math.roundToInt

object Geo {
    fun fmtDist(m: Double): String =
        if (m < 1000) "${m.roundToInt()} м" else String.format(Locale.US, "%.2f км", m / 1000.0)

    fun fmtCoord(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    fun length(pts: List<LatLng>): Double {
        var d = 0.0
        for (i in 1 until pts.size) d += pts[i - 1].distanceTo(pts[i])
        return d
    }

    fun pointFc(pts: List<LatLng>, names: List<String>? = null): FeatureCollection {
        val fs = ArrayList<Feature>()
        for (i in pts.indices) {
            val f = Feature.fromGeometry(Point.fromLngLat(pts[i].longitude, pts[i].latitude))
            if (names != null) f.addStringProperty("name", names[i])
            fs.add(f)
        }
        return FeatureCollection.fromFeatures(fs)
    }

    fun lineFc(pts: List<LatLng>): FeatureCollection {
        val fs = ArrayList<Feature>()
        if (pts.size >= 2) {
            fs.add(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) })))
        }
        return FeatureCollection.fromFeatures(fs)
    }

    /** A closed polygon for area-measurement overlays. */
    fun polygonFc(pts: List<LatLng>): FeatureCollection {
        if (pts.size < 3) return FeatureCollection.fromFeatures(emptyList())
        val ring = pts.map { Point.fromLngLat(it.longitude, it.latitude) }.toMutableList()
        if (pts.first() != pts.last()) ring.add(ring.first())
        return FeatureCollection.fromFeature(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))))
    }

    /** Spherical polygon area in square metres; longitude deltas are wrapped across the date line. */
    fun areaSquareMeters(pts: List<LatLng>): Double {
        if (pts.size < 3) return 0.0
        val radius = 6_371_008.8
        var sum = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val latA = Math.toRadians(a.latitude)
            val latB = Math.toRadians(b.latitude)
            val deltaLon = Math.toRadians(((b.longitude - a.longitude + 540.0) % 360.0) - 180.0)
            sum += deltaLon * (2.0 + kotlin.math.sin(latA) + kotlin.math.sin(latB))
        }
        return kotlin.math.abs(sum) * radius * radius / 2.0
    }

    fun fmtArea(squareMeters: Double): String = when {
        !squareMeters.isFinite() || squareMeters < 0.0 -> "—"
        squareMeters < 10_000.0 -> String.format(Locale.US, "%.0f м²", squareMeters)
        squareMeters < 1_000_000.0 -> String.format(Locale.US, "%.2f га", squareMeters / 10_000.0)
        else -> String.format(Locale.US, "%.2f км²", squareMeters / 1_000_000.0)
    }

    /** Multiple independent line strings; used for GPX tracks with separate segments. */
    fun linesFc(lines: List<List<LatLng>>): FeatureCollection {
        val features = lines.filter { it.size >= 2 }.map { line ->
            Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it.longitude, it.latitude) }))
        }
        return FeatureCollection.fromFeatures(features)
    }

    /** Accepts "41.7151, 44.8271", "41.7151 44.8271", "41.7151;44.8271". */
    fun parseCoord(text: String): LatLng? {
        val nums = Regex("-?\\d+(?:[.,]\\d+)?").findAll(text.replace(';', ' '))
            .map { it.value.replace(',', '.').toDoubleOrNull() }.toList()
        if (nums.size < 2 || nums[0] == null || nums[1] == null) return null
        val lat = nums[0]!!
        val lon = nums[1]!!
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        return LatLng(lat, lon)
    }
}
