package app.georgiamap

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
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
import android.widget.ImageView
import android.widget.LinearLayout
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
    private var hasSavedMapState = false
    private var style: Style? = null
    private var placeDb: PlaceDb? = null
    private var elevation: Elevation? = null
    private var tilesFile: File? = null

    private val ui = Handler(Looper.getMainLooper())
    private val errors = ArrayList<String>()
    private var searchResults: List<PlaceDb.Place> = emptyList()
    private var searchGeneration = 0
    private var searchTask: Runnable? = null
    private var elevationKey = ""
    private var elevationValue: Int? = null
    private var elevationGeneration = 0
    private var elevationTask: Runnable? = null
    private var waypointRenderGeneration = 0
    private var trackRenderGeneration = 0
    private var listLoadGeneration = 0
    private var diagnosticsGeneration = 0

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
    private var session: NavSession? = null
    private var routeFrom: LatLng? = null
    private var pendingFrom: LatLng? = null       // "route from here": the chosen place is the START
    private var pendingFromName = ""
    private var offCount = 0
    private var lastReroute = 0L
    private var rerouting = false
    private var routeRequestId = 0
    private val announced = HashSet<String>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var compass: CompassController
    private var myLoc: Location? = null
    private var waypointCache: List<UserDb.Wpt> = emptyList()
    private var lastNavFixAt = 0L
    private var navGpsLost = false
    private val navWatchdog = object : Runnable {
        override fun run() {
            if (navMode != NavMode.ACTIVE) return
            val now = System.currentTimeMillis()
            if (lastNavFixAt > 0L && now - lastNavFixAt > 15_000L && !navGpsLost) {
                navGpsLost = true
                findViewById<TextView>(R.id.navText).text = "Нет сигнала GPS. Ожидаю восстановления…"
                speak("Нет сигнала GPS. Ожидаю восстановления")
            }
            ui.postDelayed(this, 2_000L)
        }
    }
    private var following = false
    private var locActive = false
    private var locationRequestedByUser = false
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

    /** Seven colour themes. Each one has a UI overlay (res/values/palettes.xml) and its own map style file. */
    private enum class Palette(val key: String, val title: String, val hint: String, val styleRes: Int, val swatch: Int, val swatch2: Int) {
        OCEAN("ocean", "Океан", "спокойствие и ясность", R.style.ThemeOverlay_Georgia_Ocean, 0xFF2A7FA8.toInt(), 0xFF3FA6A0.toInt()),
        FOREST("forest", "Лес", "естественный покой", R.style.ThemeOverlay_Georgia_Forest, 0xFF3F7D58.toInt(), 0xFF7BA05B.toInt()),
        DUSK("dusk", "Закат", "тепло и уют", R.style.ThemeOverlay_Georgia_Dusk, 0xFFB2552F.toInt(), 0xFFD98A4E.toInt()),
        LAVENDER("lavender", "Лаванда", "мягкая расслабленность", R.style.ThemeOverlay_Georgia_Lavender, 0xFF6B5BC4.toInt(), 0xFF9A7BD8.toInt()),
        GRAPHITE("graphite", "Графит", "нейтральный фокус", R.style.ThemeOverlay_Georgia_Graphite, 0xFF3D4A5C.toInt(), 0xFF5E6B7E.toInt()),
        CONTRAST("contrast", "Контраст", "максимальная читаемость: чёрное и белое, жёлтый в тёмном", R.style.ThemeOverlay_Georgia_Contrast, 0xFF0B3DDB.toInt(), 0xFFFFD400.toInt()),
        NEON("neon", "Неон", "яркие акценты: пурпур днём, циан ночью", R.style.ThemeOverlay_Georgia_Neon, 0xFFD100B3.toInt(), 0xFF00E5FF.toInt())
    }

    private fun palette(): Palette {
        val k = getSharedPreferences("settings", MODE_PRIVATE).getString("palette", "ocean")
        return Palette.values().firstOrNull { it.key == k } ?: Palette.OCEAN
    }

    private fun nightFor(m: String): Boolean = when (m) {
        "dark", "twilight" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** One screen for mode (auto / light / twilight / dark) and colour theme; it reopens after each change so themes can be tried in a row. */
    private fun showAppearanceDialog() {
        val modes = listOf(
            ModeItem("auto", "Авто", "Следует настройке телефона: днём светлый, ночью тёмный."),
            ModeItem("light", "Светлый", "Светлый интерфейс и карта. Лучше всего читается на солнце."),
            ModeItem("twilight", "Сумерки", "Мягкий полутёмный режим между светлым и тёмным: меньше контраста, приятнее вечером и в помещении."),
            ModeItem("dark", "Тёмный", "Тёмный интерфейс и карта. Экономит батарею и не слепит ночью.")
        )
        val pals = Palette.values().map { PaletteItem(it.key, it.title, it.hint, it.swatch, it.swatch2) }
        AppearanceUi.show(this, themeMode(), palette().key, modes, pals,
            { m -> changeMode(m) },
            { k ->
                prefs.edit().putString("palette", k).putString("reopen", "appearance").apply()
                recreate()   // the UI overlay and the map style are rebuilt; the screen reopens afterwards
            })
    }

    private fun changeMode(m: String) {
        val before = nightFor(themeMode())
        prefs.edit().putString("theme_mode", m).putString("reopen", "appearance").apply()
        applyThemeMode(m)                      // recreates the activity when light <-> dark changes
        if (nightFor(m) == before) recreate()  // e.g. dark <-> twilight: same night state, so rebuild explicitly
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
                "dark", "twilight" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun isNight(): Boolean = nightFor(themeMode())

    override fun onCreate(savedInstanceState: Bundle?) {
        hasSavedMapState = savedInstanceState != null
        applyThemeMode(themeMode())
        super.onCreate(savedInstanceState)
        theme.applyStyle(palette().styleRes, true)   // colour theme, before any view is inflated
        if (themeMode() == "twilight") theme.applyStyle(R.style.ThemeOverlay_Georgia_Twilight, true)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        setupEdgeToEdge()

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        locationRequestedByUser = prefs.getBoolean("location_user_enabled", false)
        following = prefs.getBoolean("location_follow", locationRequestedByUser)
        locActive = locationRequestedByUser
        rulerOn = prefs.getBoolean("ruler_on", false)
        shownTrackId = prefs.getLong("shown_track", -1L)
        savedInstanceState?.getDoubleArray("ruler_points")?.let { points ->
            if (points.size % 2 == 0) for (i in points.indices step 2) rulerPts.add(LatLng(points[i], points[i + 1]))
        }
        userDb = UserDb(this)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        tvInfo = findViewById(R.id.tvInfo)
        etSearch = findViewById(R.id.etSearch)
        val restoredSearch = savedInstanceState?.getString("search_query").orEmpty()
        lvResults = findViewById(R.id.lvResults)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener { msg -> errors.add(msg ?: "?") }

        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<View>(R.id.btnPoint).setOnClickListener {
            val c = map?.cameraPosition?.target
            if (c != null) addWaypointDialog(c)
        }
        findViewById<View>(R.id.btnLocation).setOnClickListener {
            if (navMode == NavMode.ACTIVE) {
                following = true
                prefs.edit().putBoolean("location_follow", true).apply()
                myLoc?.let { map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), maxOf(map?.cameraPosition?.zoom ?: 14.0, 14.0)), 500) }
                refreshLocationButton()
            } else if (locationRequestedByUser) {
                locationRequestedByUser = false
                following = false
                prefs.edit().putBoolean("location_user_enabled", false).putBoolean("location_follow", false).apply()
                locActive = false
                stopLocationUpdates()
                myLoc = null
                refreshMe()
                updateInfo()
                refreshLocationButton()
            } else {
                withLocationPermission { startLocation(true) }
            }
        }
        findViewById<View>(R.id.btnFit).setOnClickListener {
            following = false
            prefs.edit().putBoolean("location_follow", false).apply()
            findViewById<View>(R.id.root).post {
                try {
                    val p = mapViewportPadding()
                    map?.easeCamera(CameraUpdateFactory.newLatLngBounds(GEORGIA, p[0], p[1], p[2], p[3]), 700)
                } catch (_: Exception) { }
            }
        }
        tvInfo.setOnClickListener {
            val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("info", tvInfo.text))
            toast("Скопировано")
        }

        setupChips()
        navProfile = prefs.getInt("nav_profile", Profile.CAR)
        findViewById<View>(R.id.btnRec).setOnClickListener { toggleRecording() }
        refreshRecButton()
        findViewById<View>(R.id.btnRouteTo).setOnClickListener {
            pendingFrom = null
            src("ovl-sel")?.setGeoJson(Geo.pointFc(emptyList()))
            planRoute()
        }
        findViewById<View>(R.id.btnRouteFrom).setOnClickListener { routeFromHere() }
        compass = CompassController(this, object : CompassController.Host {
            override fun origin(): CompassController.Origin? {
                myLoc?.let { return CompassController.Origin(it.latitude, it.longitude, if (it.hasAltitude()) it.altitude else 0.0, "GPS") }
                val c = map?.cameraPosition?.target ?: return null
                return CompassController.Origin(c.latitude, c.longitude, 0.0, "центр карты (GPS нет)")
            }
            override fun mapCenter() = map?.cameraPosition?.target?.let { CompassController.Target("Центр карты", it.latitude, it.longitude) }
            override fun searchPlaces(q: String, callback: (List<CompassController.Target>) -> Unit) {
                val db = placeDb
                if (db == null) { callback(emptyList()); return }
                thread(name = "compass-search") {
                    val result = try {
                        db.search(q, 20).map { p ->
                            CompassController.Target(listOf(p.ru, p.en, p.ka).firstOrNull { it.isNotEmpty() } ?: q, p.lat, p.lon)
                        }
                    } catch (_: Exception) { emptyList() }
                    runOnUiThread { if (!isFinishing && !isDestroyed) callback(result) }
                }
            }
            override fun waypoints(callback: (List<CompassController.Target>) -> Unit) {
                thread(name = "compass-waypoints") {
                    val result = try {
                        userDb.waypoints().map { CompassController.Target(it.name, it.lat, it.lon) }
                    } catch (_: Exception) { emptyList() }
                    runOnUiThread { if (!isFinishing && !isDestroyed) callback(result) }
                }
            }
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
                NavMode.PREVIEW -> if (pendingFrom != null) stopNav() else startNav()
                NavMode.ACTIVE -> stopNav()
                NavMode.IDLE -> {}
            }
        }
        findViewById<View>(R.id.navProfCar).setOnClickListener { chooseProfile(Profile.CAR) }
        findViewById<View>(R.id.navProfFoot).setOnClickListener { chooseProfile(Profile.FOOT) }
        findViewById<View>(R.id.navProfBike).setOnClickListener { chooseProfile(Profile.BIKE) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    navMode != NavMode.IDLE -> stopNav()
                    lvResults.visibility == View.VISIBLE || etSearch.text?.isNotEmpty() == true -> {
                        etSearch.setText("")
                        etSearch.clearFocus()
                        showResults(false)
                    }
                    rulerOn -> {
                        rulerOn = false
                        prefs.edit().putBoolean("ruler_on", false).apply()
                        rulerPts.clear()
                        refreshRuler()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
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
            if (restoredSearch.isNotEmpty()) etSearch.setText(restoredSearch)
            mapView.getMapAsync { setupMap(it) }
        }
    }

    private fun reopenPending() {
        val what = prefs.getString("reopen", "") ?: ""
        if (what.isEmpty()) return
        prefs.edit().remove("reopen").apply()
        ui.post { if (what == "appearance") showAppearanceDialog() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        resizeSearchResults()
        showResults(lvResults.visibility == View.VISIBLE)
        findViewById<View>(R.id.root).post {
            updateControlsMargin()
            applyMapUiMargins()
        }
    }

    override fun onStart() { super.onStart(); reopenPending(); refreshRecButton(); refreshLocationButton(); mapView.onStart(); compass.onStart(); if (locActive) startLocation(false) }
    override fun onResume() { super.onResume(); mapView.onResume(); scheduleTick(); if (navMode == NavMode.ACTIVE && !navGpsLost) lastNavFixAt = System.currentTimeMillis(); startNavWatchdog() }
    override fun onPause() { stopNavWatchdog(); mapView.onPause(); ui.removeCallbacks(tick); super.onPause() }
    override fun onStop() { stopNavWatchdog(); stopLocationUpdates(); compass.onStop(); mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() {
        stopNavWatchdog()
        routeRequestId++
        searchGeneration++
        elevationGeneration++
        waypointRenderGeneration++
        trackRenderGeneration++
        listLoadGeneration++
        diagnosticsGeneration++
        searchTask?.let { ui.removeCallbacks(it) }
        elevationTask?.let { ui.removeCallbacks(it) }
        tts?.shutdown()
        try { placeDb?.close() } catch (_: Exception) { }
        try { elevation?.close() } catch (_: Exception) { }
        mapView.onDestroy()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("search_query", etSearch.text?.toString() ?: "")
        outState.putDoubleArray("ruler_points", rulerPts.flatMap { listOf(it.latitude, it.longitude) }.toDoubleArray())
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
        // MapLibre's own compass/attribution controls must move with the actual
        // floating panels, including expanded search results and navigation mode.
        listOf(R.id.topPanel, R.id.navBanner, R.id.bottomDock, R.id.navBar).forEach { id ->
            findViewById<View>(id).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateControlsMargin()
                applyMapUiMargins()
            }
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** The control capsule sits 16 dp above whatever panel is at the bottom (dock or navigator bar). */
    private fun updateControlsMargin() {
        val controls = findViewById<View>(R.id.controls)
        val lp = controls.layoutParams as FrameLayout.LayoutParams
        if (resources.configuration.screenHeightDp < 500) {
            val topPanel = findViewById<View>(R.id.topPanel)
            val navBanner = findViewById<View>(R.id.navBanner)
            val panel = if (navBanner.visibility == View.VISIBLE) navBanner else topPanel
            val wantTop = insetTop + dp(16f) + panel.height + dp(16f)
            val wantGravity = android.view.Gravity.END or android.view.Gravity.TOP
            if (lp.gravity != wantGravity || lp.topMargin != wantTop || lp.bottomMargin != 0) {
                lp.gravity = wantGravity
                lp.topMargin = wantTop
                lp.bottomMargin = 0
                controls.layoutParams = lp
            }
        } else {
            val navBar = findViewById<View>(R.id.navBar)
            val panelHeight = if (navBar.visibility == View.VISIBLE) maxOf(navBar.height, dp(64f)) else dp(64f)
            val wantBottom = insetBottom + dp(16f) + panelHeight + dp(16f)
            val wantGravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
            if (lp.gravity != wantGravity || lp.topMargin != 0 || lp.bottomMargin != wantBottom) {
                lp.gravity = wantGravity
                lp.topMargin = 0
                lp.bottomMargin = wantBottom
                controls.layoutParams = lp
            }
        }
    }

    /** Keep MapLibre's built-in controls clear of the panels in every screen state. */
    private fun applyMapUiMargins() {
        val topPanel = findViewById<View>(R.id.topPanel)
        val navBanner = findViewById<View>(R.id.navBanner)
        val topHeight = listOf(topPanel, navBanner)
            .filter { it.visibility == View.VISIBLE }
            .maxOfOrNull { it.height } ?: 0
        val topMargin = insetTop + dp(16f) + topHeight + dp(8f)

        val navBar = findViewById<View>(R.id.navBar)
        val bottomPanel = if (navBar.visibility == View.VISIBLE) navBar else findViewById(R.id.bottomDock)
        val bottomMargin = insetBottom + dp(16f) + bottomPanel.height + dp(16f)

        val ui = map?.uiSettings ?: return
        ui.setCompassEnabled(resources.configuration.screenHeightDp >= 500)
        ui.setAttributionMargins(dp(16f), 0, 0, bottomMargin)
        ui.setCompassMargins(0, topMargin, dp(16f), 0)
    }

    // ============================================================ data (assets -> files)

    private fun prepareData(onReady: () -> Unit) {
        val have = assets.list("")?.toSet() ?: emptySet()
        if ("georgia.mbtiles" !in have) {
            loading.visibility = View.VISIBLE
            loadingText.text = "В APK нет georgia.mbtiles — сборка карты не выполнилась"
            return
        }

        val versions = try {
            assets.open("map-data-manifest.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.mapNotNull { line ->
                    val split = line.split("=", limit = 2)
                    if (split.size == 2 && split[0].isNotBlank() && split[1].isNotBlank())
                        split[0] to split[1] else null
                }.toMap()
            }
        } catch (_: Exception) { emptyMap() }

        fun assetVersion(name: String) = versions[name] ?: BuildConfig.MAP_DATA_VERSION
        fun assetLength(name: String) = assets.openFd(name).use { it.length }

        val removed = DATA_FILES.filter { it !in have }
        for (name in removed) {
            File(filesDir, name).delete()
            prefs.edit().remove("asset_version_$name").apply()
        }

        val todo = DATA_FILES.filter { name ->
            if (name !in have) false
            else {
                val target = File(filesDir, name)
                !target.isFile || target.length() != assetLength(name) ||
                    prefs.getString("asset_version_$name", "") != assetVersion(name)
            }
        }
        if (todo.isEmpty()) { onReady(); return }

        loading.visibility = View.VISIBLE
        thread(name = "data-copy") {
            try {
                for (name in todo) {
                    try {
                        copyAsset(name)
                        prefs.edit().putString("asset_version_$name", assetVersion(name)).apply()
                    } catch (e: Exception) {
                        File(filesDir, "$name.tmp").delete()
                        File(filesDir, name).delete()
                        prefs.edit().remove("asset_version_$name").apply()
                        if (name == "georgia.mbtiles") throw e
                        errors.add("$name: $e")
                    }
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        loading.visibility = View.GONE
                        onReady()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) loadingText.text = "Ошибка карты: ${e.message}"
                }
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
                    val bucket = mb / 8
                    if (bucket != last) {
                        last = bucket
                        val t = if (total > 0) "$mb / ${total shr 20} МБ" else "$mb МБ"
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed)
                                loadingText.text = getString(R.string.loading) + "\n$name: $t"
                        }
                    }
                }
            }
        }
        val copied = tmp.length()
        if (total > 0 && copied != total) {
            tmp.delete()
            error("неполное копирование $name: $copied из $total байт")
        }
        val target = File(filesDir, name)
        if (target.exists() && !target.delete()) error("cannot replace: $name")
        if (!tmp.renameTo(target)) error("rename failed: $name")
    }

    // ============================================================ map & style

    private fun setupMap(m: MapLibreMap) {
        map = m
        m.uiSettings.isLogoEnabled = false
        applyMapUiMargins()
        m.setMinZoomPreference(4.0)
        m.setMaxZoomPreference(18.0)
        if (!hasSavedMapState) {
            m.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(42.2, 43.5)).zoom(6.6).build()
                )
            )
        }
        if (following) myLoc?.let {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), maxOf(m.cameraPosition.zoom, 14.0)))
        }
        m.addOnCameraIdleListener { updateInfo() }
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                following = false
                prefs.edit().putBoolean("location_follow", false).apply()
            }
        }
        m.addOnMapLongClickListener { ll ->
            val activeNavigation = navMode == NavMode.ACTIVE
            val items = if (activeNavigation) arrayOf("Сохранить точку")
                else arrayOf("Маршрут сюда", "Сохранить точку")
            MaterialAlertDialogBuilder(this).setTitle(Geo.fmtCoord(ll.latitude, ll.longitude))
                .setItems(items) { _, i ->
                    if (!activeNavigation && i == 0) setDestination(ll, Geo.fmtCoord(ll.latitude, ll.longitude))
                    else addWaypointDialog(ll)
                }.show()
            true
        }
        m.addOnMapClickListener { ll ->
            if (rulerOn && navMode == NavMode.IDLE) { rulerPts.add(ll); refreshRuler(); true }
            else {
                val w = if (navMode == NavMode.ACTIVE) null else nearestWaypoint(m, ll)
                if (w != null) { pendingFrom = null; setDestination(LatLng(w.lat, w.lon), w.name); true } else false
            }
        }
        loadStyle()
    }

    private fun loadStyle() {
        val m = map ?: return
        val tiles = tilesFile ?: return
        val name = "style_${palette().key}" + (if (isNight()) "_dark" else "") + ".json"
        val relief = File(filesDir, "relief.mbtiles")
        val contours = File(filesDir, "contours.mbtiles")
        val obj = JSONObject(assets.open(name).bufferedReader().use { it.readText() })
        // optional data: without the file its source and layers are dropped, so the style still loads
        if (!relief.exists()) dropFromStyle(obj, listOf("relief")) { it == "relief-hillshade" }
        if (!contours.exists()) dropFromStyle(obj, listOf("contours", "contours-labels")) { it.startsWith("contour-") }
        val json = obj.toString()
            .replace("__MBTILES_URL__", "mbtiles://" + tiles.absolutePath)
            .replace("__RELIEF_URL__", "mbtiles://" + relief.absolutePath)
            .replace("__CONTOURS_URL__", "mbtiles://" + contours.absolutePath)
        val builder = Style.Builder().fromJson(json)
        for ((id, bmp) in markImages()) builder.withImage(id, bmp)
        m.setStyle(builder) { s ->
            style = s
            addOverlays(s)
            applyLabels(s)
            applyPoiFilter(s)
            applyRelief(s)
            applyHeights(s)
            refreshWaypoints()
            refreshTrack()
            refreshRuler()
            refreshMe()
            updateInfo()
        }
    }

    private fun dropFromStyle(obj: JSONObject, sources: List<String>, layerId: (String) -> Boolean) {
        for (n in sources) obj.getJSONObject("sources").remove(n)
        val src = obj.getJSONArray("layers")
        val dst = JSONArray()
        for (i in 0 until src.length()) {
            val l = src.getJSONObject(i)
            if (!layerId(l.optString("id")) && l.optString("source") !in sources) dst.put(l)
        }
        obj.put("layers", dst)
    }

    // ---- map icons (res/drawable/ic_cat_*.xml -> bitmaps for the style; made by tools/gen_icons.py)

    private var markBitmaps: Map<String, Bitmap>? = null

    /** 32 dp round badges. The bitmap density is a whole multiple of 160 so MapLibre's pixel ratio is exact. */
    private fun markImages(): Map<String, Bitmap> {
        markBitmaps?.let { return it }
        val r = Math.ceil(resources.displayMetrics.density.toDouble()).toInt().coerceAtLeast(1)
        val px = 32 * r
        val out = LinkedHashMap<String, Bitmap>()
        for (name in PoiCats.IMAGES) {
            val id = resources.getIdentifier(name, "drawable", packageName)
            if (id == 0) continue
            val d = ContextCompat.getDrawable(this, id) ?: continue
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            bmp.density = 160 * r
            d.setBounds(0, 0, px, px)
            d.draw(Canvas(bmp))
            out[name] = bmp
        }
        markBitmaps = out
        return out
    }

    private fun enabledLanguages(): List<Lang> = Lang.values().filter { prefs.getBoolean(it.prefKey, true) }

    /** Label layers that carry an icon and whose visibility is decided by the chips / the "Высоты" layer. */
    private fun isMarkLayer(id: String) =
        id == "lbl-poi" || id == "lbl-peak" || id.startsWith("lbl-aerodrome") || id.startsWith("lbl-station-")

    private fun applyLabels(s: Style) {
        val langs = enabledLanguages()
        val simple = prefs.getBoolean("labels_simple", false)
        for (layer in s.layers) {
            if (layer is SymbolLayer && layer.id.startsWith("lbl-")) {
                val mark = isMarkLayer(layer.id)
                if (simple) {
                    layer.setProperties(PropertyFactory.textField(Expression.raw("[\"to-string\",[\"get\",\"name\"]]")))
                    if (!mark) layer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
                } else if (langs.isEmpty()) {
                    // no language: names are off, but the icons of the marks stay
                    if (mark) layer.setProperties(PropertyFactory.textField(""))
                    else layer.setProperties(PropertyFactory.visibility(Property.NONE))
                } else {
                    layer.setProperties(PropertyFactory.textField(LabelExpr.build(langs, layer.id)))
                    if (!mark) layer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
                }
            }
        }
        applyMarkVisibility(s)
    }

    private fun applyRelief(s: Style) {
        val on = prefs.getBoolean("relief_on", true)
        s.getLayer("relief-hillshade")?.setProperties(
            PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
        )
    }

    // ---- "Высоты" layer: contour lines + their numbers + peak marks with elevations (like the relief switch)

    private fun heightsOn() = prefs.getBoolean("heights_on", true)

    private fun applyHeights(s: Style) {
        val vis = PropertyFactory.visibility(if (heightsOn()) Property.VISIBLE else Property.NONE)
        for (id in listOf("contour-minor-lo", "contour-minor-hi", "contour-major", "contour-label")) {
            s.getLayer(id)?.setProperties(vis)
        }
        applyMarkVisibility(s)
    }

    // ---- categories (chips on the main screen)

    private fun selectedCats(): Set<String> = prefs.getStringSet("poi_cats", emptySet()) ?: emptySet()

    /** Nothing chosen = everything is shown; otherwise only the chosen categories (airports and stations included). */
    private fun applyMarkVisibility(s: Style) {
        val sel = selectedCats()
        fun vis(on: Boolean) = PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
        s.getLayer("lbl-peak")?.setProperties(vis(heightsOn()))
        val airports = sel.isEmpty() || PoiCats.AIRPORTS in sel
        s.getLayer("lbl-aerodrome")?.setProperties(vis(airports))
        s.getLayer("lbl-aerodrome-minor")?.setProperties(vis(airports))
        val stations = sel.isEmpty() || PoiCats.STATIONS in sel
        for (l in s.layers) if (l.id.startsWith("lbl-station-")) l.setProperties(vis(stations))
        s.getLayer("lbl-poi")?.setProperties(vis(true))
    }

    private fun applyPoiFilter(s: Style) {
        val sel = selectedCats()
        val layer = s.getLayerAs<SymbolLayer>("lbl-poi") ?: return
        val f: String
        val minZ: Float
        if (sel.isEmpty()) {
            f = "['has','name']"
            minZ = 14f
        } else {
            f = "['all',['has','name']," + PoiCats.anyOf(PoiCats.ALL.filter { it.title in sel }) + "]"
            minZ = 12f
        }
        layer.setFilter(Expression.raw(f.replace('\'', '"')))
        layer.setMinZoom(minZ)
        layer.setProperties(PropertyFactory.iconImage(Expression.raw(PoiCats.iconExpression().replace('\'', '"'))))
        applyMarkVisibility(s)
    }

    private fun setupChips() {
        val row = findViewById<LinearLayout>(R.id.chipsRow)
        row.removeAllViews()
        fun chip(icon: String?, title: String, onClick: () -> Unit): View {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(if (icon != null) 6f else 14f), 0, dp(14f), 0)
                setOnClickListener { onClick() }
            }
            if (icon != null) {
                val id = resources.getIdentifier(icon, "drawable", packageName)
                c.addView(ImageView(this).apply { setImageResource(id); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    LinearLayout.LayoutParams(dp(28f), dp(28f)))
            }
            val tv = TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_Georgia_Label)
                setTextColor(com.google.android.material.color.MaterialColors.getColor(this, R.attr.gmTextPrimary, 0xFF000000.toInt()))
                text = title
                maxLines = 1
            }
            c.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (icon != null) marginStart = dp(6f)
            })
            return c
        }
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(8f) } }
        val reset = chip(null, "✕  Все") { prefs.edit().putStringSet("poi_cats", HashSet()).apply(); onCatsChanged() }
        reset.tag = "reset"
        row.addView(reset, lp())
        for (cat in PoiCats.ALL) {
            val v = chip(cat.icon, cat.title) { toggleCat(cat) }
            v.tag = cat.title
            row.addView(v, lp())
        }
        refreshChips()
    }

    private fun refreshChips() {
        val row = findViewById<LinearLayout>(R.id.chipsRow)
        val sel = selectedCats()
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            val t = (v.tag as? String) ?: ""
            if (t == "reset") { v.visibility = if (sel.isEmpty()) View.GONE else View.VISIBLE; v.setBackgroundResource(R.drawable.bg_chip_map); continue }
            v.setBackgroundResource(if (t in sel) R.drawable.bg_chip_map_selected else R.drawable.bg_chip_map)
        }
    }

    private fun onCatsChanged() {
        refreshChips()
        style?.let { applyPoiFilter(it) }
    }

    private fun toggleCat(cat: PoiCat) {
        val cur = selectedCats().toMutableSet()
        val nowOn = if (cat.title in cur) { cur.remove(cat.title); false } else { cur.add(cat.title); true }
        prefs.edit().putStringSet("poi_cats", HashSet(cur)).apply()
        onCatsChanged()
        val z = map?.cameraPosition?.zoom ?: 99.0
        if (nowOn && z < cat.minZoom - 0.5) toast("«${cat.title}»: значки видны с масштаба ${cat.minZoom}, приблизьте карту")
    }

    /** The search list and the chips share the same place under the search pill. */
    private fun resizeSearchResults() {
        val count = searchResults.size
        if (count == 0) return
        val dp = resources.displayMetrics.density
        val maxResultsDp = minOf(280, (resources.configuration.screenHeightDp - 240).coerceAtLeast(80))
        lvResults.layoutParams = lvResults.layoutParams.apply {
            height = minOf((count * 64 * dp).roundToInt(), (maxResultsDp * dp).roundToInt())
        }
    }

    private fun showResults(show: Boolean) {
        lvResults.visibility = if (show) View.VISIBLE else View.GONE
        val showFilters = navMode == NavMode.IDLE && !show
        findViewById<View>(R.id.chipsScroll).visibility = if (showFilters) View.VISIBLE else View.GONE
        tvInfo.visibility = if (showFilters && resources.configuration.screenHeightDp >= 500) View.VISIBLE else View.GONE
        updateFloatingControls()
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
        s.addSource(GeoJsonSource("ovl-rec-track"))
        s.addLayer(LineLayer("ovl-rec-track-l", "ovl-rec-track").withProperties(
            PropertyFactory.lineColor("#e53935"), PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))

        s.addSource(GeoJsonSource("ovl-route"))
        s.addLayer(LineLayer("ovl-route-case", "ovl-route").withProperties(
            PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(10f), PropertyFactory.lineOpacity(0.9f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        s.addLayer(LineLayer("ovl-route-l", "ovl-route").withProperties(
            PropertyFactory.lineColor(themeHex(R.attr.gmAccent)), PropertyFactory.lineWidth(6.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        s.addSource(GeoJsonSource("ovl-sel"))
        s.addLayer(CircleLayer("ovl-sel-c", "ovl-sel").withProperties(
            PropertyFactory.circleRadius(10f), PropertyFactory.circleColor("#ffffff"),
            PropertyFactory.circleStrokeColor(themeHex(R.attr.gmAccent)), PropertyFactory.circleStrokeWidth(4f)))
        s.addSource(GeoJsonSource("ovl-dest"))
        s.addLayer(CircleLayer("ovl-dest-halo", "ovl-dest").withProperties(
            PropertyFactory.circleRadius(20f), PropertyFactory.circleColor("#00000000"),
            PropertyFactory.circleStrokeColor(themeHex(R.attr.gmAccent)), PropertyFactory.circleStrokeWidth(4f)))
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

    /** The saved point under the finger (within ~36 dp on screen), if any. */
    private fun nearestWaypoint(m: MapLibreMap, ll: LatLng): UserDb.Wpt? {
        val tap = m.projection.toScreenLocation(ll)
        var best: UserDb.Wpt? = null
        var bestD = dp(36f).toFloat()
        for (w in waypointCache) {
            val q = m.projection.toScreenLocation(LatLng(w.lat, w.lon))
            val d = Math.hypot((q.x - tap.x).toDouble(), (q.y - tap.y).toDouble()).toFloat()
            if (d < bestD) { bestD = d; best = w }
        }
        return best
    }

    private fun themeColorInt(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun themeHex(attr: Int): String = String.format(Locale.US, "#%06X", themeColorInt(attr) and 0xFFFFFF)

    private fun src(id: String): GeoJsonSource? = style?.getSourceAs<GeoJsonSource>(id)

    private fun refreshWaypoints() {
        val generation = ++waypointRenderGeneration
        thread(name = "waypoint-render") {
            val points = try { userDb.waypoints() } catch (_: Exception) { emptyList() }
            val geo = Geo.pointFc(points.map { LatLng(it.lat, it.lon) }, points.map { it.name })
            runOnUiThread {
                if (generation == waypointRenderGeneration && !isFinishing && !isDestroyed) {
                    waypointCache = points
                    src("ovl-wpt")?.setGeoJson(geo)
                }
            }
        }
    }

    private fun refreshTrack() {
        val recordingId = if (TrackService.running) prefs.getLong("rec_track", 0L) else 0L
        val selectedId = shownTrackId.takeIf { it > 0L && it != recordingId } ?: 0L
        val generation = ++trackRenderGeneration
        thread(name = "track-render") {
            fun lines(id: Long) = if (id > 0L) userDb.trackSegments(id).map { segment ->
                TrackGeometry.simplify(
                    segment.map { TrackGeometry.Point(it.lat, it.lon) },
                    toleranceMeters = 5.0
                ).map { LatLng(it.lat, it.lon) }
            } else emptyList()
            val selected = try { lines(selectedId) } catch (_: Exception) { emptyList() }
            val recording = try { lines(recordingId) } catch (_: Exception) { emptyList() }
            val selectedGeo = Geo.linesFc(selected)
            val recordingGeo = Geo.linesFc(recording)
            runOnUiThread {
                if (generation == trackRenderGeneration && !isFinishing && !isDestroyed) {
                    src("ovl-track")?.setGeoJson(selectedGeo)
                    src("ovl-rec-track")?.setGeoJson(recordingGeo)
                }
            }
        }
    }

    private fun refreshRuler() {
        val visible = rulerOn && navMode == NavMode.IDLE
        val points = if (visible) rulerPts else emptyList()
        src("ovl-ruler")?.setGeoJson(Geo.lineFc(points))
        src("ovl-ruler-pts")?.setGeoJson(Geo.pointFc(points))
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
            } else {
                val id = prefs.getLong("rec_track", 0L)
                val started = prefs.getLong("rec_started_at", 0L)
                if (id > 0L && System.currentTimeMillis() - started > 10_000L) {
                    prefs.edit().putLong("rec_track", 0L).putLong("rec_started_at", 0L).apply()
                    shownTrackId = id
                    prefs.edit().putLong("shown_track", id).apply()
                    refreshTrack()
                    updateInfo()
                    refreshRecButton()
                    toast("Служба записи остановилась. Сохранённый трек оставлен на телефоне.")
                } else if (id > 0L) {
                    ui.postDelayed(this, 1000)
                }
            }
        }
    }

    private fun scheduleTick() {
        ui.removeCallbacks(tick)
        if (TrackService.running || prefs.getLong("rec_track", 0L) > 0L) ui.postDelayed(tick, 1000)
    }

    private fun requestElevation(lat: Double, lon: Double) {
        val db = elevation ?: return
        val key = String.format(Locale.US, "%.5f/%.5f", lat, lon)
        if (key == elevationKey) return
        elevationKey = key
        elevationValue = null
        val generation = ++elevationGeneration
        elevationTask?.let { ui.removeCallbacks(it) }
        val task = Runnable {
            thread(name = "elevation-read") {
                val value = try { db.at(lat, lon) } catch (_: Exception) { null }
                runOnUiThread {
                    if (generation == elevationGeneration && !isFinishing && !isDestroyed) {
                        elevationValue = value
                        updateInfo()
                    }
                }
            }
        }
        elevationTask = task
        ui.postDelayed(task, 100)
    }

    private fun updateInfo() {
        val m = map ?: return
        val c = m.cameraPosition.target ?: return
        requestElevation(c.latitude, c.longitude)
        val sb = StringBuilder(Geo.fmtCoord(c.latitude, c.longitude))
        elevationValue?.let { sb.append("  ·  ≈ $it м") }
        sb.append("  ·  z").append(String.format(Locale.US, "%.1f", m.cameraPosition.zoom))
        myLoc?.let {
            sb.append("\nGPS: ±").append(it.accuracy.roundToInt()).append(" м")
            if (it.hasAltitude()) sb.append(", высота ").append(it.altitude.roundToInt()).append(" м")
        } ?: run { if (locActive) sb.append("\nGPS: ожидание сигнала…") }
        if (rulerOn && navMode == NavMode.IDLE) sb.append("\nЛиния: ").append(Geo.fmtDist(Geo.length(rulerPts)))
            .append(" (тап — новая точка)")
        if (TrackService.running) sb.append("\n● REC трек пишется")
        tvInfo.text = sb.toString()
    }

    // ============================================================ search

    private fun runSearch(q: String) {
        searchTask?.let { ui.removeCallbacks(it) }
        val generation = ++searchGeneration
        val query = q.trim()
        if (query.length < 2) {
            searchResults = emptyList()
            showResults(false)
            return
        }
        val db = placeDb
        if (db == null) {
            toast("Индекс поиска не найден в APK")
            showResults(false)
            return
        }
        val task = Runnable {
            thread(name = "place-search") {
                val result = try { db.search(query) to null } catch (e: Exception) { emptyList<PlaceDb.Place>() to e }
                runOnUiThread {
                    if (generation != searchGeneration || isFinishing || isDestroyed) return@runOnUiThread
                    val (found, error) = result
                    if (error != null) {
                        searchResults = emptyList()
                        showResults(false)
                        toast("Ошибка поиска: ${error.message}")
                    } else {
                        searchResults = found
                        val items = found.map { p ->
                            val names = listOf(p.ka, p.ru, p.en).filter { it.isNotEmpty() }.distinct().joinToString(" / ")
                            val extra = kindLabel(p.kind) + if (p.ele > 0) " · ${p.ele} м" else ""
                            names to extra
                        }
                        lvResults.adapter = ResultsAdapter(items)
                        resizeSearchResults()
                        showResults(items.isNotEmpty())
                    }
                }
            }
        }
        searchTask = task
        ui.postDelayed(task, 250)
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
        searchTask?.let { ui.removeCallbacks(it) }
        searchTask = null
        searchGeneration++
        val ll = LatLng(p.lat, p.lon)
        src("ovl-search")?.setGeoJson(Geo.pointFc(listOf(ll)))
        val z = when (p.kind) {
            "city" -> 11.5; "town" -> 12.5; "peak" -> 13.0
            "village", "hamlet", "suburb" -> 13.5
            else -> 15.5
        }
        map?.easeCamera(CameraUpdateFactory.newLatLngZoom(ll, z), 900)
        setDestination(ll, listOf(p.ru, p.en, p.ka).firstOrNull { it.isNotEmpty() } ?: Geo.fmtCoord(p.lat, p.lon))
        showResults(false)
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
            val now = System.currentTimeMillis()
            val current = myLoc
            val candidateAccuracy = l.accuracy.takeIf { it.isFinite() && it > 0f } ?: Float.POSITIVE_INFINITY
            val currentAccuracy = current?.accuracy?.takeIf { it.isFinite() && it > 0f } ?: Float.POSITIVE_INFINITY
            if (!LocationQuality.shouldAccept(l.time, candidateAccuracy, current?.time ?: 0L, currentAccuracy, now)) return
            myLoc = l
            refreshMe()
            if (navMode == NavMode.ACTIVE && LocationQuality.isUsableForNavigation(l.time, candidateAccuracy, now, navProfile)) {
                lastNavFixAt = now
                if (navGpsLost) { navGpsLost = false; speak("Сигнал GPS восстановлен") }
                onNavLocation(l)
            }
            else if (navMode != NavMode.ACTIVE && following && map != null) {
                val z = maxOf(map!!.cameraPosition.zoom, 14.0)
                map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(l.latitude, l.longitude), z), 600)
            }
            updateInfo()
        }

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val anyEnabled = try {
                val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) ||
                    ((fine || coarse) && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            } catch (_: Exception) { false }
            if (!anyEnabled) {
                if (navMode == NavMode.ACTIVE) stopNav()
                locationRequestedByUser = false
                locActive = false
                following = false
                prefs.edit().putBoolean("location_user_enabled", false).putBoolean("location_follow", false).apply()
                stopLocationUpdates()
                myLoc = null
                refreshMe()
                refreshLocationButton()
                updateInfo()
                toast("Геолокация отключена")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation(userRequested: Boolean) {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val finePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarsePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val gps = finePermission && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val net = (finePermission || coarsePermission) && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!gps && !net) {
            locActive = false
            locationRequestedByUser = false
            following = false
            prefs.edit().putBoolean("location_user_enabled", false).putBoolean("location_follow", false).apply()
            if (navMode == NavMode.ACTIVE) stopNav()
            myLoc = null
            refreshMe()
            refreshLocationButton()
            updateInfo()
            MaterialAlertDialogBuilder(this)
                .setMessage("Геолокация выключена. Включите GPS в настройках телефона.")
                .setPositiveButton("Настройки") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Отмена", null).show()
            return
        }
        locActive = true
        if (userRequested) {
            locationRequestedByUser = true
            following = true
            prefs.edit().putBoolean("location_user_enabled", true).putBoolean("location_follow", true).apply()
            refreshLocationButton()
            myLoc?.let {
                map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.0), 600)
            }
        }
        var last: Location? = null
        try {
            lm.removeUpdates(locListener)
            if (gps) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            if (net) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, locListener, Looper.getMainLooper())
            last = listOfNotNull(
                if (gps) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null,
                if (net) lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null,
                lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            ).maxByOrNull { it.time }
        } catch (e: SecurityException) {
            locActive = false
            locationRequestedByUser = false
            following = false
            prefs.edit().putBoolean("location_user_enabled", false).putBoolean("location_follow", false).apply()
            stopLocationUpdates()
            if (navMode == NavMode.ACTIVE) stopNav()
            myLoc = null
            refreshMe()
            refreshLocationButton()
            updateInfo()
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
        val hasContours = File(filesDir, "contours.mbtiles").exists()
        val recordBusy = TrackService.running || prefs.getLong("rec_track", 0L) > 0L
        val modeName = when (themeMode()) { "light" -> "светлый"; "dark" -> "тёмный"; "twilight" -> "сумерки"; else -> "авто" }
        MenuUi.show(
            this, "Меню",
            listOf(
                MenuTile(R.drawable.ic_navigation, "Навигатор", navMode != NavMode.IDLE) { openNavigatorChooser() },
                MenuTile(R.drawable.ic_compass, "Компас", false) { compass.show(CompassController.Mode.NORTH) },
                MenuTile(R.drawable.ic_qibla, "Кибла", false) { compass.show(CompassController.Mode.QIBLA) },
                MenuTile(R.drawable.ic_bookmark, "Мои точки", false) { showWaypoints() },
                MenuTile(R.drawable.ic_straighten, "Линейка", rulerOn) {
                    rulerOn = !rulerOn
                    prefs.edit().putBoolean("ruler_on", rulerOn).apply()
                    if (!rulerOn) rulerPts.clear()
                    refreshRuler()
                }
            ),
            listOf(
                MenuSection("Карта", listOf(
                    MenuRow.Link("Языки названий", langs) { showLanguageDialog() },
                    MenuRow.Link("Перейти к координатам") { gotoCoordinatesDialog() },
                    MenuRow.Toggle("Рельеф", if (hasRelief) "Тени гор на карте" else "Нет данных в APK",
                        prefs.getBoolean("relief_on", true) && hasRelief, hasRelief) { on ->
                        prefs.edit().putBoolean("relief_on", on).apply()
                        style?.let { applyRelief(it) }
                    },
                    MenuRow.Toggle("Высоты", if (hasContours) "Линии высот, их подписи и вершины с отметками" else "Вершины с отметками (линий высот в APK нет)",
                        heightsOn()) { on ->
                        prefs.edit().putBoolean("heights_on", on).apply()
                        style?.let { applyHeights(it) }
                    }
                )),
                MenuSection("Оформление", listOf(
                    MenuRow.Link("Оформление", palette().title + " · " + modeName) { showAppearanceDialog() }
                )),
                MenuSection("Навигация", listOf(
                    MenuRow.Toggle("Голосовые подсказки", "Нужен русский голос в системе",
                        prefs.getBoolean("nav_voice", true)) { on -> prefs.edit().putBoolean("nav_voice", on).apply() },
                    MenuRow.Link("Транспорт по умолчанию", profileName(navProfile)) { showNavProfileDialog() }
                )),
                MenuSection("Мои данные", listOf(
                    MenuRow.Toggle("Кнопка записи трека",
                        if (recordBusy) "Остановите запись, чтобы скрыть кнопку" else "Показывать кнопку на главном экране",
                        prefs.getBoolean("rec_button", true), !recordBusy) { on ->
                        prefs.edit().putBoolean("rec_button", on).apply()
                        refreshRecButton()
                    },
                    MenuRow.Link("Мои треки") { showTracks() },
                    MenuRow.Link("Экспорт GPX") {
                        if (TrackService.running || prefs.getLong("rec_track", 0L) > 0L) {
                            toast("Остановите запись перед экспортом GPX, чтобы сохранить все точки")
                        } else {
                            exportLauncher.launch("georgia-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".gpx")
                        }
                    },
                    MenuRow.Link("Импорт GPX") { importLauncher.launch(arrayOf("*/*")) }
                )),
                MenuSection("О приложении", listOf(
                    MenuRow.Link("Справка") { showInfo() },
                    MenuRow.Link("Диагностика") { showDiagnostics() }
                ))
            )
        )
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

    // ============================================================ waypoints

    private fun addWaypointDialog(ll: LatLng) {
        val et = EditText(this)
        et.setText("Точка ${waypointCache.size + 1}")
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
        val generation = ++listLoadGeneration
        val progress = MaterialAlertDialogBuilder(this).setMessage("Загружаю сохранённые точки…")
            .setNegativeButton("Отмена") { _, _ -> listLoadGeneration++ }.create()
        progress.setOnCancelListener { if (generation == listLoadGeneration) listLoadGeneration++ }
        progress.show()
        thread(name = "waypoint-list") {
            val list = try { userDb.waypoints() } catch (_: Exception) { emptyList() }
            val items = list.map { "${it.name}\n${Geo.fmtCoord(it.lat, it.lon)}" }.toTypedArray()
            runOnUiThread {
                if (generation != listLoadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (list.isEmpty()) { toast("Точек нет. Долгое нажатие на карту или кнопка «Точка»"); return@runOnUiThread }
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
        }
    }

    private fun flyTo(lat: Double, lon: Double, zoom: Double) {
        following = false
        prefs.edit().putBoolean("location_follow", false).apply()
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

    /** The record button lives in the control capsule on the map; the menu setting shows or hides it. */
    private fun refreshLocationButton() {
        val btn = findViewById<android.widget.ImageButton>(R.id.btnLocation)
        val active = locationRequestedByUser || navMode == NavMode.ACTIVE
        btn.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_icon_btn)
        btn.contentDescription = when {
            navMode == NavMode.ACTIVE -> "Перейти к моему местоположению"
            locationRequestedByUser -> "Остановить отображение GPS"
            else -> "Показать моё местоположение"
        }
    }

    private fun updateFloatingControls() {
        val resultsVisible = lvResults.visibility == View.VISIBLE
        val inNavigation = navMode != NavMode.IDLE
        val locationVisible = !resultsVisible
        val fitVisible = !inNavigation && !resultsVisible && resources.configuration.screenHeightDp >= 380
        val recBusy = TrackService.running || prefs.getLong("rec_track", 0L) > 0L
        val recVisible = recBusy || (prefs.getBoolean("rec_button", true) && !resultsVisible)

        findViewById<View>(R.id.btnFit).visibility = if (fitVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.fitDivider).visibility = if (fitVisible && locationVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnLocation).visibility = if (locationVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.recDivider).visibility = if (recVisible && locationVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnRec).visibility = if (recVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.controls).visibility =
            if (fitVisible || locationVisible || recVisible) View.VISIBLE else View.GONE
    }

    private fun refreshRecButton() {
        val btn = findViewById<android.widget.ImageButton>(R.id.btnRec)
        val rec = TrackService.running
        val pending = !rec && prefs.getLong("rec_track", 0L) > 0L
        val busy = rec || pending
        btn.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_record)
        btn.setBackgroundResource(if (busy) R.drawable.bg_rec_active else R.drawable.bg_icon_btn)
        btn.contentDescription = when {
            rec -> "Остановить запись трека"
            pending -> "Отменить запуск записи"
            else -> "Начать запись трека"
        }
        updateFloatingControls()
    }

    private fun toggleRecording() {
        if (TrackService.running) {
            val savedId = prefs.getLong("rec_track", 0L)
            stopService(Intent(this, TrackService::class.java))
            prefs.edit().putLong("rec_track", 0L).putLong("rec_started_at", 0L).apply()
            ui.postDelayed({ refreshTrack(); updateInfo(); refreshRecButton() }, 400)
            MaterialAlertDialogBuilder(this).setTitle("Трек сохранён")
                .setMessage("Запись остановлена, маршрут сохранён на телефоне.\n\nНайти его: Меню → «Мои данные» → «Мои треки». " +
                    "Там трек можно показать на карте или удалить, а «Экспорт GPX» сохранит файл для других программ.")
                .setPositiveButton("Показать на карте") { _, _ ->
                    if (savedId > 0) { shownTrackId = savedId; prefs.edit().putLong("shown_track", savedId).apply(); refreshTrack(); fitTrack(savedId) }
                }
                .setNegativeButton("Закрыть", null).show()
            return
        }
        val pendingId = prefs.getLong("rec_track", 0L)
        if (pendingId > 0L) {
            stopService(Intent(this, TrackService::class.java))
            prefs.edit().putLong("rec_track", 0L).putLong("rec_started_at", 0L).apply()
            shownTrackId = pendingId
            prefs.edit().putLong("shown_track", pendingId).apply()
            refreshTrack()
            updateInfo()
            refreshRecButton()
            toast("Запуск записи отменён; пустой трек сохранён")
            return
        }
        withLocationPermission {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                MaterialAlertDialogBuilder(this)
                    .setMessage("Для записи трека включите геолокацию телефона.")
                    .setPositiveButton("Настройки") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    .setNegativeButton("Отмена", null).show()
                return@withLocationPermission
            }
            val id = userDb.newTrack("Трек " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date()))
            prefs.edit().putLong("rec_track", id).putLong("rec_started_at", System.currentTimeMillis()).apply()
            shownTrackId = id
            prefs.edit().putLong("shown_track", id).apply()
            ContextCompat.startForegroundService(this, Intent(this, TrackService::class.java))
            // TrackService owns its GPS subscription; the activity must not start a second one.
            ui.postDelayed({ scheduleTick(); updateInfo(); refreshRecButton() }, 800)
            toast("Запись трека началась")
        }
    }

    private fun showTracks() {
        val generation = ++listLoadGeneration
        val progress = MaterialAlertDialogBuilder(this).setMessage("Загружаю список треков…")
            .setNegativeButton("Отмена") { _, _ -> listLoadGeneration++ }.create()
        progress.setOnCancelListener { if (generation == listLoadGeneration) listLoadGeneration++ }
        progress.show()
        thread(name = "track-list") {
            val list = try { userDb.tracks() } catch (_: Exception) { emptyList() }
            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            val items = try {
                list.map { track ->
                    val len = userDb.trackSegments(track.id).sumOf { segment ->
                        Geo.length(segment.map { p -> LatLng(p.lat, p.lon) })
                    }
                    "${track.name}\n${fmt.format(Date(track.start))} · ${track.count} точек · ${Geo.fmtDist(len)}"
                }.toTypedArray()
            } catch (_: Exception) { emptyArray() }
            runOnUiThread {
                if (generation != listLoadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (list.isEmpty()) { toast("Треков пока нет"); return@runOnUiThread }
                MaterialAlertDialogBuilder(this).setTitle("Мои треки").setItems(items) { _, i ->
                    val t = list[i]
                    MaterialAlertDialogBuilder(this).setTitle(t.name)
                        .setItems(arrayOf("Показать на карте", "Скрыть", "Удалить")) { _, a ->
                            when (a) {
                                0 -> { shownTrackId = t.id; prefs.edit().putLong("shown_track", t.id).apply(); refreshTrack(); fitTrack(t.id) }
                                1 -> { if (shownTrackId == t.id) { shownTrackId = -1L; prefs.edit().putLong("shown_track", -1L).apply() }; refreshTrack() }
                                2 -> {
                                    if (prefs.getLong("rec_track", 0L) == t.id) {
                                        toast("Сначала остановите запись")
                                    } else {
                                        loadingText.text = "Удаляю трек…"
                                        loading.visibility = View.VISIBLE
                                        thread(name = "track-delete") {
                                            val deleted = try { userDb.deleteTrack(t.id); true } catch (_: Exception) { false }
                                            runOnUiThread {
                                                if (!isFinishing && !isDestroyed) {
                                                    loading.visibility = View.GONE
                                                    if (deleted) {
                                                        if (shownTrackId == t.id) { shownTrackId = -1L; prefs.edit().putLong("shown_track", -1L).apply() }
                                                        refreshTrack()
                                                        toast("Трек удалён")
                                                    } else toast("Не удалось удалить трек")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }.show()
                }.show()
            }
        }
    }

    private fun mapViewportPadding(): IntArray {
        val topPanel = findViewById<View>(R.id.topPanel)
        val navBanner = findViewById<View>(R.id.navBanner)
        val topHeight = listOf(topPanel, navBanner)
            .filter { it.visibility == View.VISIBLE }
            .maxOfOrNull { it.height } ?: 0
        val navBar = findViewById<View>(R.id.navBar)
        val bottomPanel = if (navBar.visibility == View.VISIBLE) navBar else findViewById(R.id.bottomDock)
        return intArrayOf(
            dp(48f),
            insetTop + dp(16f) + topHeight + dp(16f),
            dp(48f),
            insetBottom + dp(16f) + bottomPanel.height + dp(16f)
        )
    }

    private fun fitTrack(id: Long) {
        val selectedTrackId = id
        thread(name = "track-fit") {
            val pts = try { userDb.trackSegments(id).flatten().map { LatLng(it.lat, it.lon) } }
                catch (_: Exception) { emptyList() }
            if (pts.size < 2) return@thread
            val bounds = try {
                LatLngBounds.Builder().apply { pts.forEach { include(it) } }.build()
            } catch (_: Exception) { return@thread }
            runOnUiThread {
                if (!isFinishing && !isDestroyed && shownTrackId == selectedTrackId) {
                    findViewById<View>(R.id.root).post {
                        if (shownTrackId != selectedTrackId || isFinishing || isDestroyed) return@post
                        try {
                            val p = mapViewportPadding()
                            map?.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, p[0], p[1], p[2], p[3]), 800)
                        } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    // ============================================================ GPX

    private fun esc(s: String) = GpxXml.escapeText(s)

    private fun writeGpx(uri: Uri) {
        if (TrackService.running || prefs.getLong("rec_track", 0L) > 0L) {
            toast("Остановите запись перед экспортом GPX, чтобы сохранить все точки")
            return
        }
        toast("Экспортирую GPX…")
        thread(name = "gpx-export") {
            try {
                val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val output = contentResolver.openOutputStream(uri) ?: error("Не удалось открыть файл для записи")
                output.bufferedWriter(Charsets.UTF_8).use { out ->
                    out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                    out.append("<gpx version=\"1.1\" creator=\"Georgia Offline Map\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                    for (w in userDb.waypoints()) {
                        out.append("<wpt lat=\"${w.lat}\" lon=\"${w.lon}\"><name>${esc(w.name)}</name>")
                        if (w.ts > 0L) out.append("<time>${iso.format(Date(w.ts))}</time>")
                        out.append("</wpt>\n")
                    }
                    for (t in userDb.tracks()) {
                        out.append("<trk><name>${esc(t.name)}</name>\n")
                        for (segment in userDb.trackSegments(t.id)) {
                            out.append("<trkseg>\n")
                            for (p in segment) {
                                out.append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
                                if (!p.ele.isNaN()) out.append("<ele>${p.ele}</ele>")
                                if (p.ts > 0) out.append("<time>${iso.format(Date(p.ts))}</time>")
                                out.append("</trkpt>\n")
                            }
                            out.append("</trkseg>\n")
                        }
                        out.append("</trk>\n")
                    }
                    out.append("</gpx>\n")
                }
                runOnUiThread { if (!isFinishing && !isDestroyed) toast("GPX сохранён") }
            } catch (e: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) toast("Ошибка экспорта: ${e.message}") }
            }
        }
    }

    private fun parseGpxTime(value: String): Long {
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

    private fun readGpx(uri: Uri) {
        if (TrackService.running || prefs.getLong("rec_track", 0L) > 0L) {
            toast("Остановите запись трека перед импортом GPX")
            return
        }
        loadingText.text = "Импортирую GPX…"
        loading.visibility = View.VISIBLE
        thread(name = "gpx-import") {
            try {
                val waypoints = ArrayList<UserDb.Wpt>()
                val tracks = ArrayList<UserDb.ImportTrack>()
                var pointCount = 0
                contentResolver.openInputStream(uri)?.use { stream ->
                    val p = Xml.newPullParser()
                    p.setInput(stream, null)
                    var inWpt = false
                    var inTrack = false
                    var pointTag = ""
                    var wptLat = 0.0
                    var wptLon = 0.0
                    var wptName = ""
                    var wptTimestamp = 0L
                    var lat = 0.0
                    var lon = 0.0
                    var ele = Double.NaN
                    var timestamp = 0L
                    var trackName = "Импорт GPX"
                    var segment = 0
                    var nextSegment = 0
                    var rootSeen = false
                    val points = ArrayList<UserDb.Pt>()

                    fun coordinate(tag: String, attr: String): Double {
                        val value = p.getAttributeValue(null, attr)?.toDoubleOrNull()
                            ?: throw IllegalArgumentException("Нет корректной координаты $attr у $tag")
                        if (!value.isFinite() || (attr == "lat" && value !in -90.0..90.0) ||
                            (attr == "lon" && value !in -180.0..180.0)) {
                            throw IllegalArgumentException("Координата $attr вне допустимого диапазона у $tag")
                        }
                        return value
                    }

                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            if (!rootSeen) {
                                if (p.name != "gpx") error("Файл не является GPX")
                                rootSeen = true
                            }
                            when (p.name) {
                                "wpt" -> {
                                    inWpt = true
                                    wptName = ""
                                    wptTimestamp = 0L
                                    wptLat = coordinate("wpt", "lat")
                                    wptLon = coordinate("wpt", "lon")
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
                                    pointTag = p.name
                                    lat = coordinate(pointTag, "lat")
                                    lon = coordinate(pointTag, "lon")
                                    ele = Double.NaN
                                    timestamp = 0L
                                }
                                "name" -> {
                                    val value = p.nextText()
                                    if (inWpt) wptName = value.take(200) else if (inTrack && pointTag.isEmpty()) trackName = value.take(200)
                                }
                                "ele" -> if (pointTag.isNotEmpty()) ele = p.nextText().toDoubleOrNull()?.takeIf { it.isFinite() } ?: Double.NaN
                                "time" -> {
                                    val value = parseGpxTime(p.nextText())
                                    if (inWpt) wptTimestamp = value else if (pointTag.isNotEmpty()) timestamp = value
                                }
                            }
                        } else if (ev == XmlPullParser.END_TAG) {
                            when (p.name) {
                                "wpt" -> {
                                    if (waypoints.size >= 20_000) error("Слишком много точек в GPX (лимит 20 000)")
                                    waypoints.add(UserDb.Wpt(0L, wptName.ifBlank { "Точка ${waypoints.size + 1}" }, wptLat, wptLon, wptTimestamp))
                                    inWpt = false
                                }
                                "trkpt", "rtept" -> if (pointTag == p.name) {
                                    if (pointCount >= 500_000) error("Слишком много точек трека в GPX (лимит 500 000)")
                                    points.add(UserDb.Pt(lat, lon, ele, timestamp, segment))
                                    pointCount++
                                    if (pointCount % 10_000 == 0) {
                                        val progress = pointCount
                                        runOnUiThread { if (!isFinishing && !isDestroyed) loadingText.text = "Импорт GPX: $progress точек…" }
                                    }
                                    pointTag = ""
                                }
                                "trk", "rte" -> if (inTrack) {
                                    if (points.isNotEmpty()) {
                                        if (tracks.size >= 10_000) error("Слишком много треков в GPX (лимит 10 000)")
                                        tracks.add(UserDb.ImportTrack(trackName, points.toList()))
                                    }
                                    inTrack = false
                                    pointTag = ""
                                }
                            }
                        }
                        ev = p.next()
                    }
                    if (!rootSeen) error("Пустой GPX-файл")
                } ?: error("Не удалось открыть GPX-файл")

                userDb.importGpx(waypoints, tracks)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        loading.visibility = View.GONE
                        refreshWaypoints()
                        refreshTrack()
                        toast("Импортировано: точек ${waypoints.size}, треков ${tracks.size}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        loading.visibility = View.GONE
                        toast("Ошибка импорта: ${e.message}")
                    }
                }
            }
        }
    }

    // ============================================================ help and diagnostics

    private fun showInfo() {
        val msg = "Экстренные службы: 112 (единый номер).\n\n" +
            "Карта, поиск и рельеф работают без интернета. GPS тоже работает без сети.\n\n" +
            "Горные дороги и перевалы (Омало, Ушгули, Абано и др.) зимой могут быть закрыты — на карте это не отражено. " +
            "Перед выездом уточняйте обстановку на месте.\n\n" +
            "Навигатор строит офлайн-маршруты на автомобиле, пешком и на велосипеде. Учитываются односторонние дороги и доступ транспорта; запрещающие повороты, пробки и сезонные закрытия дорог не учитываются.\n\n" +
            "Запись трека (кнопка справа на главном экране) сохраняет ваш путь по GPS. Видимость кнопки настраивается в Меню → «Мои данные». " +
            "Остановив запись, найдёте трек в Меню → «Мои данные» → «Мои треки». Для долгой записи разрешите работу в фоне и не закрывайте уведомление.\n\n" +
            "GPX: импорт и экспорт сохранённых точек и треков, включая отдельные сегменты и время записанных точек.\n\n" +
            "Компас: направление на север, на Мекку (кибла) или на любой пункт по названию; нужен датчик ориентации, GPS или центр карты задают вашу позицию.\n\n" +
            "Данные: © участники OpenStreetMap (ODbL), схема OpenMapTiles, Natural Earth, рельеф — AWS Terrain Tiles. " +
            "Данные актуальны на дату сборки APK."
        MaterialAlertDialogBuilder(this).setTitle("Справка").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun showDiagnostics() {
        val generation = ++diagnosticsGeneration
        val progress = MaterialAlertDialogBuilder(this).setMessage("Проверяю данные карты…")
            .setNegativeButton("Отмена") { _, _ -> diagnosticsGeneration++ }.create()
        progress.setOnCancelListener { if (generation == diagnosticsGeneration) diagnosticsGeneration++ }
        progress.show()

        val tileFile = tilesFile
        val reliefPresent = elevation != null
        val contoursFile = File(filesDir, "contours.mbtiles")
        val iconCount = markImages().size
        val rendered = ArrayList<Pair<String, Int>>()
        val m = map
        if (m != null && style != null) {
            val rect = RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat())
            for (id in listOf("water", "landcover-wood", "landuse-residential", "road-primary", "road-minor",
                "road-path", "building", "boundary-2", "boundary-4", "lbl-city", "lbl-town", "lbl-village", "lbl-peak", "lbl-road-major")) {
                rendered.add(id to try { m.queryRenderedFeatures(rect, id).size } catch (_: Exception) { -1 })
            }
        }
        val errorsSnapshot = errors.takeLast(6)

        thread(name = "map-diagnostics") {
            val sb = StringBuilder()
            if (tileFile != null && tileFile.exists()) {
                sb.append("georgia.mbtiles: ${tileFile.length() shr 20} МБ\n")
                var db: SQLiteDatabase? = null
                try {
                    db = SQLiteDatabase.openDatabase(tileFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    db.rawQuery("select name,value from metadata where name in ('minzoom','maxzoom','bounds','format')", null).use {
                        while (it.moveToNext()) sb.append("  ${it.getString(0)} = ${it.getString(1)}\n")
                    }
                    val zs = StringBuilder()
                    db.rawQuery("select zoom_level,count(*) from tiles group by 1 order by 1", null).use {
                        while (it.moveToNext()) zs.append("z${it.getInt(0)}:${it.getInt(1)} ")
                    }
                    sb.append("  тайлы: $zs\n")
                } catch (e: Exception) { sb.append("  ошибка чтения: $e\n") }
                finally { db?.close() }
            } else sb.append("georgia.mbtiles: нет\n")

            sb.append("places.db: ").append(try { placeDb?.count()?.toString() ?: "нет" } catch (_: Exception) { "ошибка" }).append(" мест\n")
            sb.append("relief: ").append(if (reliefPresent) "есть" else "нет").append("\n")
            sb.append("contours.mbtiles (линии высот): ").append(if (contoursFile.exists()) "${contoursFile.length() shr 20} МБ" else "нет").append("\n")
            sb.append("значки на карте: ").append(iconCount).append(" из ").append(PoiCats.IMAGES.size).append("\n")
            try {
                val files = assets.list("glyphs/NotoSans")?.size ?: 0
                sb.append("шрифты (glyphs) в APK: $files файлов\n")
                for ((f, name, need) in listOf(
                    Triple("0-255", "латиница", 150), Triple("1024-1279", "кириллица", 200), Triple("4096-4351", "грузинский", 45)
                )) {
                    val bytes = try { assets.open("glyphs/NotoSans/$f.pbf").use { it.readBytes() } } catch (_: Exception) { null }
                    if (bytes == null) { sb.append("  $name: файла нет ✗\n"); continue }
                    val ink = glyphsWithInk(bytes)
                    sb.append("  $name ($f): $ink глифов ${if (ink >= need) "✓" else "✗ (заглушка, не шрифт)"}\n")
                }
            } catch (e: Exception) { sb.append("шрифты: ошибка $e\n") }

            if (rendered.isNotEmpty()) {
                sb.append("\nОтрисованные объекты на экране:\n")
                rendered.forEach { (id, count) -> sb.append("  $id: $count\n") }
            }
            if (errorsSnapshot.isNotEmpty()) {
                sb.append("\nОшибки загрузки:\n")
                errorsSnapshot.forEach { sb.append("  $it\n") }
            }
            runOnUiThread {
                if (generation != diagnosticsGeneration || isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                MaterialAlertDialogBuilder(this).setTitle("Диагностика").setMessage(sb.toString())
                    .setPositiveButton("OK", null).show()
            }
        }
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

    /** "Route from here": the selected place becomes the start, the destination is chosen next. */
    private fun clearPendingFrom() {
        pendingFrom = null
        pendingFromName = ""
        src("ovl-sel")?.setGeoJson(Geo.pointFc(emptyList()))
    }

    private fun routeFromHere() {
        val here = navDest ?: return
        routeRequestId++ // invalidate any preview calculation before changing the route origin
        pendingFrom = here
        pendingFromName = navDestName
        src("ovl-sel")?.setGeoJson(Geo.pointFc(listOf(here)))
        openNavigatorChooser("Маршрут от «" + navDestName + "»: куда едем?", true)
    }

    private fun openNavigatorChooser(title: String = "Навигатор: куда едем?", fromMode: Boolean = false) {
        if (navMode == NavMode.ACTIVE) {
            MaterialAlertDialogBuilder(this).setMessage("Завершить текущую навигацию?")
                .setPositiveButton("Завершить") { _, _ -> stopNav() }
                .setNegativeButton("Нет", null).show()
            return
        }
        val items = arrayOf("Точку на карте", "В одну из моих точек", "По координатам", "Найти место через поиск")
        MaterialAlertDialogBuilder(this).setTitle(title).setOnCancelListener {
            if (fromMode) clearPendingFrom()
        }.setItems(items) { _, i ->
            when (i) {
                0 -> map?.cameraPosition?.target?.let { setDestination(it, Geo.fmtCoord(it.latitude, it.longitude)) }
                1 -> showRouteWaypointChooser(fromMode)
                2 -> {
                    val et = EditText(this)
                    et.hint = "41.7151, 44.8271"
                    MaterialAlertDialogBuilder(this).setTitle("Широта, долгота").setView(et)
                        .setOnCancelListener { if (fromMode) clearPendingFrom() }
                        .setPositiveButton("Маршрут") { _, _ ->
                            val ll = Geo.parseCoord(et.text.toString())
                            if (ll == null) toast("Не удалось разобрать координаты")
                            else setDestination(ll, Geo.fmtCoord(ll.latitude, ll.longitude))
                        }.setNegativeButton("Отмена") { _, _ -> if (fromMode) clearPendingFrom() }.show()
                }
                else -> {
                    if (fromMode) toast("Найдите место и нажмите на результат")
                    etSearch.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }.show()
    }

    private fun showRouteWaypointChooser(fromMode: Boolean) {
        val generation = ++listLoadGeneration
        val progress = MaterialAlertDialogBuilder(this).setMessage("Загружаю сохранённые точки…")
            .setNegativeButton("Отмена") { _, _ ->
                listLoadGeneration++
                if (fromMode) clearPendingFrom()
            }.create()
        progress.setOnCancelListener {
            if (generation == listLoadGeneration) listLoadGeneration++
            if (fromMode) clearPendingFrom()
        }
        progress.show()
        thread(name = "route-waypoints") {
            val list = try { userDb.waypoints() } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (generation != listLoadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (list.isEmpty()) {
                    toast("Сохранённых точек нет")
                    if (fromMode) clearPendingFrom()
                } else {
                    MaterialAlertDialogBuilder(this).setTitle("Куда")
                        .setOnCancelListener { if (fromMode) clearPendingFrom() }
                        .setItems(list.map { it.name }.toTypedArray()) { _, k ->
                            setDestination(LatLng(list[k].lat, list[k].lon), list[k].name)
                        }.show()
                }
            }
        }
    }

    private fun setDestination(ll: LatLng, name: String) {
        if (navMode == NavMode.ACTIVE) return
        routeRequestId++ // discard a route calculation for the previously selected destination
        navDest = ll
        navDestName = name
        session = null
        src("ovl-route")?.setGeoJson(Geo.lineFc(emptyList()))
        src("ovl-dest")?.setGeoJson(Geo.pointFc(listOf(ll)))
        navMode = NavMode.PLACE
        if (etSearch.text?.isNotEmpty() == true) {
            etSearch.setText("")
            etSearch.clearFocus()
        }
        showResults(false)
        updateNavUi()
        if (pendingFrom != null) planRoute()
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

    private fun setSummary(remM: Double, remS: Double, note: String = "") {
        findViewById<TextView>(R.id.navMain).text = Geo.fmtDist(remM) + " · " + fmtDur(remS)
        findViewById<TextView>(R.id.navSub).text = "Прибытие в " + fmtEta(remS) + " · " + profileName(navProfile) +
            (if (pendingFrom != null) "\nот «$pendingFromName»" else "") +
            (if (navDestName.isNotBlank()) "\n→ $navDestName" else "") +
            (if (note.isNotEmpty()) "\n$note" else "")
    }

    /** When there is no road next to a point, the nearest one is used: say how far it is. */
    private fun offRoadNote(r: RouteResult): String {
        val parts = ArrayList<String>()
        if (r.startOffM > 80) parts.add("старт: ближайшая дорога в " + Geo.fmtDist(r.startOffM))
        if (r.endOffM > 80) parts.add("финиш: ближайшая дорога в " + Geo.fmtDist(r.endOffM))
        return parts.joinToString(", ")
    }

    /** One bottom bar serves three states: a chosen place, a route preview, active guidance. */
    private fun updateNavUi() {
        val mode = navMode
        val active = mode == NavMode.ACTIVE
        showResults(lvResults.visibility == View.VISIBLE)
        findViewById<View>(R.id.navBanner).visibility = if (active) View.VISIBLE else View.GONE
        findViewById<View>(R.id.topPanel).visibility = if (active) View.GONE else View.VISIBLE
        val bar = findViewById<View>(R.id.navBar)
        bar.visibility = if (mode == NavMode.IDLE) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottomDock).visibility = if (mode == NavMode.IDLE) View.VISIBLE else View.GONE
        findViewById<View>(R.id.navProfiles).visibility = if (mode == NavMode.PREVIEW) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnNavClose).visibility = if (active) View.GONE else View.VISIBLE
        val go = findViewById<TextView>(R.id.btnNavGo)
        go.setBackgroundResource(if (active) R.drawable.bg_btn_danger else R.drawable.bg_btn_primary)
        go.text = when (mode) {
            NavMode.PLACE -> "Маршрут"
            NavMode.PREVIEW -> if (pendingFrom != null) "Закрыть" else "Начать"
            NavMode.ACTIVE -> "Завершить"
            else -> ""
        }
        // a chosen place offers "route here" / "route from here"; the single big button is for preview and guidance
        findViewById<View>(R.id.navPlaceActions).visibility = if (mode == NavMode.PLACE) View.VISIBLE else View.GONE
        go.visibility = if (mode == NavMode.PLACE) View.GONE else View.VISIBLE
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
            NavMode.PREVIEW -> session?.let { setSummary(it.totalM, it.route.timeS, offRoadNote(it.route)) }
            else -> {}
        }
        refreshRuler()
        updateFloatingControls()
        bar.post { updateControlsMargin(); applyMapUiMargins() }
    }

    private fun planRoute() {
        val dest = navDest ?: return
        val from = pendingFrom ?: myLoc?.let { LatLng(it.latitude, it.longitude) } ?: routeFrom
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
        val gap = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, dest.latitude, dest.longitude, gap)
        if (gap[0] < 2f) {
            toast("Вы уже у пункта назначения — маршрут не требуется")
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
        val requestId = ++routeRequestId
        val f = File(filesDir, "routing.bin")
        if (!f.exists()) {
            if (requestId == routeRequestId) done(null, "В приложении нет дорожных данных (routing.bin). Пересоберите APK.")
            return
        }
        val profile = navProfile
        if (!silent) findViewById<TextView>(R.id.navSub).text = if (graph == null) "Загружаю дорожные данные…" else "Строю маршрут…"
        thread(name = "routing") {
            try {
                val g = graph ?: synchronized(this@MainActivity) {
                    graph ?: RandomAccessFile(f, "r").use { raf ->
                        RoadGraph(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()))
                    }.also { graph = it }
                }
                val r = g.route(from.latitude, from.longitude, to.latitude, to.longitude, profile)
                runOnUiThread {
                    if (requestId == routeRequestId && !isFinishing && !isDestroyed) {
                        done(r, if (r == null) "Маршрут не найден: рядом нет подходящей дороги или нет связи между точками" else null)
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    if (requestId == routeRequestId && !isFinishing && !isDestroyed) done(null, "Ошибка маршрутизации: ${e.message}")
                }
            }
        }
    }

    private fun drawRoute(r: RouteResult) {
        src("ovl-route")?.setGeoJson(Geo.lineFc((0 until r.pointCount).map { LatLng(r.lat(it), r.lon(it)) }))
    }

    private fun fitRoute(r: RouteResult) {
        val requestId = routeRequestId
        val pts = ArrayList<LatLng>()
        val step = maxOf(1, r.pointCount / 200)
        var i = 0
        while (i < r.pointCount) { pts.add(LatLng(r.lat(i), r.lon(i))); i += step }
        pts.add(LatLng(r.lat(r.pointCount - 1), r.lon(r.pointCount - 1)))
        if (pts.size < 2) return
        try {
            val b = LatLngBounds.Builder().includes(pts).build()
            following = false
            prefs.edit().putBoolean("location_follow", false).apply()
            findViewById<View>(R.id.root).post {
                if (requestId != routeRequestId || navMode == NavMode.IDLE || isFinishing || isDestroyed) return@post
                try {
                    val p = mapViewportPadding()
                    map?.easeCamera(CameraUpdateFactory.newLatLngBounds(b, p[0], p[1], p[2], p[3]), 900)
                } catch (_: Exception) { }
            }
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
        if (!hasLocationPermission()) {
            withLocationPermission { startNav() }
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val finePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarsePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasUsableProvider = (finePermission && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) ||
            ((finePermission || coarsePermission) && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        if (!hasUsableProvider) {
            MaterialAlertDialogBuilder(this)
                .setMessage("Для навигации включите геолокацию телефона.")
                .setPositiveButton("Настройки") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Отмена", null).show()
            return
        }
        navMode = NavMode.ACTIVE
        lastNavFixAt = System.currentTimeMillis()
        navGpsLost = false
        startNavWatchdog()
        refreshLocationButton()
        offCount = 0
        announced.clear()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (prefs.getBoolean("nav_voice", true)) initTts()
        following = true
        prefs.edit().putBoolean("location_follow", true).apply()
        startLocation(false)
        updateNavUi()
        session?.let { s ->
            val first = s.route.steps.firstOrNull()
            findViewById<TextView>(R.id.navArrow).text = Maneuver.arrow(first?.kind ?: Maneuver.DEPART)
            findViewById<TextView>(R.id.navDist).text = fmtStepDist(first?.distToNextM ?: 0.0)
            findViewById<TextView>(R.id.navText).text = first?.text ?: "Начните движение"
            setSummary(s.totalM, s.route.timeS)
        }
        val l = myLoc
        if (l != null && LocationQuality.isUsableForNavigation(l.time, l.accuracy.takeIf { it.isFinite() && it > 0f } ?: Float.POSITIVE_INFINITY, System.currentTimeMillis(), navProfile)) {
            lastNavFixAt = System.currentTimeMillis()
            onNavLocation(l)
        } else toast("Ждём точный сигнал GPS…")
    }

    private fun stopNav() {
        stopNavWatchdog()
        lastNavFixAt = 0L
        navGpsLost = false
        routeRequestId++ // invalidate any route calculation still running in the background
        if (!locationRequestedByUser) {
            locActive = false
            following = false
            prefs.edit().putBoolean("location_follow", false).apply()
            stopLocationUpdates()
            myLoc = null
            refreshMe()
        }
        navMode = NavMode.IDLE
        refreshLocationButton()
        session = null
        navDest = null
        routeFrom = null
        pendingFrom = null
        pendingFromName = ""
        src("ovl-sel")?.setGeoJson(Geo.pointFc(emptyList()))
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

    private fun startNavWatchdog() {
        ui.removeCallbacks(navWatchdog)
        if (navMode == NavMode.ACTIVE) ui.postDelayed(navWatchdog, 2_000L)
    }

    private fun stopNavWatchdog() { ui.removeCallbacks(navWatchdog) }

    private fun onNavLocation(l: Location) {
        val accuracy = l.accuracy.takeIf { it.isFinite() && it > 0f } ?: Float.POSITIVE_INFINITY
        if (!LocationQuality.isUsableForNavigation(l.time, accuracy, System.currentTimeMillis(), navProfile)) return
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
        private val DATA_FILES = listOf("georgia.mbtiles", "relief.mbtiles", "contours.mbtiles", "places.db", "routing.bin")
        private val GEORGIA = LatLngBounds.from(43.65, 46.8, 41.0, 40.0)

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
