package app.georgiamap

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Waypoints and recorded tracks (private, on-device). */
class UserDb(ctx: Context) : SQLiteOpenHelper(ctx, "user.db", null, 3) {

    data class Wpt(val id: Long, val name: String, val lat: Double, val lon: Double, val ts: Long = 0L)
    data class Pt(val lat: Double, val lon: Double, val ele: Double, val ts: Long, val segment: Int = 0)
    data class Track(val id: Long, val name: String, val start: Long, val count: Int)
    data class ImportTrack(val name: String, val points: List<Pt>)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("create table waypoints(id integer primary key autoincrement, name text, lat real, lon real, ts integer)")
        db.execSQL("create table tracks(id integer primary key autoincrement, name text, start integer)")
        db.execSQL("create table track_points(id integer primary key autoincrement, track_id integer, ts integer, lat real, lon real, ele real, segment integer not null default 0)")
        db.execSQL("create index tp_segment_idx on track_points(track_id,segment,id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("alter table track_points add column segment integer not null default 0")
            db.execSQL("create index if not exists tp_segment_idx on track_points(track_id,segment,id)")
        }
        if (oldVersion < 3) {
            // tp_segment_idx already covers track_id lookups; the old single-column index was redundant.
            db.execSQL("drop index if exists tp_idx")
        }
    }

    fun addWaypoint(name: String, lat: Double, lon: Double): Long {
        val v = ContentValues()
        v.put("name", name); v.put("lat", lat); v.put("lon", lon); v.put("ts", System.currentTimeMillis())
        return writableDatabase.insert("waypoints", null, v)
    }

    fun waypointCount(): Int = readableDatabase.rawQuery("select count(*) from waypoints", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun waypoints(): List<Wpt> {
        val out = ArrayList<Wpt>()
        readableDatabase.rawQuery("select id,name,lat,lon,ts from waypoints order by id", null).use { c ->
            while (c.moveToNext()) out.add(Wpt(c.getLong(0), c.getString(1) ?: "", c.getDouble(2), c.getDouble(3), c.getLong(4)))
        }
        return out
    }

    fun deleteWaypoint(id: Long) {
        writableDatabase.delete("waypoints", "id=?", arrayOf(id.toString()))
    }

    fun newTrack(name: String): Long {
        val v = ContentValues()
        v.put("name", name); v.put("start", System.currentTimeMillis())
        return writableDatabase.insert("tracks", null, v)
    }

    fun lastTrackPoint(trackId: Long): Pt? =
        readableDatabase.rawQuery(
            "select lat,lon,ele,ts,segment from track_points where track_id=? order by segment desc,id desc limit 1",
            arrayOf(trackId.toString())
        ).use { c ->
            if (!c.moveToFirst()) null else Pt(
                c.getDouble(0), c.getDouble(1), if (c.isNull(2)) Double.NaN else c.getDouble(2),
                c.getLong(3), c.getInt(4)
            )
        }

    fun addPoint(trackId: Long, lat: Double, lon: Double, ele: Double, ts: Long, segment: Int = 0) {
        val v = ContentValues()
        v.put("track_id", trackId); v.put("ts", ts); v.put("lat", lat); v.put("lon", lon)
        if (!ele.isNaN()) v.put("ele", ele)
        v.put("segment", segment.coerceAtLeast(0))
        writableDatabase.insert("track_points", null, v)
    }

    fun tracks(): List<Track> {
        val out = ArrayList<Track>()
        readableDatabase.rawQuery(
            "select t.id, t.name, t.start, (select count(*) from track_points p where p.track_id=t.id) from tracks t order by t.id desc",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(Track(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getInt(3)))
        }
        return out
    }

    fun trackPoints(id: Long): List<Pt> {
        val out = ArrayList<Pt>()
        readableDatabase.rawQuery(
            "select lat,lon,ele,ts,segment from track_points where track_id=? order by segment,id", arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Pt(c.getDouble(0), c.getDouble(1), if (c.isNull(2)) Double.NaN else c.getDouble(2), c.getLong(3), c.getInt(4)))
            }
        }
        return out
    }

    fun trackSegments(id: Long): List<List<Pt>> =
        trackPoints(id).groupBy { it.segment }.toSortedMap().values.toList()

    /** Import all parsed GPX data atomically so malformed files never leave half-imported records. */
    fun importGpx(waypoints: List<Wpt>, importedTracks: List<ImportTrack>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (w in waypoints) {
                val v = ContentValues().apply {
                    put("name", w.name); put("lat", w.lat); put("lon", w.lon)
                    put("ts", w.ts.coerceAtLeast(0L))
                }
                db.insertOrThrow("waypoints", null, v)
            }
            for (track in importedTracks) {
                val tv = ContentValues().apply {
                    put("name", track.name)
                    put("start", track.points.map { it.ts }.filter { it > 0L }.minOrNull() ?: System.currentTimeMillis())
                }
                val id = db.insertOrThrow("tracks", null, tv)
                for (p in track.points) {
                    val pv = ContentValues().apply {
                        put("track_id", id); put("ts", p.ts); put("lat", p.lat); put("lon", p.lon)
                        put("segment", p.segment)
                        if (!p.ele.isNaN()) put("ele", p.ele)
                    }
                    db.insertOrThrow("track_points", null, pv)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteTrack(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("track_points", "track_id=?", arrayOf(id.toString()))
            db.delete("tracks", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

/** Offline search index built at CI time (places.db). */
class PlaceDb(path: String) {
    data class Place(val ka: String, val ru: String, val en: String, val kind: String,
                     val lat: Double, val lon: Double, val ele: Int)

    private val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

    @Synchronized
    fun count(): Int = db.rawQuery("select count(*) from places", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Synchronized
    fun search(query: String, limit: Int = 40): List<Place> {
        val q = query.trim().lowercase().replace("ё", "е").replace("%", "").replace("_", "")
        if (q.isEmpty()) return emptyList()
        val maxResults = limit.coerceIn(1, 200)
        val out = ArrayList<Place>(maxResults)
        val seen = HashSet<String>()

        fun append(c: android.database.Cursor) {
            val place = Place(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "",
                c.getString(3) ?: "", c.getDouble(4), c.getDouble(5), c.getInt(6))
            val key = place.ka + "|" + place.ru + "|" + place.en + "|" + place.lat + "|" + place.lon
            if (seen.add(key)) out.add(place)
        }

        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(q).map { it.value }.toList()
        if (tokens.isNotEmpty()) {
            val match = tokens.joinToString(" AND ") { "$it*" }
            try {
                db.rawQuery(
                    "select p.ka,p.ru,p.en,p.kind,p.lat,p.lon,ifnull(p.ele,0) " +
                        "from places_fts f join places p on p.id=f.docid " +
                        "where places_fts match ? " +
                        "order by case when p.s like ? or p.s like ? then 0 else 1 end, p.prio, length(p.s) limit ?",
                    arrayOf(match, q + "%", "%\n" + q + "%", maxResults.toString())
                ).use { c -> while (c.moveToNext() && out.size < maxResults) append(c) }
            } catch (_: Exception) {
                // Older or vendor SQLite builds may lack FTS4; the substring fallback remains available.
            }
        }

        // Preserve substring matches (for example, "bil" inside "Tbilisi") even when
        // the prefix index already returned other results. Append only the remaining slots.
        if (out.size < maxResults) {
            val remaining = maxResults - out.size
            db.rawQuery(
                "select ka,ru,en,kind,lat,lon,ifnull(ele,0) from places where s like ? " +
                    "order by case when s like ? or s like ? then 0 else 1 end, prio, length(s) limit ?",
                arrayOf("%" + q + "%", q + "%", "%\n" + q + "%", remaining.toString())
            ).use { c -> while (c.moveToNext() && out.size < maxResults) append(c) }
        }
        return out
    }

    @Synchronized
    fun close() = db.close()
}

/** Reads ground elevation from the Terrarium tiles bundled in relief.mbtiles. */
class Elevation(path: String, private val z: Int = 11) {
    private val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
    private var cacheKey = ""
    private var cacheBmp: Bitmap? = null

    @Synchronized
    fun at(lat: Double, lon: Double): Int? {
        if (lat < -85 || lat > 85) return null
        val n = 1 shl z
        val xf = (lon + 180.0) / 360.0 * n
        val r = Math.toRadians(lat)
        val yf = (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n
        val x = floor(xf).toInt()
        val y = floor(yf).toInt()
        if (x < 0 || y < 0 || x >= n || y >= n) return null
        val key = "$x/$y"
        var bmp: Bitmap? = if (key == cacheKey) cacheBmp else null
        if (bmp == null) {
            val row = n - 1 - y
            var blob: ByteArray? = null
            db.rawQuery(
                "select tile_data from tiles where zoom_level=? and tile_column=? and tile_row=?",
                arrayOf(z.toString(), x.toString(), row.toString())
            ).use { c -> if (c.moveToFirst()) blob = c.getBlob(0) }
            val b = blob ?: return null
            bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
            cacheKey = key
            cacheBmp = bmp
        }
        val bm = bmp ?: return null
        val px = ((xf - x) * bm.width).toInt().coerceIn(0, bm.width - 1)
        val py = ((yf - y) * bm.height).toInt().coerceIn(0, bm.height - 1)
        val p = bm.getPixel(px, py)
        val rr = (p shr 16) and 0xFF
        val gg = (p shr 8) and 0xFF
        val bb = p and 0xFF
        return Math.round((rr * 256 + gg + bb / 256.0) - 32768.0).toInt()
    }

    @Synchronized
    fun close() {
        cacheBmp?.recycle()
        cacheBmp = null
        db.close()
    }
}
