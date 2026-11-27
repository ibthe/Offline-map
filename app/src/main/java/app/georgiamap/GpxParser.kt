package app.georgiamap

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/** Streaming, bounded and XXE-safe GPX 1.1 parser. No Android dependencies: safe to regression-test on the JVM. */
object GpxParser {
    private const val WAYPOINT_EXTENSION_NS = "https://georgiamap.app/gpx/extensions/1"

    data class Waypoint(val name: String, val lat: Double, val lon: Double, val timestampMs: Long, val note: String = "", val category: String = "Общее", val folder: String = "Без папки")
    data class Point(
        val lat: Double, val lon: Double, val elevationM: Double,
        val timestampMs: Long, val segment: Int
    )
    data class Track(val name: String, val points: List<Point>)
    data class Parsed(val waypoints: List<Waypoint>, val tracks: List<Track>)

    private const val MAX_WAYPOINTS = 20_000
    private const val MAX_POINTS = 500_000
    private const val MAX_TRACKS = 10_000

    fun parse(input: InputStream, onProgress: (Int) -> Unit = {}): Parsed {
        val waypoints = ArrayList<Waypoint>()
        val tracks = ArrayList<Track>()
        var pointCount = 0

        val handler = object : DefaultHandler() {
            private var rootSeen = false
            private var inWaypoint = false
            private var inWaypointExtensions = false
            private var inTrack = false
            private var pointTag = ""
            private var waypointLat = 0.0
            private var waypointLon = 0.0
            private var waypointName = ""
            private var waypointNote = ""
            private var waypointCategory = "Общее"
            private var waypointFolder = "Без папки"
            private var waypointTime = 0L
            private var lat = 0.0
            private var lon = 0.0
            private var elevation = Double.NaN
            private var timestamp = 0L
            private var trackName = "Импорт GPX"
            private var segment = 0
            private var nextSegment = 0
            private val points = ArrayList<Point>()
            private var textTag = ""
            private val text = StringBuilder()

            private fun tag(localName: String, qName: String) =
                (if (localName.isNotEmpty()) localName else qName).substringAfter(':')

            private fun coordinate(attrs: Attributes, attr: String, element: String): Double {
                val value = attrs.getValue(attr)?.toDoubleOrNull()
                    ?: throw SAXException("Нет корректной координаты $attr у $element")
                if (!value.isFinite() || (attr == "lat" && value !in -90.0..90.0) ||
                    (attr == "lon" && value !in -180.0..180.0)) {
                    throw SAXException("Координата $attr вне допустимого диапазона у $element")
                }
                return value
            }

            override fun startElement(uri: String?, localName: String, qName: String, attrs: Attributes) {
                val name = tag(localName, qName)
                if (!rootSeen) {
                    if (name != "gpx") throw SAXException("Файл не является GPX")
                    rootSeen = true
                }
                when (name) {
                    "wpt" -> {
                        inWaypoint = true
                        waypointName = ""
                        waypointNote = ""
                        waypointCategory = "Общее"
                        waypointFolder = "Без папки"
                        inWaypointExtensions = false
                        waypointTime = 0L
                        waypointLat = coordinate(attrs, "lat", "wpt")
                        waypointLon = coordinate(attrs, "lon", "wpt")
                    }
                    "trk", "rte" -> {
                        inTrack = true
                        trackName = "Импорт GPX"
                        points.clear()
                        segment = 0
                        nextSegment = 0
                    }
                    "trkseg" -> if (inTrack) segment = nextSegment++
                    "trkpt", "rtept" -> if (inTrack) {
                        pointTag = name
                        lat = coordinate(attrs, "lat", name)
                        lon = coordinate(attrs, "lon", name)
                        elevation = Double.NaN
                        timestamp = 0L
                    }
                    "name" -> {
                        textTag = when {
                            inWaypoint -> "wpt-name"
                            inTrack && pointTag.isEmpty() -> "track-name"
                            else -> ""
                        }
                        text.setLength(0)
                    }
                    "desc", "cmt" -> if (inWaypoint) {
                        textTag = "wpt-note"
                        text.setLength(0)
                    }
                    "type" -> if (inWaypoint) {
                        textTag = "wpt-category"
                        text.setLength(0)
                    }
                    "extensions" -> if (inWaypoint) inWaypointExtensions = true
                    "folder" -> if (inWaypoint && inWaypointExtensions && uri == WAYPOINT_EXTENSION_NS) {
                        textTag = "wpt-folder"
                        text.setLength(0)
                    }
                    "ele" -> if (pointTag.isNotEmpty()) {
                        textTag = "ele"
                        text.setLength(0)
                    }
                    "time" -> {
                        textTag = when {
                            inWaypoint -> "wpt-time"
                            pointTag.isNotEmpty() -> "point-time"
                            else -> ""
                        }
                        text.setLength(0)
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (textTag.isNotEmpty()) text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String, qName: String) {
                val name = tag(localName, qName)
                if (textTag.isNotEmpty()) {
                    val value = text.toString().trim()
                    when (textTag) {
                        "wpt-name" -> waypointName = value.take(200)
                        "wpt-note" -> waypointNote = value.take(2_000)
                        "wpt-category" -> waypointCategory = value.take(100).ifBlank { "Общее" }
                        "wpt-folder" -> waypointFolder = value.take(100).ifBlank { "Без папки" }
                        "track-name" -> trackName = value.take(200)
                        "ele" -> elevation = value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: Double.NaN
                        "wpt-time" -> waypointTime = parseTime(value)
                        "point-time" -> timestamp = parseTime(value)
                    }
                    if ((textTag == "wpt-name" || textTag == "track-name" || textTag == "ele" ||
                        textTag == "wpt-time" || textTag == "point-time" || textTag == "wpt-note" || textTag == "wpt-category" || textTag == "wpt-folder") &&
                        name in setOf("name", "ele", "time", "desc", "cmt", "type", "folder")) {
                        textTag = ""
                        text.setLength(0)
                    }
                }
                when (name) {
                    "extensions" -> if (inWaypoint) inWaypointExtensions = false
                    "wpt" -> {
                        if (waypoints.size >= MAX_WAYPOINTS) throw SAXException("Слишком много точек в GPX (лимит $MAX_WAYPOINTS)")
                        waypoints.add(Waypoint(waypointName.ifBlank { "Точка ${waypoints.size + 1}" },
                            waypointLat, waypointLon, waypointTime, waypointNote, waypointCategory, waypointFolder))
                        inWaypoint = false
                        inWaypointExtensions = false
                    }
                    "trkpt", "rtept" -> if (pointTag == name) {
                        if (pointCount >= MAX_POINTS) throw SAXException("Слишком много точек трека в GPX (лимит $MAX_POINTS)")
                        points.add(Point(lat, lon, elevation, timestamp, segment))
                        pointCount++
                        if (pointCount % 10_000 == 0) onProgress(pointCount)
                        pointTag = ""
                    }
                    "trk", "rte" -> if (inTrack) {
                        if (points.isNotEmpty()) {
                            if (tracks.size >= MAX_TRACKS) throw SAXException("Слишком много треков в GPX (лимит $MAX_TRACKS)")
                            tracks.add(Track(trackName.ifBlank { "Импорт GPX" }, points.toList()))
                        }
                        inTrack = false
                        pointTag = ""
                    }
                }
            }

            fun result(): Parsed {
                if (!rootSeen) throw SAXException("Пустой GPX-файл")
                return Parsed(waypoints.toList(), tracks.toList())
            }
        }

        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val reader = factory.newSAXParser().xmlReader
        reader.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        try { reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) { }
        try { reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) { }
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.contentHandler = handler
        reader.parse(InputSource(input))
        return handler.result()
    }

    private fun parseTime(value: String): Long {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                return SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time ?: continue
            } catch (_: Exception) { }
        }
        return 0L
    }
}
