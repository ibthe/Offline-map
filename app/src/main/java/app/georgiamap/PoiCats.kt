package app.georgiamap

/**
 * Map categories: one chip on the main screen and one round icon on the map for each.
 *
 * [values] are OpenMapTiles `poi` class / subclass values; [kind] says where the marks come from:
 *  - POI      the `poi` layer (lbl-poi): the icon is picked per feature from these values
 *  - STATION  the transport-hub layers (lbl-station-*), always drawn with their own icons
 *  - AIRPORT  the `aerodrome_label` layer (lbl-aerodrome*)
 * [minZoom] is the zoom from which the marks exist in the tiles (used for the "zoom in" hint on the chip).
 * The chip selection is stored in the preference "poi_cats" by [title] (old installs keep their choice).
 */
class PoiCat(
    val title: String,
    val icon: String,              // drawable name, also the image name inside the map style
    val kind: Kind,
    val minZoom: Int,
    val values: List<String> = emptyList()
) {
    enum class Kind { POI, STATION, AIRPORT }
}

object PoiCats {
    const val STATIONS = "Вокзалы и станции"
    const val AIRPORTS = "Аэропорты"

    val ALL: List<PoiCat> = listOf(
        PoiCat("АЗС", "ic_cat_fuel", PoiCat.Kind.POI, 12, listOf("fuel", "charging_station")),
        PoiCat("Аптеки", "ic_cat_pharmacy", PoiCat.Kind.POI, 12, listOf("pharmacy", "chemist")),
        PoiCat("Больницы и врачи", "ic_cat_hospital", PoiCat.Kind.POI, 12, listOf("hospital", "clinic", "doctors", "dentist")),
        PoiCat("Банки и банкоматы", "ic_cat_bank", PoiCat.Kind.POI, 12, listOf("bank", "atm")),
        PoiCat("Магазины", "ic_cat_shop", PoiCat.Kind.POI, 12,
            listOf("shop", "supermarket", "convenience", "grocery", "bakery", "marketplace", "mall", "department_store")),
        PoiCat("Еда и кафе", "ic_cat_food", PoiCat.Kind.POI, 12,
            listOf("restaurant", "cafe", "fast_food", "bar", "pub", "food_and_drink", "food_court")),
        PoiCat("Ночлег", "ic_cat_lodging", PoiCat.Kind.POI, 12,
            listOf("lodging", "hotel", "hostel", "guest_house", "motel", "camp_site", "campsite",
                "caravan_site", "alpine_hut", "wilderness_hut", "chalet", "apartments")),
        PoiCat("Вода и родники", "ic_cat_water", PoiCat.Kind.POI, 12, listOf("drinking_water", "spring", "water_point", "water_tap")),
        PoiCat("Туалеты", "ic_cat_toilets", PoiCat.Kind.POI, 12, listOf("toilets")),
        PoiCat("Парковки", "ic_cat_parking", PoiCat.Kind.POI, 12, listOf("parking")),
        // railway / bus / ferry / cable-car stations have their own layers (own icons); no class list needed
        PoiCat(STATIONS, "ic_cat_rail", PoiCat.Kind.STATION, 11),
        PoiCat("Остановки и такси", "ic_cat_stop", PoiCat.Kind.POI, 12, listOf("bus", "bus_stop", "taxi")),
        PoiCat(AIRPORTS, "ic_cat_airport", PoiCat.Kind.AIRPORT, 8),
        PoiCat("Достопримечательности", "ic_cat_sight", PoiCat.Kind.POI, 12,
            listOf("attraction", "viewpoint", "museum", "monument", "castle", "ruins",
                "church", "monastery", "place_of_worship", "waterfall", "memorial", "archaeological_site", "information")),
        PoiCat("Полиция и экстренные", "ic_cat_police", PoiCat.Kind.POI, 12, listOf("police", "fire_station", "emergency"))
    )

    /** Every image the map style refers to (added to the style as bitmaps at start-up). */
    val IMAGES: List<String> = (ALL.map { it.icon } + listOf(
        "ic_cat_dot", "ic_cat_peak", "ic_cat_airport",
        "ic_cat_rail", "ic_cat_subway", "ic_cat_tram", "ic_cat_busstation", "ic_cat_ferry", "ic_cat_aerial"
    )).distinct()

    private fun matchAny(prop: String, vals: List<String>): String =
        "['match',['get','$prop'],[${vals.joinToString(",") { "'$it'" }}],true,false]"

    /** Filter condition: the feature belongs to one of these POI categories (by class or subclass). */
    fun anyOf(cats: List<PoiCat>): String {
        val vals = cats.filter { it.kind == PoiCat.Kind.POI }.flatMap { it.values }.distinct()
        if (vals.isEmpty()) return "['literal',false]"
        return "['any',${matchAny("class", vals)},${matchAny("subclass", vals)}]"
    }

    /** icon-image expression: the icon of the first category the feature belongs to, a small dot otherwise. */
    fun iconExpression(): String {
        val parts = ALL.filter { it.kind == PoiCat.Kind.POI }.joinToString(",") { c ->
            "['any',${matchAny("class", c.values)},${matchAny("subclass", c.values)}],'${c.icon}'"
        }
        return "['case',$parts,'ic_cat_dot']"
    }
}
