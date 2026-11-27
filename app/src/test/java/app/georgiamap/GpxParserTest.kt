package app.georgiamap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream

class GpxParserTest {
    private fun parse(xml: String) = GpxParser.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    @Test
    fun parsesWaypointsTracksSegmentsAndRoutePoints() {
        val parsed = parse("""<?xml version="1.0"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="41.7" lon="44.8"><name>Camp &amp; base</name><time>2024-01-02T03:04:05Z</time></wpt>
              <trk><name>Mountain walk</name>
                <trkseg>
                  <trkpt lat="41.7001" lon="44.8001"><ele>1200</ele><time>2024-01-02T03:05:05Z</time></trkpt>
                  <trkpt lat="41.7002" lon="44.8002"/>
                </trkseg>
                <trkseg><trkpt lat="41.7003" lon="44.8003"><ele>1210</ele></trkpt></trkseg>
              </trk>
              <rte><name>Planned route</name><rtept lat="41.71" lon="44.81"/><rtept lat="41.72" lon="44.82"/></rte>
            </gpx>""")
        assertEquals(1, parsed.waypoints.size)
        assertEquals("Camp & base", parsed.waypoints[0].name)
        assertTrue(parsed.waypoints[0].timestampMs > 0L)
        assertEquals(2, parsed.tracks.size)
        assertEquals("Mountain walk", parsed.tracks[0].name)
        assertEquals(1, parsed.tracks[0].points[0].segment + parsed.tracks[0].points[2].segment)
        assertEquals(0, parsed.tracks[0].points[0].segment)
        assertEquals(1, parsed.tracks[0].points[2].segment)
        assertEquals(0L, parsed.tracks[0].points[1].timestampMs)
        assertEquals("Planned route", parsed.tracks[1].name)
        assertEquals(2, parsed.tracks[1].points.size)
        assertEquals(0, parsed.tracks[1].points[1].segment)
    }

    @Test
    fun rejectsInvalidCoordinatesAndNonGpxRoots() {
        try {
            parse("<gpx><wpt lat='91' lon='44'/></gpx>")
            throw AssertionError("invalid latitude should be rejected")
        } catch (_: SAXException) { }
        try {
            parse("<not-gpx/>")
            throw AssertionError("non-GPX root should be rejected")
        } catch (_: SAXException) { }
    }

    @Test
    fun rejectsDoctypeAndExternalEntityPayloads() {
        val xml = """<!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <gpx><wpt lat="41" lon="44"><name>&xxe;</name></wpt></gpx>"""
        try {
            parse(xml)
            throw AssertionError("DOCTYPE should be rejected")
        } catch (_: SAXException) { }
    }

    @Test
    fun parsesWaypointDescriptionAsNote() {
        val parsed = parse("""<gpx version="1.1">
            <wpt lat="41.7" lon="44.8">
                <name>Camp</name>
                <desc>Water nearby &amp; sheltered from wind</desc>
            </wpt>
        </gpx>""")
        assertEquals("Water nearby & sheltered from wind", parsed.waypoints.single().note)
    }

    @Test
    fun missingWaypointTimeRemainsMissing() {
        val parsed = parse("<gpx><wpt lat='41.7' lon='44.8'><name>Point</name></wpt></gpx>")
        assertEquals(0L, parsed.waypoints.single().timestampMs)
    }
}
