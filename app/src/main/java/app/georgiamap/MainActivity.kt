package app.georgiamap

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.util.Xml
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var tvInfo: TextView
    private lateinit var etSearch: EditText
    private lateinit var lvResults: ListView
    private lateinit var prefs: SharedPreferences
    private lateinit var userDb: UserDb

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var placeDb: PlaceDb? = null
    private var elevation: Elevation? = null
    private var tilesFile: File? = null

    private val ui = Handler(Looper.getMainLooper())
    private val errors = ArrayList<String>()
    private var searchResults: List<PlaceDb.Place> = emptyList()

    private var rulerOn = false
    private val rulerPts = ArrayList<LatLng>()
    private var shownTrackId = -1L

    // navigator
    private enum class NavMode { IDLE, PLACE, PREVIEW, ACTIVE }
    private var navMode = NavMode.IDLE
    private var navDest: LatLng? = null
    private var navDestName = ""
    private var navProfile = Profile.CAR
    @Volatile private var graph: RoadGraph? = null
    private var graphLoading = false
    private var session: NavSession? = null
    private var routeFrom: LatLng? = null
    private var offCount = 0
    private var lastReroute = 0L
    private var rerouting = false
    private val announced = HashSet<String>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var compass: CompassController
    private var myLoc: Location? = null
    private var following = false
    private var locActive = false
    private var pendingAfterPerm: (() -> Unit)? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            val ok = r[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                r[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            val action = pendingAfterPerm
            pendingAfterPerm = null
            if (ok) action?.invoke() else toast(getString(R.string.no_location))
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
            if (uri != null) writeGpx(uri)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readGpx(uri)
        }

    // ============================================================ lifecycle

    /** Five colour themes. Each one has a UI overlay (res/values/palettes.xml) and its own map style file. */
    private enum class Palette(val key: String, val title: String, val hint: String, val styleRes: Int, val swatch: Int) {
        OCEAN("ocean", "Океан", "спокойствие и ясность", R.style.ThemeOverlay_Georgia_Ocean, 0xFF2A7FA8.toInt()),
        FOREST("forest", "Лес", "естественный покой", R.style.ThemeOverlay_Georgia_Forest, 0xFF3F7D58.toInt()),
        DUSK("dusk", "Закат", "тепло и уют", R.style.ThemeOverlay_Georgia_Dusk, 0xFFB2552F.toInt()),
        LAVENDER("lavender", "Лаванда", "мягкая расслабленность", R.style.ThemeOverlay_Georgia_Lavender, 0xFF6B5BC4.toInt()),
        GRAPHITE("graphite", "Графит", "нейтральный фокус", R.style.ThemeOverlay_Georgia_Graphite, 0xFF3D4A5C.toInt())
    }

    private fun palette(): Palette {
        val k = getSharedPreferences("settings", MODE_PRIVATE).getString("palette", "ocean")
        return Palette.values().firstOrNull { it.key == k } ?: Palette.OCEAN
    }

    private fun showPaletteDialog() {
        val all = Palette.values()
        val items = all.map {
            SpannableString("●  ${it.title} — ${it.hint}").apply {
                setSpan(ForegroundColorSpan(it.swatch), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }.toTypedArray<CharSequence>()
        MaterialAlertDialogBuilder(this)
            .setTitle("Цветовая тема")
            .setSingleChoiceItems(items, all.indexOf(palette())) { _, which ->
                if (all[which] != palette()) {
                    // the UI overlay and the map style are rebuilt with the new palette; the chooser reopens afterwards
                    prefs.edit().putString("palette", all[which].key).putString("reopen", "palette").apply()
                    recreate()
                }
            }
            .setPositiveButton("Готово", null).show()
    }

    /** "auto" follows the system, "light" / "dark" force the app UI and the map style. */
    private fun themeMode(): String {
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val m = sp.getString("theme_mode", null)
        if (m != null) return m
        return if (sp.getBoolean("dark_map", false)) "dark" else "auto"   // migrate the old toggle
    }

    private fun applyThemeMode(m: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (m) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun isNight(): Boolean = when (themeMode()) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeMode(themeMode())
        super.onCreate(savedInstanceState)
        theme.applyStyle(palette().styleRes, true)   // colour theme, before any view is inflated
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        setupEdgeToEdge()

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        userDb = UserDb(this)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        tvInfo = findViewById(R.id.tvInfo)
        etSearch = findViewById(R.id.etSearch)
        lvResults = findViewById(R.id.lvResults)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener { msg -> errors.add(msg ?: "?") }

        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<View>(R.id.btnPoint).setOnClickListener {
            val c = map?.cameraPosition?.target
            if (c != null) addWaypointDialog(c)
        }
        findViewById<View>(R.id.btnLocation).setOnClickListener { withLocationPermission { startLocation(true) } }
        findViewById<View>(R.id.btnFit).setOnClickListener {
            map?.easeCamera(CameraUpdateFactory.newLatLngBounds(GEORGIA, 48), 700)
        }
        tvInfo.setOnClickListener {
            val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("info", tvInfo.text))
            toast("Скопировано")
        }

        navProfile = prefs.getInt("nav_profile", Profile.CAR)
        compass = CompassController(this, object : CompassController.Host {
            override fun origin(): CompassController.Origin? {
                myLoc?.let { return CompassController.Origin(it.latitude, it.longitude, if (it.hasAltitude()) it.altitude else 0.0, "GPS") }
                val c = map?.cameraPosition?.target ?: return null
                return CompassController.Origin(c.latitude, c.longitude, 0.0, "центр карты (GPS нет)")
            }
            override fun mapCenter() = map?.cameraPosition?.target?.let { CompassController.Target("Центр карты", it.latitude, it.longitude) }
            override fun searchPlaces(q: String): List<CompassController.Target> = placeDb?.search(q, 20)?.map { p ->
                CompassController.Target(listOf(p.ru, p.en, p.ka).firstOrNull { it.isNotEmpty() } ?: q, p.lat, p.lon)
            } ?: emptyList()
            override fun waypoints() = userDb.waypoints().map { CompassController.Target(it.name, it.lat, it.lon) }
            override fun navTarget() = navDest?.let { CompassController.Target(navDestName.ifBlank { "Цель" }, it.latitude, it.longitude) }
            override fun setMapBearing(deg: Double) {
                if (navMode == NavMode.ACTIVE) return
                map?.let { m -> m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).bearing(deg).build())) }
            }
            override fun toast(msg: String) = this@MainActivity.toast(msg)
        })
        findViewById<View>(R.id.btnNavClose).setOnClickListener { stopNav() }
        findViewById<View>(R.id.btnNavGo).setOnClickListener {
            when (navMode) {
                NavMode.PLACE -> planRoute()
                NavMode.PREVIEW -> startNav()
                NavMode.ACTIVE -> stopNav()
                NavMode.IDLE -> {}
            }
        }
        findViewById<View>(R.id.navProfCar).setOnClickListener { chooseProfile(Profile.CAR) }
        findViewById<View>(R.id.navProfFoot).setOnClickListener { chooseProfile(Profile.FOOT) }
        findViewById<View>(R.id.navProfBike).setOnClickListener { chooseProfile(Profile.BIKE) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navMode != NavMode.IDLE) stopNav() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        val btnClear = findViewById<View>(R.id.btnClear)
        btnClear.setOnClickListener { etSearch.setText(""); etSearch.requestFocus() }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                runSearch(s?.toString() ?: "")
            }
        })
        lvResults.setOnItemClickListener { _, _, pos, _ ->
            val p = searchResults.getOrNull(pos) ?: return@setOnItemClickListener
            showSearchResult(p)
        }

        prepareData {
            val dir = filesDir
            tilesFile = File(dir, "georgia.mbtiles")
            File(dir, "places.db").takeIf { it.exists() }?.let {
                placeDb = try { PlaceDb(it.absolutePath) } catch (e: Exception) { errors.add("places: $e"); null }
            }
            File(dir, "relief.mbtiles").takeIf { it.exists() }?.let {
                elevation = try { Elevation(it.absolutePath) } catch (e: Exception) { errors.add("relief: $e"); null }
            }
            mapView.getMapAsync { setupMap(it) }
        }
    }

    private fun reopenPending() {
        val what = prefs.getString("reopen", "") ?: ""
        if (what.isEmpty()) return
        prefs.edit().remove("reopen").apply()
        ui.post { if (what == "palette") showPaletteDialog() else if (what == "mode") showModeDialog() }
    }

    override fun onStart() { super.onStart(); reopenPending(); mapView.onStart(); compass.onStart(); if (locActive) startLocation(false) }
    override fun onResume() { super.onResume(); mapView.onResume(); scheduleTick() }
    override fun onPause() { mapView.onPause(); ui.removeCallbacks(tick); super.onPause() }
    override fun onStop() { stopLocationUpdates(); compass.onStop(); mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { tts?.shutdown(); mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    // ============================================================ edge-to-edge

    private var insetTop = 0
    private var insetBottom = 0

    private fun dp(v: Float) = (v * resources.displayMetrics.density).roundToInt()

    /** Map runs under transparent system bars; floating panels are moved clear of them. */
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.root)
        val tops = listOf(R.id.topPanel, R.id.navBanner).map { findViewById<View>(it) }
        val bottoms = listOf(R.id.bottomDock, R.id.navBar).map { findViewById<View>(it) }
        val baseTops = tops.map { (it.layoutParams as FrameLayout.LayoutParams).topMargin }
        val baseBottoms = bottoms.map { (it.layoutParams as FrameLayout.LayoutParams).bottomMargin }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insetTop = sb.top
            insetBottom = sb.bottom
            tops.forEachIndexed { i, v -> (v.layoutParams as FrameLayout.LayoutParams).topMargin = baseTops[i] + sb.top; v.requestLayout() }
            bottoms.forEachIndexed { i, v -> (v.layoutParams as FrameLayout.LayoutParams).bottomMargin = baseBottoms[i] + sb.bottom; v.requestLayout() }
            updateControlsMargin()
            applyMapUiMargins()
            insets
        }
        findViewById<View>(R.id.navBar).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateControlsMargin() }
        ViewCompat.requestApplyInsets(root)
    }

    /** The control capsule sits 16 dp above whatever panel is at the bottom (dock or navigator bar). */
    private fun updateControlsMargin() {
        val c = findViewById<View>(R.id.controls)
        val nb = findViewById<View>(R.id.navBar)
        val lp = c.layoutParams as FrameLayout.LayoutParams
        val panel = if (nb.visibility == View.VISIBLE) maxOf(nb.height, dp(64f)) else dp(64f)
        val want = insetBottom + dp(16f) + panel + dp(16f)
        if (lp.bottomMargin != want) { lp.bottomMargin = want; c.layoutParams = lp }
    }

    /** Keeps the MapLibre logo, attribution and compass out from under the floating panels. */
    private fun applyMapUiMargins() {
        val ui = map?.uiSettings ?: return
        val bottom = dp(96f) + insetBottom           // dock margin 16 + dock 64 + gap 16 (the MapLibre logo is switched off)
        ui.setAttributionMargins(dp(16f), 0, 0, bottom)
        ui.setCompassMargins(0, dp(16f + 56f + 8f + 48f + 8f) + insetTop, dp(16f), 0)   // under search + info chip
    }

    // ============================================================ data (assets -> files)

    private fun prepareData(onReady: () -> Unit) {
        val have = assets.list("")?.toSet() ?: emptySet()
        if ("georgia.mbtiles" !in have) {
            loading.visibility = View.VISIBLE
            loadingText.text = "В APK нет georgia.mbtiles — сборка карты не выполнилась"
            return
        }
        val todo = DATA_FILES.filter { it in have }
        for (n in DATA_FILES) if (n !in have) File(filesDir, n).delete()
        val version = BuildConfig.VERSION_CODE
        val ready = todo.all { File(filesDir, it).exists() } && prefs.getInt("data_version", -1) == version
        if (ready) { onReady(); return }

        loading.visibility = View.VISIBLE
        thread(name = "data-copy") {
            try {
                for (name in todo) copyAsset(name)
                prefs.edit().putInt("data_version", version).apply()
                runOnUiThread { loading.visibility = View.GONE; onReady() }
            } catch (e: Exception) {
                runOnUiThread { loadingText.text = "Ошибка: ${e.message}" }
            }
        }
    }

    private fun copyAsset(name: String) {
        val total = try { assets.openFd(name).use { it.length } } catch (e: Exception) { -1L }
        val tmp = File(filesDir, "$name.tmp")
        assets.open(name).use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var copied = 0L
                var last = -1L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    val mb = copied shr 20
                    if (mb != last) {
                        last = mb
                        val t = if (total > 0) "$mb / ${total shr 20} МБ" else "$mb МБ"
                        runOnUiThread { loadingText.text = getString(R.string.loading) + "\n$name: $t" }
                    }
                }
            }
        }
        val target = File(filesDir, name)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) error("rename failed: $name")
    }

    // ============================================================ map & style

    private fun setupMap(m: MapLibreMap) {
        map = m
        m.uiSettings.isLogoEnabled = false
        applyMapUiMargins()
        m.setMinZoomPreference(4.0)
        m.setMaxZoomPreference(18.0)
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(42.2, 43.5)).zoom(6.6).build()
            )
        )
        m.addOnCameraIdleListener { updateInfo() }
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) following = false
        }
        m.addOnMapLongClickListener { ll ->
            MaterialAlertDialogBuilder(this).setTitle(Geo.fmtCoord(ll.latitude, ll.longitude))
                .setItems(arrayOf("Маршрут сюда", "Сохранить точку")) { _, i ->
                    if (i == 0) setDestination(ll, Geo.fmtCoord(ll.latitude, ll.longitude)) else addWaypointDialog(ll)
                }.show()
            true
        }
        m.addOnMapClickListener { ll ->
            if (rulerOn) { rulerPts.add(ll); refreshRuler(); true } else false
        }
        loadStyle()
    }

    private fun loadStyle() {
        val m = map ?: return
        val tiles = tilesFile ?: return
        val name = "style_${palette().key}" + (if (isNight()) "_dark" else "") + ".json"
        val relief = File(filesDir, "relief.mbtiles")
        val obj = JSONObject(assets.open(name).bufferedReader().use { it.readText() })
        if (!relief.exists()) {
            obj.getJSONObject("sources").remove("relief")
            val src = obj.getJSONArray("layers")
            val dst = JSONArray()
            for (i in 0 until src.length()) {
                val l = src.getJSONObject(i)
                if (l.optString("id") != "relief-hillshade") dst.put(l)
            }
            obj.put("layers", dst)
        }
        val json = obj.toString()
            .replace("__MBTILES_URL__", "mbtiles://" + tiles.absolutePath)
            .replace("__RELIEF_URL__", "mbtiles://" + relief.absolutePath)
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            style = s
            addOverlays(s)
            applyLabels(s)
            applyPoiFilter(s)
            applyRelief(s)
            refreshWaypoints()
            refreshTrack()
            refreshRuler()
            refreshMe()
            updateInfo()
        }
    }

    private fun enabledLanguages(): List<Lang> = Lang.values().filter { prefs.getBoolean(it.prefKey, true) }

    private fun applyLabels(s: Style) {
        val langs = enabledLanguages()
        val simple = prefs.getBoolean("labels_simple", false)
        for (layer in s.layers) {
            if (layer is SymbolLayer && layer.id.startsWith("lbl-")) {
                if (simple) {
                    layer.setProperties(
                        PropertyFactory.visibility(Property.VISIBLE),
                        PropertyFactory.textField(Expression.raw("[\"to-string\",[\"get\",\"name\"]]"))
                    )
                } else if (langs.isEmpty()) {
                    layer.setProperties(PropertyFactory.visibility(Property.NONE))
                } else {
                    layer.setProperties(
                        PropertyFactory.visibility(Property.VISIBLE),
                        PropertyFactory.textField(LabelExpr.build(langs, layer.id))
                    )
                }
            }
        }
    }

    private fun applyRelief(s: Style) {
        val on = prefs.getBoolean("relief_on", true)
        s.getLayer("relief-hillshade")?.setProperties(
            PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
        )
    }

    private fun applyPoiFilter(s: Style) {
        val sel = prefs.getStringSet("poi_cats", emptySet()) ?: emptySet()
        val f: String
        val minZ: Float
        if (sel.isEmpty()) {
            f = "['has','name']"
            minZ = 15f
        } else {
            val vals = sel.flatMap { POI_CATS[it] ?: emptyList() }.distinct().joinToString(",") { "'$it'" }
            f = "['all',['has','name'],['any',['match',['get','class'],[$vals],true,false]," +
                "['match',['get','subclass'],[$vals],true,false]]]"
            minZ = 12f
        }
        val e = Expression.raw(f.replace('\'', '"'))
        s.getLayerAs<CircleLayer>("poi-dot")?.let { it.setFilter(e); it.setMinZoom(minZ) }
        s.getLayerAs<SymbolLayer>("lbl-poi")?.let { it.setFilter(e); it.setMinZoom(minZ) }
    }

    // ============================================================ overlays (points, tracks, ruler, me)

    private fun addOverlays(s: Style) {
        s.addSource(GeoJsonSource("ovl-search"))
        s.addLayer(CircleLayer("ovl-search-c", "ovl-search").withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#e53935"),
            PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(3f)))

        s.addSource(GeoJsonSource("ovl-track"))
        s.addLayer(LineLayer("ovl-track-l", "ovl-track").withProperties(
            PropertyFactory.lineColor("#1565c0"), PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))

        s.addSource(GeoJsonSource("ovl-route"))
        s.addLayer(LineLayer("ovl-route-case", "ovl-route").withProperties(
            PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(10f), PropertyFactory.lineOpacity(0.9f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        s.addLayer(LineLayer("ovl-route-l", "ovl-route").withProperties(
            PropertyFactory.lineColor(themeHex(R.attr.gmAccent)), PropertyFactory.lineWidth(6.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        s.addSource(GeoJsonSource("ovl-dest"))
        s.addLayer(CircleLayer("ovl-dest-c", "ovl-dest").withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(themeHex(R.attr.gmCrosshair)),
            PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(3f)))

        s.addSource(GeoJsonSource("ovl-ruler"))
        s.addLayer(LineLayer("ovl-ruler-l", "ovl-ruler").withProperties(
            PropertyFactory.lineColor("#e65100"), PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1.5f))))
        s.addSource(GeoJsonSource("ovl-ruler-pts"))
        s.addLayer(CircleLayer("ovl-ruler-c", "ovl-ruler-pts").withProperties(
            PropertyFactory.circleRadius(5f), PropertyFactory.circleColor("#e65100"),
            PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2f)))

        s.addSource(GeoJsonSource("ovl-wpt"))
        s.addLayer(CircleLayer("ovl-wpt-c", "ovl-wpt").withProperties(
            PropertyFactory.circleRadius(7f), PropertyFactory.circleColor("#d81b60"),
            PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2f)))
        s.addLayer(SymbolLayer("ovl-wpt-t", "ovl-wpt").withProperties(
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("NotoSans")),
            PropertyFactory.textSize(13f),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textColor("#880e4f"),
            PropertyFactory.textHaloColor("#ffffff"),
            PropertyFactory.textHaloWidth(1.5f)))

        s.addSource(GeoJsonSource("ovl-me"))
        s.addLayer(CircleLayer("ovl-me-c", "ovl-me").withProperties(
            PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#2979ff"),
            PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(3f)))
    }

    private fun themeColorInt(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun themeHex(attr: Int): String = String.format(Locale.US, "#%06X", themeColorInt(attr) and 0xFFFFFF)

    private fun src(id: String): GeoJsonSource? = style?.getSourceAs<GeoJsonSource>(id)

    private fun refreshWaypoints() {
        val w = userDb.waypoints()
        src("ovl-wpt")?.setGeoJson(Geo.pointFc(w.map { LatLng(it.lat, it.lon) }, w.map { it.name }))
    }

    private fun refreshTrack() {
        val id = if (TrackService.running) prefs.getLong("rec_track", 0L) else shownTrackId
        val pts = if (id > 0) userDb.trackLatLng(id) else emptyList()
        src("ovl-track")?.setGeoJson(Geo.lineFc(pts))
    }

    private fun refreshRuler() {
        src("ovl-ruler")?.setGeoJson(Geo.lineFc(rulerPts))
        src("ovl-ruler-pts")?.setGeoJson(Geo.pointFc(rulerPts))
        updateInfo()
    }

    private fun refreshMe() {
        val l = myLoc
        src("ovl-me")?.setGeoJson(Geo.pointFc(if (l != null) listOf(LatLng(l.latitude, l.longitude)) else emptyList()))
    }

    private val tick = object : Runnable {
        override fun run() {
            if (TrackService.running) {
                refreshTrack()
                updateInfo()
                ui.postDelayed(this, 5000)
            }
        }
    }

    private fun scheduleTick() {
        ui.removeCallbacks(tick)
        if (TrackService.running) ui.postDelayed(tick, 1000)
    }

    private fun updateInfo() {
        val m = map ?: return
        val c = m.cameraPosition.target ?: return
        val sb = StringBuilder(Geo.fmtCoord(c.latitude, c.longitude))
        elevation?.at(c.latitude, c.longitude)?.let { sb.append("  ·  ≈ $it м") }
        sb.append("  ·  z").append(String.format(Locale.US, "%.1f", m.cameraPosition.zoom))
        myLoc?.let {
            sb.append("\nGPS: ±").append(it.accuracy.roundToInt()).append(" м")
            if (it.hasAltitude()) sb.append(", высота ").append(it.altitude.roundToInt()).append(" м")
        } ?: run { if (locActive) sb.append("\nGPS: ожидание сигнала…") }
        if (rulerOn) sb.append("\nЛиния: ").append(Geo.fmtDist(Geo.length(rulerPts)))
            .append(" (тап — новая точка)")
        if (TrackService.running) sb.append("\n● REC трек пишется")
        tvInfo.text = sb.toString()
    }

    // ============================================================ search

    private fun runSearch(q: String) {
        if (q.trim().length < 2) {
            lvResults.visibility = View.GONE
            return
        }
        val db = placeDb
        if (db == null) {
            toast("Индекс поиска не найден в APK")
            return
        }
        searchResults = db.search(q)
        val items = searchResults.map { p ->
            val names = listOf(p.ka, p.ru, p.en).filter { it.isNotEmpty() }.distinct().joinToString(" / ")
            val extra = kindLabel(p.kind) + if (p.ele > 0) " · ${p.ele} м" else ""
            names to extra
        }
        lvResults.adapter = ResultsAdapter(items)
        val dp = resources.displayMetrics.density
        lvResults.layoutParams = lvResults.layoutParams.apply {
            height = minOf((items.size * 64 * dp).roundToInt(), (320 * dp).roundToInt())
        }
        lvResults.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class ResultsAdapter(private val items: List<Pair<String, String>>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_result, parent, false)
            v.findViewById<TextView>(R.id.resTitle).text = items[position].first
            v.findViewById<TextView>(R.id.resSub).text = items[position].second
            return v
        }
    }

    private fun showSearchResult(p: PlaceDb.Place) {
        val ll = LatLng(p.lat, p.lon)
        src("ovl-search")?.setGeoJson(Geo.pointFc(listOf(ll)))
        val z = when (p.kind) {
            "city" -> 11.5; "town" -> 12.5; "peak" -> 13.0
            "village", "hamlet", "suburb" -> 13.5
            else -> 15.5
        }
        map?.easeCamera(CameraUpdateFactory.newLatLngZoom(ll, z), 900)
        setDestination(ll, listOf(p.ru, p.en, p.ka).firstOrNull { it.isNotEmpty() } ?: Geo.fmtCoord(p.lat, p.lon))
        lvResults.visibility = View.GONE
        etSearch.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun kindLabel(k: String): String = KIND_RU[k] ?: k

    // ============================================================ location

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun withLocationPermission(action: () -> Unit) {
        if (hasLocationPermission()) { action(); return }
        pendingAfterPerm = action
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val first = myLoc == null
            myLoc = l
            refreshMe()
            if (navMode == NavMode.ACTIVE) onNavLocation(l)
            else if ((first || following) && map != null) {
                val z = maxOf(map!!.cameraPosition.zoom, 14.0)
                map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(l.latitude, l.longitude), z), 600)
                following = true
            }
            updateInfo()
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation(userRequested: Boolean) {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!gps && !net) {
            MaterialAlertDialogBuilder(this)
                .setMessage("Геолокация выключена. Включите GPS в настройках телефона.")
                .setPositiveButton("Настройки") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Отмена", null).show()
            return
        }
        locActive = true
        if (userRequested) {
            following = true
            myLoc?.let {
                map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.0), 600)
            }
        }
        var last: Location? = null
        try {
            lm.removeUpdates(locListener)
            if (gps) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            if (net) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, locListener, Looper.getMainLooper())
            if (myLoc == null) {
                last = (if (gps) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            }
        } catch (e: SecurityException) {
            toast(getString(R.string.no_location))
        }
        last?.let { locListener.onLocationChanged(it) }
        updateInfo()
    }

    private fun stopLocationUpdates() {
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locListener) } catch (e: Exception) {}
    }

    // ============================================================ menu

    private fun showMenu() {
        val langs = enabledLanguages().joinToString(" · ") { it.name }.ifEmpty { "выкл" }
        val hasRelief = File(filesDir, "relief.mbtiles").exists()
        val rec = TrackService.running
        val poiN = (prefs.getStringSet("poi_cats", emptySet()) ?: emptySet()).size
        val modeName = when (themeMode()) { "light" -> "Светлый"; "dark" -> "Тёмный"; else -> "Как в системе" }
        MenuUi.show(
            this, "Меню",
            listOf(
                MenuTile(R.drawable.ic_navigation, "Навигатор", navMode != NavMode.IDLE) { openNavigatorChooser() },
                MenuTile(R.drawable.ic_compass, "Компас", false) { compass.show(CompassController.Mode.NORTH) },
                MenuTile(R.drawable.ic_qibla, "Кибла", false) { compass.show(CompassController.Mode.QIBLA) },
                MenuTile(R.drawable.ic_bookmark, "Точки", false) { showWaypoints() },
                MenuTile(if (rec) R.drawable.ic_stop else R.drawable.ic_record, if (rec) "Стоп" else "Запись", rec) { toggleRecording() },
                MenuTile(R.drawable.ic_straighten, "Линейка", rulerOn) {
                    rulerOn = !rulerOn
                    if (!rulerOn) rulerPts.clear()
                    refreshRuler()
                }
            ),
            listOf(
                MenuSection("Карта", listOf(
                    MenuRow.Link("Языки названий", langs) { showLanguageDialog() },
                    MenuRow.Link("Категории на карте", if (poiN == 0) "все" else "выбрано $poiN") { showPoiDialog() },
                    MenuRow.Toggle("Рельеф", if (hasRelief) "Тени гор на карте" else "Нет данных в APK",
                        prefs.getBoolean("relief_on", true) && hasRelief, hasRelief) { on ->
                        prefs.edit().putBoolean("relief_on", on).apply()
                        style?.let { applyRelief(it) }
                    }
                )),
                MenuSection("Оформление", listOf(
                    MenuRow.Link("Режим", modeName) { showModeDialog() },
                    MenuRow.Link("Цветовая тема", palette().title) { showPaletteDialog() }
                )),
                MenuSection("Навигация", listOf(
                    MenuRow.Toggle("Голосовые подсказки", "Нужен русский голос в системе",
                        prefs.getBoolean("nav_voice", true)) { on -> prefs.edit().putBoolean("nav_voice", on).apply() },
                    MenuRow.Link("Транспорт по умолчанию", profileName(navProfile)) { showNavProfileDialog() }
                )),
                MenuSection("Мои данные", listOf(
                    MenuRow.Link("Мои треки") { showTracks() },
                    MenuRow.Link("Перейти к координатам") { gotoCoordinatesDialog() },
                    MenuRow.Link("Экспорт GPX") {
                        exportLauncher.launch("georgia-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".gpx")
                    },
                    MenuRow.Link("Импорт GPX") { importLauncher.launch(arrayOf("*/*")) }
                )),
                MenuSection("О приложении", listOf(
                    MenuRow.Link("Справка") { showInfo() },
                    MenuRow.Link("Диагностика") { showDiagnostics() },
                    MenuRow.Toggle("Простые подписи", "Для отладки карты", prefs.getBoolean("labels_simple", false)) { on ->
                        prefs.edit().putBoolean("labels_simple", on).apply()
                        style?.let { applyLabels(it) }
                    }
                ))
            )
        )
    }

    private fun showModeDialog() {
        val keys = listOf("auto", "light", "dark")
        val names = arrayOf("Как в системе", "Светлый", "Тёмный")
        MaterialAlertDialogBuilder(this).setTitle("Режим оформления")
            .setSingleChoiceItems(names, keys.indexOf(themeMode())) { _, which ->
                if (keys[which] != themeMode()) {
                    prefs.edit().putString("theme_mode", keys[which]).putString("reopen", "mode").apply()
                    applyThemeMode(keys[which])   // recreates the activity: UI and map style both switch
                }
            }
            .setPositiveButton("Готово", null).show()
    }

    private fun showLanguageDialog() {
        val all = Lang.values()
        val checked = BooleanArray(all.size) { prefs.getBoolean(all[it].prefKey, true) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_title)
            .setMultiChoiceItems(all.map { it.title }.toTypedArray(), checked) { _, which, isChecked ->
                prefs.edit().putBoolean(all[which].prefKey, isChecked).apply()
                style?.let { applyLabels(it) }
            }
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun showPoiDialog() {
        val names = POI_CATS.keys.toList()
        val cur = (prefs.getStringSet("poi_cats", emptySet()) ?: emptySet()).toMutableSet()
        val checked = BooleanArray(names.size) { names[it] in cur }
        MaterialAlertDialogBuilder(this)
            .setTitle("Показывать на карте (ничего не выбрано — все с 15-го масштаба)")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) cur.add(names[which]) else cur.remove(names[which])
                prefs.edit().putStringSet("poi_cats", HashSet(cur)).apply()
                style?.let { applyPoiFilter(it) }
            }
            .setPositiveButton(android.R.string.ok, null).show()
    }

    // ============================================================ waypoints

    private fun addWaypointDialog(ll: LatLng) {
        val et = EditText(this)
        et.setText("Точка ${userDb.waypoints().size + 1}")
        et.setSelectAllOnFocus(true)
        MaterialAlertDialogBuilder(this)
            .setTitle("Сохранить точку\n" + Geo.fmtCoord(ll.latitude, ll.longitude))
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                userDb.addWaypoint(et.text.toString().ifBlank { "Точка" }, ll.latitude, ll.longitude)
                refreshWaypoints()
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun showWaypoints() {
        val list = userDb.waypoints()
        if (list.isEmpty()) { toast("Точек нет. Долгое нажатие на карту или кнопка «Точка»"); return }
        val items = list.map { "${it.name}\n${Geo.fmtCoord(it.lat, it.lon)}" }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Мои точки").setItems(items) { _, i ->
            val w = list[i]
            MaterialAlertDialogBuilder(this).setTitle(w.name)
                .setItems(arrayOf("Перейти", "Маршрут сюда", "Удалить")) { _, a ->
                    when (a) {
                        0 -> flyTo(w.lat, w.lon, 15.0)
                        1 -> setDestination(LatLng(w.lat, w.lon), w.name)
                        else -> { userDb.deleteWaypoint(w.id); refreshWaypoints() }
                    }
                }.show()
        }.show()
    }

    private fun flyTo(lat: Double, lon: Double, zoom: Double) {
        following = false
        map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), 800)
    }

    private fun gotoCoordinatesDialog() {
        val et = EditText(this)
        et.hint = "41.7151, 44.8271"
        MaterialAlertDialogBuilder(this).setTitle("Широта, долгота").setView(et)
            .setPositiveButton("Перейти") { _, _ ->
                val ll = Geo.parseCoord(et.text.toString())
                if (ll == null) toast("Не удалось разобрать координаты")
                else {
                    src("ovl-search")?.setGeoJson(Geo.pointFc(listOf(ll)))
                    flyTo(ll.latitude, ll.longitude, 14.0)
                }
            }
            .setNegativeButton("Отмена", null).show()
    }

    // ============================================================ tracks

    private fun toggleRecording() {
        if (TrackService.running) {
            val savedId = prefs.getLong("rec_track", 0L)
            stopService(Intent(this, TrackService::class.java))
            prefs.edit().putLong("rec_track", 0L).apply()
            ui.postDelayed({ refreshTrack(); updateInfo() }, 400)
            MaterialAlertDialogBuilder(this).setTitle("Трек сохранён")
                .setMessage("Запись остановлена, маршрут сохранён на телефоне.\n\nНайти его: Меню → «Мои данные» → «Мои треки». " +
                    "Там трек можно показать на карте или удалить, а «Экспорт GPX» сохранит файл для других программ.")
                .setPositiveButton("Показать на карте") { _, _ ->
                    if (savedId > 0) { shownTrackId = savedId; refreshTrack(); fitTrack(savedId) }
                }
                .setNegativeButton("Закрыть", null).show()
            return
        }
        withLocationPermission {
            val id = userDb.newTrack("Трек " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date()))
            prefs.edit().putLong("rec_track", id).apply()
            shownTrackId = id
            ContextCompat.startForegroundService(this, Intent(this, TrackService::class.java))
            startLocation(false)
            ui.postDelayed({ scheduleTick(); updateInfo() }, 800)
            toast("Запись трека началась")
        }
    }

    private fun showTracks() {
        val list = userDb.tracks()
        if (list.isEmpty()) { toast("Треков пока нет"); return }
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val items = list.map {
            val len = Geo.length(userDb.trackLatLng(it.id))
            "${it.name}\n${fmt.format(Date(it.start))} · ${it.count} точек · ${Geo.fmtDist(len)}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Мои треки").setItems(items) { _, i ->
            val t = list[i]
            MaterialAlertDialogBuilder(this).setTitle(t.name)
                .setItems(arrayOf("Показать на карте", "Скрыть", "Удалить")) { _, a ->
                    when (a) {
                        0 -> { shownTrackId = t.id; refreshTrack(); fitTrack(t.id) }
                        1 -> { shownTrackId = -1L; refreshTrack() }
                        2 -> {
                            if (TrackService.running && prefs.getLong("rec_track", 0L) == t.id) toast("Сначала остановите запись")
                            else { userDb.deleteTrack(t.id); if (shownTrackId == t.id) shownTrackId = -1L; refreshTrack() }
                        }
                    }
                }.show()
        }.show()
    }

    private fun fitTrack(id: Long) {
        val pts = userDb.trackLatLng(id)
        if (pts.size < 2) return
        val b = LatLngBounds.Builder()
        pts.forEach { b.include(it) }
        try { map?.easeCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80), 800) } catch (e: Exception) {}
    }

    // ============================================================ GPX

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun writeGpx(uri: Uri) {
        try {
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<gpx version=\"1.1\" creator=\"Georgia Offline Map\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            for (w in userDb.waypoints()) {
                sb.append("<wpt lat=\"${w.lat}\" lon=\"${w.lon}\"><name>${esc(w.name)}</name></wpt>\n")
            }
            for (t in userDb.tracks()) {
                sb.append("<trk><name>${esc(t.name)}</name><trkseg>\n")
                for (p in userDb.trackPoints(t.id)) {
                    sb.append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
                    if (!p.ele.isNaN()) sb.append("<ele>${p.ele}</ele>")
                    if (p.ts > 0) sb.append("<time>${iso.format(Date(p.ts))}</time>")
                    sb.append("</trkpt>\n")
                }
                sb.append("</trkseg></trk>\n")
            }
            sb.append("</gpx>\n")
            contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            toast("GPX сохранён")
        } catch (e: Exception) {
            toast("Ошибка экспорта: ${e.message}")
        }
    }

    private fun readGpx(uri: Uri) {
        try {
            val p = Xml.newPullParser()
            var wpts = 0
            var trks = 0
            contentResolver.openInputStream(uri)?.use { stream ->
                p.setInput(stream, null)
                var inWpt = false
                var inTrk = false
                var inPt = false
                var lat = 0.0
                var lon = 0.0
                var ele = Double.NaN
                var name = ""
                var trkName = "Импорт GPX"
                val pts = ArrayList<UserDb.Pt>()
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (p.name) {
                            "wpt" -> { inWpt = true; name = ""; lat = p.getAttributeValue(null, "lat").toDouble(); lon = p.getAttributeValue(null, "lon").toDouble() }
                            "trk" -> { inTrk = true; trkName = "Импорт GPX"; pts.clear() }
                            "trkpt" -> { inPt = true; ele = Double.NaN; lat = p.getAttributeValue(null, "lat").toDouble(); lon = p.getAttributeValue(null, "lon").toDouble() }
                            "name" -> { val t = p.nextText(); if (inWpt) name = t else if (inTrk && !inPt) trkName = t }
                            "ele" -> { val t = p.nextText(); if (inPt) ele = t.toDoubleOrNull() ?: Double.NaN }
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        when (p.name) {
                            "wpt" -> { userDb.addWaypoint(name.ifBlank { "Точка" }, lat, lon); inWpt = false; wpts++ }
                            "trkpt" -> { pts.add(UserDb.Pt(lat, lon, ele, 0L)); inPt = false }
                            "trk" -> {
                                if (pts.isNotEmpty()) { val id = userDb.newTrack(trkName); userDb.addPoints(id, pts); trks++ }
                                inTrk = false
                            }
                        }
                    }
                    ev = p.next()
                }
            }
            refreshWaypoints()
            toast("Импортировано: точек $wpts, треков $trks")
        } catch (e: Exception) {
            toast("Ошибка импорта: ${e.message}")
        }
    }

    // ============================================================ phrasebook, info, diagnostics

    private fun showInfo() {
        val msg = "Экстренные службы: 112 (единый номер).\n\n" +
            "Карта, поиск и рельеф работают без интернета. GPS тоже работает без сети.\n\n" +
            "Горные дороги и перевалы (Омало, Ушгули, Абано и др.) зимой могут быть закрыты — на карте это не отражено. " +
            "Перед выездом уточняйте обстановку на месте.\n\n" +
            "Запись трека (кнопка «Запись» в меню) сохраняет ваш путь по GPS: линию на карте, длину и точки. " +
            "Остановив запись, найдёте трек в Меню → «Мои данные» → «Мои треки». " +
            "Для долгой записи разрешите приложению работу в фоне и не закрывайте уведомление.\n\n" +
            "Компас: направление на север, на Мекку (кибла) или на любой пункт по названию; нужен датчик ориентации, GPS или центр карты задают вашу позицию.\n\n" +
            "Данные: © участники OpenStreetMap (ODbL), схема OpenMapTiles, Natural Earth, рельеф — AWS Terrain Tiles. " +
            "Данные актуальны на дату сборки APK."
        MaterialAlertDialogBuilder(this).setTitle("Справка").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun showDiagnostics() {
        val sb = StringBuilder()
        val t = tilesFile
        if (t != null && t.exists()) {
            sb.append("georgia.mbtiles: ${t.length() shr 20} МБ\n")
            try {
                val db = SQLiteDatabase.openDatabase(t.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                db.rawQuery("select name,value from metadata where name in ('minzoom','maxzoom','bounds','format')", null).use {
                    while (it.moveToNext()) sb.append("  ${it.getString(0)} = ${it.getString(1)}\n")
                }
                val zs = StringBuilder()
                db.rawQuery("select zoom_level,count(*) from tiles group by 1 order by 1", null).use {
                    while (it.moveToNext()) zs.append("z${it.getInt(0)}:${it.getInt(1)} ")
                }
                sb.append("  тайлы: $zs\n")
                db.close()
            } catch (e: Exception) { sb.append("  ошибка чтения: $e\n") }
        } else sb.append("georgia.mbtiles: нет\n")
        sb.append("places.db: ").append(placeDb?.count()?.toString() ?: "нет").append(" мест\n")
        sb.append("relief: ").append(if (elevation != null) "есть" else "нет").append("\n")
        try {
            val files = assets.list("glyphs/NotoSans")?.size ?: 0
            sb.append("шрифты (glyphs) в APK: $files файлов\n")
            // a real font has many glyphs with ink in these ranges; a placeholder has almost none
            for ((f, name, need) in listOf(
                Triple("0-255", "латиница", 150), Triple("1024-1279", "кириллица", 200), Triple("4096-4351", "грузинский", 45)
            )) {
                val bytes = try { assets.open("glyphs/NotoSans/$f.pbf").use { it.readBytes() } } catch (e: Exception) { null }
                if (bytes == null) { sb.append("  $name: файла нет ✗\n"); continue }
                val ink = glyphsWithInk(bytes)
                sb.append("  $name ($f): $ink глифов ${if (ink >= need) "✓" else "✗ (заглушка, не шрифт)"}\n")
            }
        } catch (e: Exception) { sb.append("шрифты: ошибка $e\n") }

        val m = map
        val v = mapView
        if (m != null && style != null) {
            sb.append("\nОтрисованные объекты на экране:\n")
            val rect = RectF(0f, 0f, v.width.toFloat(), v.height.toFloat())
            for (id in listOf("water", "landcover-wood", "landuse-residential", "road-primary", "road-minor",
                "road-path", "building", "boundary-2", "boundary-4", "lbl-city", "lbl-town", "lbl-village", "lbl-peak", "lbl-road-major")) {
                val n = try { m.queryRenderedFeatures(rect, id).size } catch (e: Exception) { -1 }
                sb.append("  $id: $n\n")
            }
        }
        if (errors.isNotEmpty()) {
            sb.append("\nОшибки загрузки:\n")
            errors.takeLast(6).forEach { sb.append("  $it\n") }
        }
        MaterialAlertDialogBuilder(this).setTitle("Диагностика").setMessage(sb.toString())
            .setPositiveButton("OK", null).show()
    }

    /** Number of glyphs that have a bitmap in an SDF glyph file (glyphs.proto). */
    private fun glyphsWithInk(data: ByteArray): Int {
        var ink = 0
        try {
            forEachField(data, 0, data.size) { n, wt, _, s, e ->
                if (n == 1 && wt == 2) forEachField(data, s, e) { n2, w2, _, s2, e2 ->
                    if (n2 == 3 && w2 == 2) {
                        var width = 0L
                        var bitmap = false
                        forEachField(data, s2, e2) { n3, w3, v3, s3, e3 ->
                            if (n3 == 3 && w3 == 0) width = v3
                            if (n3 == 2 && w3 == 2 && e3 > s3) bitmap = true
                        }
                        if (width > 0 && bitmap) ink++
                    }
                }
            }
        } catch (e: Exception) { return -1 }
        return ink
    }

    private fun forEachField(b: ByteArray, from: Int, to: Int, cb: (Int, Int, Long, Int, Int) -> Unit) {
        var p = from
        fun vint(): Long {
            var r = 0L
            var shift = 0
            while (p < to) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if ((x and 0x80) == 0) break
                shift += 7
            }
            return r
        }
        while (p < to) {
            val key = vint().toInt()
            val num = key ushr 3
            when (key and 7) {
                0 -> cb(num, 0, vint(), 0, 0)
                2 -> { val len = vint().toInt(); cb(num, 2, 0L, p, p + len); p += len }
                1 -> p += 8
                5 -> p += 4
                else -> return
            }
        }
    }

    // ============================================================ navigator (offline road routing)

    private fun profileName(p: Int) = when (p) { Profile.FOOT -> "Пешком"; Profile.BIKE -> "На велосипеде"; else -> "На авто" }

    private fun showNavProfileDialog() {
        val ps = listOf(Profile.CAR, Profile.FOOT, Profile.BIKE)
        MaterialAlertDialogBuilder(this).setTitle("Транспорт по умолчанию")
            .setSingleChoiceItems(ps.map { profileName(it) }.toTypedArray(), ps.indexOf(navProfile)) { d, which ->
                navProfile = ps[which]
                prefs.edit().putInt("nav_profile", navProfile).apply()
                d.dismiss()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun openNavigatorChooser() {
        if (navMode == NavMode.ACTIVE) {
            MaterialAlertDialogBuilder(this).setMessage("Завершить текущую навигацию?")
                .setPositiveButton("Завершить") { _, _ -> stopNav() }
                .setNegativeButton("Нет", null).show()
            return
        }
        val items = arrayOf("Точку на карте", "В одну из моих точек", "По координатам", "Найти место через поиск")
        MaterialAlertDialogBuilder(this).setTitle("Навигатор: куда едем?").setItems(items) { _, i ->
            when (i) {
                0 -> map?.cameraPosition?.target?.let { setDestination(it, Geo.fmtCoord(it.latitude, it.longitude)) }
                1 -> {
                    val list = userDb.waypoints()
                    if (list.isEmpty()) toast("Сохранённых точек нет")
                    else MaterialAlertDialogBuilder(this).setTitle("Куда").setItems(list.map { it.name }.toTypedArray()) { _, k ->
                        setDestination(LatLng(list[k].lat, list[k].lon), list[k].name)
                    }.show()
                }
                2 -> {
                    val et = EditText(this)
                    et.hint = "41.7151, 44.8271"
                    MaterialAlertDialogBuilder(this).setTitle("Широта, долгота").setView(et)
                        .setPositiveButton("Маршрут") { _, _ ->
                            val ll = Geo.parseCoord(et.text.toString())
                            if (ll == null) toast("Не удалось разобрать координаты")
                            else setDestination(ll, Geo.fmtCoord(ll.latitude, ll.longitude))
                        }.setNegativeButton("Отмена", null).show()
                }
                else -> {
                    etSearch.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }.show()
    }

    private fun setDestination(ll: LatLng, name: String) {
        if (navMode == NavMode.ACTIVE) return
        navDest = ll
        navDestName = name
        session = null
        src("ovl-route")?.setGeoJson(Geo.lineFc(emptyList()))
        src("ovl-dest")?.setGeoJson(Geo.pointFc(listOf(ll)))
        navMode = NavMode.PLACE
        updateNavUi()
    }

    private fun chooseProfile(p: Int) {
        navProfile = p
        prefs.edit().putInt("nav_profile", p).apply()
        if (navMode == NavMode.PREVIEW) planRoute() else updateNavUi()
    }

    private fun fmtDur(sec: Double): String {
        val m = (sec / 60).roundToInt().coerceAtLeast(1)
        return if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"
    }

    private fun fmtEta(sec: Double): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (sec * 1000).toLong()))

    private fun fmtStepDist(m: Double): String = when {
        m < 25 -> "Сейчас"
        m < 200 -> "${(m / 10).roundToInt() * 10} м"
        m < 1000 -> "${(m / 50).roundToInt() * 50} м"
        else -> String.format(Locale.US, "%.1f км", m / 1000.0)
    }

    private fun setSummary(remM: Double, remS: Double) {
        findViewById<TextView>(R.id.navMain).text = Geo.fmtDist(remM) + " · " + fmtDur(remS)
        findViewById<TextView>(R.id.navSub).text = "Прибытие в " + fmtEta(remS) + " · " + profileName(navProfile) +
            if (navDestName.isNotBlank()) "\n" + navDestName else ""
    }

    /** One bottom bar serves three states: a chosen place, a route preview, active guidance. */
    private fun updateNavUi() {
        val mode = navMode
        val active = mode == NavMode.ACTIVE
        findViewById<View>(R.id.navBanner).visibility = if (active) View.VISIBLE else View.GONE
        findViewById<View>(R.id.topPanel).visibility = if (active) View.GONE else View.VISIBLE
        val bar = findViewById<View>(R.id.navBar)
        bar.visibility = if (mode == NavMode.IDLE) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottomDock).visibility = if (mode == NavMode.IDLE) View.VISIBLE else View.GONE
        findViewById<View>(R.id.navProfiles).visibility = if (mode == NavMode.PREVIEW) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnNavClose).visibility = if (active) View.GONE else View.VISIBLE
        val go = findViewById<TextView>(R.id.btnNavGo)
        go.setBackgroundResource(if (active) R.drawable.bg_btn_danger else R.drawable.bg_btn_primary)
        go.text = when (mode) { NavMode.PLACE -> "Маршрут"; NavMode.PREVIEW -> "Начать"; NavMode.ACTIVE -> "Завершить"; else -> "" }
        listOf(R.id.navProfCar to Profile.CAR, R.id.navProfFoot to Profile.FOOT, R.id.navProfBike to Profile.BIKE).forEach { (id, p) ->
            val v = findViewById<TextView>(id)
            val sel = p == navProfile
            v.setBackgroundResource(if (sel) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            v.setTextColor(themeColorInt(if (sel) R.attr.gmOnAccentContainer else R.attr.gmTextSecondary))
        }
        when (mode) {
            NavMode.PLACE -> {
                findViewById<TextView>(R.id.navMain).text = navDestName
                findViewById<TextView>(R.id.navSub).text = navDest?.let { Geo.fmtCoord(it.latitude, it.longitude) } ?: ""
            }
            NavMode.PREVIEW -> session?.let { setSummary(it.totalM, it.route.timeS) }
            else -> {}
        }
        bar.post { updateControlsMargin() }
    }

    private fun planRoute() {
        val dest = navDest ?: return
        val from = myLoc?.let { LatLng(it.latitude, it.longitude) } ?: routeFrom
        if (from == null) {
            MaterialAlertDialogBuilder(this)
                .setMessage("Нет сигнала GPS. Построить маршрут от центра карты или включить GPS?")
                .setPositiveButton("Включить GPS") { _, _ -> withLocationPermission { startLocation(true) } }
                .setNeutralButton("От центра карты") { _, _ ->
                    routeFrom = map?.cameraPosition?.target
                    planRoute()
                }
                .setNegativeButton("Отмена", null).show()
            return
        }
        computeRoute(from, dest, false) { r, err ->
            if (r == null) {
                toast(err ?: "Маршрут не найден")
                updateNavUi()
                return@computeRoute
            }
            session = NavSession(r, navProfile)
            drawRoute(r)
            fitRoute(r)
            navMode = NavMode.PREVIEW
            updateNavUi()
        }
    }

    /** Runs the search on a worker thread (the road graph is loaded from routing.bin on first use). */
    private fun computeRoute(from: LatLng, to: LatLng, silent: Boolean, done: (RouteResult?, String?) -> Unit) {
        val f = File(filesDir, "routing.bin")
        if (!f.exists()) {
            done(null, "В приложении нет дорожных данных (routing.bin). Пересоберите APK.")
            return
        }
        val profile = navProfile
        if (!silent) findViewById<TextView>(R.id.navSub).text = if (graph == null) "Загружаю дорожные данные…" else "Строю маршрут…"
        thread(name = "routing") {
            try {
                val g = graph ?: RandomAccessFile(f, "r").use { raf ->
                    RoadGraph(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()))
                }.also { graph = it }
                val r = g.route(from.latitude, from.longitude, to.latitude, to.longitude, profile)
                runOnUiThread { done(r, if (r == null) "Маршрут не найден: рядом нет подходящей дороги или нет связи между точками" else null) }
            } catch (e: Throwable) {
                runOnUiThread { done(null, "Ошибка маршрутизации: ${e.message}") }
            }
        }
    }

    private fun drawRoute(r: RouteResult) {
        src("ovl-route")?.setGeoJson(Geo.lineFc((0 until r.pointCount).map { LatLng(r.lat(it), r.lon(it)) }))
    }

    private fun fitRoute(r: RouteResult) {
        val pts = ArrayList<LatLng>()
        val step = maxOf(1, r.pointCount / 200)
        var i = 0
        while (i < r.pointCount) { pts.add(LatLng(r.lat(i), r.lon(i))); i += step }
        pts.add(LatLng(r.lat(r.pointCount - 1), r.lon(r.pointCount - 1)))
        if (pts.size < 2) return
        try {
            val b = LatLngBounds.Builder().includes(pts).build()
            following = false
            map?.easeCamera(
                CameraUpdateFactory.newLatLngBounds(b, dp(48f), dp(96f) + insetTop, dp(48f), dp(260f) + insetBottom), 900
            )
        } catch (e: Exception) { /* degenerate route: keep the camera */ }
    }

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("ru", "RU"))
                ttsReady = r != null && r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady || !prefs.getBoolean("nav_voice", true)) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    private fun startNav() {
        if (session == null) return
        navMode = NavMode.ACTIVE
        offCount = 0
        announced.clear()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (prefs.getBoolean("nav_voice", true)) initTts()
        following = true
        withLocationPermission { startLocation(false) }
        updateNavUi()
        session?.let { s ->
            val st = s.route.steps.getOrNull(1) ?: s.route.steps.first()
            findViewById<TextView>(R.id.navArrow).text = Maneuver.arrow(st.kind)
            findViewById<TextView>(R.id.navDist).text = fmtStepDist(s.route.steps.first().distToNextM)
            findViewById<TextView>(R.id.navText).text = s.route.steps.first().text
            setSummary(s.totalM, s.route.timeS)
        }
        val l = myLoc
        if (l != null) onNavLocation(l) else toast("Ждём сигнал GPS…")
    }

    private fun stopNav() {
        navMode = NavMode.IDLE
        session = null
        navDest = null
        routeFrom = null
        rerouting = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tts?.stop()
        src("ovl-route")?.setGeoJson(Geo.lineFc(emptyList()))
        src("ovl-dest")?.setGeoJson(Geo.pointFc(emptyList()))
        map?.let { m ->
            m.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).tilt(0.0).bearing(0.0).build()), 500)
        }
        updateNavUi()
    }

    private fun onNavLocation(l: Location) {
        val s = session ?: return
        val prog = s.update(l.latitude, l.longitude)
        val m = map
        if (following && m != null) {
            val bearing = if (l.hasBearing() && l.speed > 1.5f) l.bearing.toDouble() else m.cameraPosition.bearing
            val zoom = if (s.profile == Profile.CAR) 16.5 else 17.5
            m.easeCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(l.latitude, l.longitude)).zoom(zoom).bearing(bearing).tilt(40.0).build()), 900)
        }
        if (prog.arrived) {
            speak("Вы прибыли в пункт назначения")
            toast("Вы прибыли в пункт назначения")
            stopNav()
            return
        }
        val step = s.route.steps[prog.stepIndex]
        findViewById<TextView>(R.id.navArrow).text = Maneuver.arrow(step.kind)
        findViewById<TextView>(R.id.navDist).text = fmtStepDist(prog.distToStepM)
        findViewById<TextView>(R.id.navText).text = step.text
        setSummary(prog.remainingM, prog.remainingS)

        val far = if (s.profile == Profile.CAR) 400.0 else 80.0
        val near = if (s.profile == Profile.CAR) 70.0 else 25.0
        if (step.kind != Maneuver.ARRIVE) {
            if (prog.distToStepM <= far && announced.add("${prog.stepIndex}:far") && prog.distToStepM > near + 20) {
                val d = ((prog.distToStepM / 50).roundToInt() * 50).coerceAtLeast(50)
                speak("Через $d метров, ${step.text}")
            } else if (prog.distToStepM <= near && announced.add("${prog.stepIndex}:near")) {
                speak(step.text)
            }
        }

        if (prog.offRouteM > s.offRouteLimitM) offCount++ else offCount = 0
        if (offCount >= 3 && !rerouting && System.currentTimeMillis() - lastReroute > 8000) {
            val dest = navDest ?: return
            rerouting = true
            lastReroute = System.currentTimeMillis()
            offCount = 0
            speak("Пересчёт маршрута")
            computeRoute(LatLng(l.latitude, l.longitude), dest, true) { r, _ ->
                rerouting = false
                if (r != null && navMode == NavMode.ACTIVE) {
                    session = NavSession(r, navProfile)
                    announced.clear()
                    drawRoute(r)
                }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private val DATA_FILES = listOf("georgia.mbtiles", "relief.mbtiles", "places.db", "routing.bin")
        private val GEORGIA = LatLngBounds.from(43.65, 46.8, 41.0, 40.0)

        private val POI_CATS: Map<String, List<String>> = linkedMapOf(
            "АЗС" to listOf("fuel", "charging_station"),
            "Аптеки" to listOf("pharmacy", "chemist"),
            "Больницы и врачи" to listOf("hospital", "clinic", "doctors", "dentist"),
            "Банки и банкоматы" to listOf("bank", "atm"),
            "Магазины" to listOf("shop", "supermarket", "convenience", "grocery", "bakery", "marketplace", "mall", "department_store"),
            "Еда и кафе" to listOf("restaurant", "cafe", "fast_food", "bar", "pub", "food_and_drink", "food_court"),
            "Ночлег" to listOf("lodging", "hotel", "hostel", "guest_house", "motel", "camp_site", "campsite",
                "caravan_site", "alpine_hut", "wilderness_hut", "chalet", "apartments"),
            "Вода и родники" to listOf("drinking_water", "spring", "water_point", "water_tap"),
            "Туалеты" to listOf("toilets"),
            "Парковки" to listOf("parking"),
            "Вокзалы и станции" to listOf("railway", "station", "halt", "subway", "tram_stop", "bus_station",
                "ferry_terminal", "harbor", "aerodrome", "airport", "aerialway"),
            "Остановки и такси" to listOf("bus", "bus_stop", "taxi"),
            "Достопримечательности" to listOf("attraction", "viewpoint", "museum", "monument", "castle", "ruins",
                "church", "monastery", "place_of_worship", "waterfall", "memorial", "archaeological_site", "information"),
            "Полиция и экстренные" to listOf("police", "fire_station", "emergency")
        )

        private val KIND_RU: Map<String, String> = mapOf(
            "city" to "город", "town" to "город/посёлок", "village" to "село", "hamlet" to "хутор",
            "suburb" to "район", "quarter" to "квартал", "neighbourhood" to "микрорайон",
            "isolated_dwelling" to "усадьба", "island" to "остров", "state" to "область",
            "province" to "область", "peak" to "вершина", "aerodrome" to "аэропорт", "water" to "водоём",
            "fuel" to "АЗС", "pharmacy" to "аптека", "hospital" to "больница", "restaurant" to "ресторан",
            "cafe" to "кафе", "hotel" to "отель", "guest_house" to "гостевой дом", "hostel" to "хостел",
            "camp_site" to "кемпинг", "bank" to "банк", "atm" to "банкомат", "parking" to "парковка",
            "viewpoint" to "смотровая", "museum" to "музей", "castle" to "замок", "monastery" to "монастырь",
            "place_of_worship" to "храм", "supermarket" to "супермаркет", "drinking_water" to "питьевая вода",
            "spring" to "родник", "toilets" to "туалет", "police" to "полиция",
            "station" to "ж/д вокзал, станция", "halt" to "ж/д платформа", "subway" to "метро",
            "bus_station" to "автовокзал", "bus_stop" to "остановка", "tram_stop" to "трамвайная остановка",
            "ferry_terminal" to "паромный терминал", "taxi" to "такси", "fire_station" to "пожарная часть",
            "clinic" to "клиника", "charging_station" to "зарядка EV",
            "bakery" to "пекарня", "convenience" to "магазин", "marketplace" to "рынок",
            "alpine_hut" to "горная хижина"
        )
    }
}
