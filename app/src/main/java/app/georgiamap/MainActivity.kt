package app.georgiamap

import android.Manifest
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var prefs: SharedPreferences

    private var map: MapLibreMap? = null
    private var style: Style? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) showMyLocation()
            else Toast.makeText(this, R.string.no_location, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        findViewById<View>(R.id.btnLang).setOnClickListener { showLanguageDialog() }
        findViewById<View>(R.id.btnFit).setOnClickListener {
            map?.easeCamera(CameraUpdateFactory.newLatLngBounds(GEORGIA, 48), 700)
        }
        findViewById<View>(R.id.btnLocation).setOnClickListener { requestLocation() }

        prepareTiles { file -> mapView.getMapAsync { m -> setupMap(m, file) } }
    }

    // ---------------------------------------------------------------- tiles

    /** SQLite needs a real file, so the bundled .mbtiles is copied out of the APK once per build. */
    private fun prepareTiles(onReady: (File) -> Unit) {
        val target = File(filesDir, "georgia.mbtiles")
        val version = BuildConfig.VERSION_CODE
        if (target.exists() && prefs.getInt("tiles_version", -1) == version) {
            onReady(target)
            return
        }
        loading.visibility = View.VISIBLE
        thread(name = "tiles-copy") {
            try {
                val total = try {
                    assets.openFd("georgia.mbtiles").use { it.length }
                } catch (e: Exception) {
                    -1L
                }
                val tmp = File(filesDir, "georgia.mbtiles.tmp")
                assets.open("georgia.mbtiles").use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var copied = 0L
                        var lastShown = -1L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            val mb = copied shr 20
                            if (mb != lastShown) {
                                lastShown = mb
                                val text = if (total > 0) "$mb / ${total shr 20} МБ" else "$mb МБ"
                                runOnUiThread { loadingText.text = getString(R.string.loading) + "\n" + text }
                            }
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) error("rename failed")
                prefs.edit().putInt("tiles_version", version).apply()
                runOnUiThread {
                    loading.visibility = View.GONE
                    onReady(target)
                }
            } catch (e: Exception) {
                runOnUiThread { loadingText.text = "Ошибка: ${e.message}" }
            }
        }
    }

    // ---------------------------------------------------------------- map

    private fun setupMap(m: MapLibreMap, file: File) {
        map = m
        m.setMinZoomPreference(5.0)
        m.setMaxZoomPreference(18.0)
        m.setLatLngBoundsForCameraTarget(LIMIT)
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(42.2, 43.5)).zoom(6.6).build()
            )
        )

        val json = assets.open("style.json").bufferedReader().use { it.readText() }
            .replace("__MBTILES_URL__", "mbtiles://" + file.absolutePath)

        m.setStyle(Style.Builder().fromJson(json)) { s ->
            style = s
            applyLabels(s)
        }
    }

    private fun enabledLanguages(): List<Lang> =
        Lang.values().filter { prefs.getBoolean(it.prefKey, true) }

    /** Rewrites the text-field of every label layer (ids start with "lbl-") for the chosen languages. */
    private fun applyLabels(s: Style) {
        val langs = enabledLanguages()
        for (layer in s.layers) {
            if (layer is SymbolLayer && layer.id.startsWith("lbl-")) {
                if (langs.isEmpty()) {
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

    private fun showLanguageDialog() {
        val all = Lang.values()
        val checked = BooleanArray(all.size) { prefs.getBoolean(all[it].prefKey, true) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_title)
            .setMultiChoiceItems(all.map { it.title }.toTypedArray(), checked) { _, which, isChecked ->
                prefs.edit().putBoolean(all[which].prefKey, isChecked).apply()
                style?.let { applyLabels(it) }
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------------------------------------------------------------- location

    private fun requestLocation() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) showMyLocation()
        else locationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    @SuppressLint("MissingPermission")
    private fun showMyLocation() {
        val m = map ?: return
        val s = style ?: return
        try {
            val lc = m.locationComponent
            if (!lc.isLocationComponentActivated) {
                lc.activateLocationComponent(LocationComponentActivationOptions.builder(this, s).build())
            }
            lc.isLocationComponentEnabled = true
            lc.cameraMode = CameraMode.TRACKING
            lc.renderMode = RenderMode.COMPASS
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "location error", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    companion object {
        // approximate extent of Georgia (N, E, S, W)
        private val GEORGIA = LatLngBounds.from(43.65, 46.8, 41.0, 40.0)
        // how far the camera may wander
        private val LIMIT = LatLngBounds.from(44.2, 47.4, 40.5, 39.4)
    }
}
