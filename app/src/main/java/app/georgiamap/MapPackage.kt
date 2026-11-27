package app.georgiamap

import org.maplibre.android.geometry.LatLngBounds

data class MapBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {
    init {
        require(south.isFinite() && north.isFinite() && west.isFinite() && east.isFinite())
        require(south in -90.0..90.0 && north in -90.0..90.0 && south < north)
        require(west in -180.0..180.0 && east in -180.0..180.0 && west < east)
    }

    fun contains(lat: Double, lon: Double) =
        lat in south..north && lon in west..east

    fun toLatLngBounds() = LatLngBounds.from(north, east, south, west)
}

/** Asset contract for one offline region. Optional files may be absent without disabling the base map. */
data class MapPackage(
    val id: String,
    val title: String,
    val countryCode: String,
    val bounds: MapBounds,
    val tilesAsset: String,
    val placesAsset: String?,
    val reliefAsset: String?,
    val contoursAsset: String?,
    val routingAsset: String?
) {
    val assetFiles: List<String> get() =
        listOfNotNull(tilesAsset, placesAsset, reliefAsset, contoursAsset, routingAsset).distinct()
}

object MapPackages {
    const val DEFAULT_ID = "georgia"

    val GEORGIA = MapPackage(
        id = DEFAULT_ID,
        title = "Грузия",
        countryCode = "GE",
        bounds = MapBounds(south = 41.0, west = 40.0, north = 46.8, east = 43.65),
        tilesAsset = "georgia.mbtiles",
        placesAsset = "places.db",
        reliefAsset = "relief.mbtiles",
        contoursAsset = "contours.mbtiles",
        routingAsset = "routing.bin"
    )

    /** Additional regional packages can be registered here without hard-coding filenames in the activity. */
    val available: List<MapPackage> = listOf(GEORGIA)

    fun resolve(id: String): MapPackage =
        available.firstOrNull { it.id == id } ?: GEORGIA
}
