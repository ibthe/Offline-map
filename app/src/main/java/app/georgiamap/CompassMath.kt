package app.georgiamap

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure geometry for the compass (no Android code). All angles are degrees from TRUE north, clockwise. */
object CompassMath {
    /** The Kaaba in Mecca. */
    const val KAABA_LAT = 21.422487
    const val KAABA_LON = 39.826206

    /** Initial great-circle bearing from point 1 to point 2, 0..360. For Mecca this is the qibla direction. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val a = sin((p2 - p1) / 2).let { it * it } + cos(p1) * cos(p2) * sin(Math.toRadians(lon2 - lon1) / 2).let { it * it }
        return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun qibla(lat: Double, lon: Double) = bearing(lat, lon, KAABA_LAT, KAABA_LON)

    /** Signed turn needed to face [target] when the phone points at [heading]: positive = turn right. */
    fun turn(target: Double, heading: Double): Double {
        var d = (target - heading) % 360.0
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    fun aligned(target: Double, heading: Double, tolerance: Double) = abs(turn(target, heading)) <= tolerance

    fun cardinal(deg: Double): String {
        val names = arrayOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
        return names[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
    }

    fun fmtKm(km: Double) = if (km < 1.0) "${(km * 1000).toInt()} м" else if (km < 100) String.format(java.util.Locale.US, "%.1f км", km)
    else String.format(java.util.Locale.US, "%,d км", km.toLong()).replace(',', ' ')
}
