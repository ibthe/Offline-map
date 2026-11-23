package app.georgiamap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Dial: north rotates with the phone, the target marker shows where to turn. */
class CompassView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var heading = 0f
        set(v) { field = v; invalidate() }
    var target = Float.NaN
        set(v) { field = v; invalidate() }
    var aligned = false
        set(v) { field = v; invalidate() }
    var colFill = Color.WHITE
    var colRing = Color.GRAY
    var colText = Color.BLACK
    var colMuted = Color.GRAY
    var colAccent = Color.BLUE
    var colNorth = Color.RED
    var colOk = Color.GREEN

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tri = Path()

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 10 * d
        paint.style = Paint.Style.FILL
        paint.color = colFill
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (if (aligned) 4 else 2) * d
        paint.color = if (aligned) colOk else colRing
        c.drawCircle(cx, cy, r, paint)

        // rotating dial
        c.save()
        c.rotate(-heading, cx, cy)
        for (a in 0 until 360 step 10) {
            val major = a % 30 == 0
            paint.strokeWidth = (if (major) 2.5f else 1.2f) * d
            paint.color = if (a == 0) colNorth else if (major) colText else colMuted
            val len = (if (major) 14 else 8) * d
            c.drawLine(cx, cy - r + 2 * d, cx, cy - r + 2 * d + len, paint)
            c.rotate(10f, cx, cy)
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = sp(20f)
        paint.isFakeBoldText = true
        val labels = arrayOf("С", "В", "Ю", "З")
        for (i in 0 until 4) {
            paint.color = if (i == 0) colNorth else colText
            c.drawText(labels[i], cx, cy - r + 44 * d, paint)
            c.rotate(90f, cx, cy)
        }
        paint.isFakeBoldText = false
        // target marker (absolute bearing, so it turns together with the dial)
        if (!target.isNaN()) {
            c.save()
            c.rotate(target, cx, cy)
            val col = if (aligned) colOk else colAccent
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5 * d
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = col
            c.drawLine(cx, cy, cx, cy - r + 70 * d, paint)
            paint.style = Paint.Style.FILL
            tri.reset()
            tri.moveTo(cx, cy - r + 52 * d)
            tri.lineTo(cx - 14 * d, cy - r + 80 * d)
            tri.lineTo(cx + 14 * d, cy - r + 80 * d)
            tri.close()
            c.drawPath(tri, paint)
            c.restore()
        }
        c.restore()

        // fixed marker: the top of the phone
        paint.style = Paint.Style.FILL
        paint.color = colText
        tri.reset()
        tri.moveTo(cx, cy - r - 2 * d)
        tri.lineTo(cx - 9 * d, cy - r - 18 * d)
        tri.lineTo(cx + 9 * d, cy - r - 18 * d)
        tri.close()
        c.drawPath(tri, paint)
        c.drawCircle(cx, cy, 5 * d, paint)
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, min(s, (320 * d).toInt()))
    }
}

/** Compass sheet: north / qibla / any point, with sensor handling. The activity only supplies data through [Host]. */
class CompassController(private val act: AppCompatActivity, private val host: Host) {
    class Target(val name: String, val lat: Double, val lon: Double)
    class Origin(val lat: Double, val lon: Double, val alt: Double, val label: String)
    enum class Mode { NORTH, QIBLA, POINT }

    interface Host {
        fun origin(): Origin?
        fun mapCenter(): Target?
        fun searchPlaces(q: String): List<Target>
        fun waypoints(): List<Target>
        fun navTarget(): Target?
        fun setMapBearing(deg: Double)
        fun toast(msg: String)
    }

    private val sm = act.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rot: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var registered = false
    private var sheetOpen = false
    private var mapFollow = false
    private var mode = Mode.NORTH
    private var point: Target? = null
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

    private var sx = 0.0
    private var sy = 0.0
    private var have = false
    private var heading = 0.0
    private var declination = 0f
    private var declAt = 0L
    private var lastUi = 0L
    private var lastMap = 0L
    private var alignedNow = false

    private var view: CompassView? = null
    private var tvMain: TextView? = null
    private var tvTurn: TextView? = null
    private var tvSub: TextView? = null
    private var chips: List<Pair<Mode, TextView>> = emptyList()

    private val listener = object : SensorEventListener {
        private val r = FloatArray(9)
        private val r2 = FloatArray(9)
        private val o = FloatArray(3)
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(r, e.values)
            SensorManager.getOrientation(r, o)
            var az = o[0].toDouble()
            if (abs(Math.toDegrees(o[1].toDouble())) > 55) {     // held upright: use where the back of the phone points
                SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, r2)
                SensorManager.getOrientation(r2, o)
                az = o[0].toDouble()
            }
            onHeading((Math.toDegrees(az) + declination + 360.0) % 360.0)
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) { accuracy = a }
    }

    // ---------------------------------------------------------------- lifecycle
    fun onStart() = updateSensors()
    fun onStop() = unregister()

    private fun updateSensors() { if (sheetOpen || mapFollow) register() else unregister() }

    private fun register() {
        if (registered) return
        if (rot == null) { host.toast("В телефоне нет датчика ориентации"); return }
        refreshDeclination()
        registered = sm.registerListener(listener, rot, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun unregister() {
        if (!registered) return
        sm.unregisterListener(listener)
        registered = false
        have = false
    }

    private fun refreshDeclination() {
        val o = host.origin() ?: return
        declination = GeomagneticField(o.lat.toFloat(), o.lon.toFloat(), o.alt.toFloat(), System.currentTimeMillis()).declination
        declAt = System.currentTimeMillis()
    }

    private fun onHeading(h: Double) {
        val a = Math.toRadians(h)
        if (!have) { sx = cos(a); sy = sin(a); have = true } else { sx += 0.18 * (cos(a) - sx); sy += 0.18 * (sin(a) - sy) }
        heading = (Math.toDegrees(atan2(sy, sx)) + 360.0) % 360.0
        val now = System.currentTimeMillis()
        if (now - declAt > 60_000) refreshDeclination()
        if (mapFollow && now - lastMap > 200) { lastMap = now; host.setMapBearing(heading) }
        if (sheetOpen && now - lastUi > 50) { lastUi = now; refresh() }
    }

    // ---------------------------------------------------------------- target
    private fun targetOf(): Triple<String, Double, Double>? {   // name, lat, lon of the target (not for NORTH)
        return when (mode) {
            Mode.QIBLA -> Triple("Кибла (Мекка)", CompassMath.KAABA_LAT, CompassMath.KAABA_LON)
            Mode.POINT -> point?.let { Triple(it.name, it.lat, it.lon) }
            Mode.NORTH -> null
        }
    }

    private fun refresh() {
        val v = view ?: return
        val o = host.origin()
        var bearing = Double.NaN
        var main: String
        var distance = ""
        if (mode == Mode.NORTH) {
            bearing = 0.0
            main = "Север · 0°"
        } else {
            val t = targetOf()
            if (t == null || o == null) {
                main = if (mode == Mode.POINT) "Выберите точку" else "Нет позиции"
            } else {
                bearing = CompassMath.bearing(o.lat, o.lon, t.second, t.third)
                main = "${t.first} · ${bearing.roundToInt()}° ${CompassMath.cardinal(bearing)}"
                distance = "до цели " + CompassMath.fmtKm(CompassMath.distanceKm(o.lat, o.lon, t.second, t.third))
            }
        }
        val h = if (have) heading else Double.NaN
        v.heading = if (have) heading.toFloat() else 0f
        v.target = if (bearing.isNaN()) Float.NaN else bearing.toFloat()
        val d = if (bearing.isNaN() || h.isNaN()) Double.NaN else CompassMath.turn(bearing, h)
        val was = alignedNow
        alignedNow = !d.isNaN() && abs(d) <= (if (was) 6.0 else 3.0)
        if (alignedNow && !was) buzz()
        v.aligned = alignedNow
        tvMain?.text = main
        tvTurn?.text = when {
            !have -> "Жду данные датчика…"
            d.isNaN() -> ""
            alignedNow -> "Направление совпало ✓"
            d > 0 -> "Повернитесь вправо на ${abs(d).roundToInt()}°"
            else -> "Повернитесь влево на ${abs(d).roundToInt()}°"
        }
        val parts = ArrayList<String>()
        if (have) parts.add("Телефон смотрит на ${heading.roundToInt()}° ${CompassMath.cardinal(heading)}")
        if (distance.isNotEmpty()) parts.add(distance)
        if (mode != Mode.NORTH) parts.add(if (o == null) "позиция неизвестна" else "позиция: ${o.label}")
        if (accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) parts.add("компас неточен: покачайте телефон «восьмёркой»")
        tvSub?.text = parts.joinToString("\n")
        chips.forEach { (m, tv) ->
            val sel = m == mode
            tv.setBackgroundResource(if (sel) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            tv.setTextColor(color(if (sel) R.attr.gmOnAccentContainer else R.attr.gmTextSecondary, Color.GRAY))
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        try {
            val vib = act.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            else vib.vibrate(40)
        } catch (e: Exception) { /* no vibrator */ }
    }

    private fun color(attr: Int, fallback: Int) = MaterialColors.getColor(act, attr, fallback)

    // ---------------------------------------------------------------- UI
    fun show(start: Mode) {
        mode = start
        val dialog = BottomSheetDialog(act)
        val root = act.layoutInflater.inflate(R.layout.sheet_compass, null)
        val v = root.findViewById<CompassView>(R.id.cmpView)
        v.colFill = color(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        v.colRing = color(R.attr.gmPanelStroke, Color.GRAY)
        v.colText = color(R.attr.gmTextPrimary, Color.BLACK)
        v.colMuted = color(R.attr.gmHint, Color.GRAY)
        v.colAccent = color(R.attr.gmAccent, Color.BLUE)
        v.colNorth = color(R.attr.gmCrosshair, Color.RED)
        v.colOk = 0xFF2FA866.toInt()
        view = v
        tvMain = root.findViewById(R.id.cmpMain)
        tvTurn = root.findViewById(R.id.cmpTurn)
        tvSub = root.findViewById(R.id.cmpSub)
        tvTurn?.setTextColor(color(R.attr.gmAccent, Color.BLUE))
        val north = root.findViewById<TextView>(R.id.cmpModeNorth)
        val qibla = root.findViewById<TextView>(R.id.cmpModeQibla)
        val pt = root.findViewById<TextView>(R.id.cmpModePoint)
        chips = listOf(Mode.NORTH to north, Mode.QIBLA to qibla, Mode.POINT to pt)
        north.setOnClickListener { mode = Mode.NORTH; refresh() }
        qibla.setOnClickListener { mode = Mode.QIBLA; refresh() }
        pt.setOnClickListener { pickPoint() }
        val sw = root.findViewById<MaterialSwitch>(R.id.cmpMapSwitch)
        sw.isChecked = mapFollow
        sw.setOnCheckedChangeListener { _, on ->
            mapFollow = on
            if (!on) host.setMapBearing(0.0)
            updateSensors()
        }
        dialog.setContentView(root)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundResource(R.drawable.bg_sheet)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { sheetOpen = false; view = null; updateSensors() }
        sheetOpen = true
        refresh()
        updateSensors()
        dialog.show()
    }

    private fun pickPoint() {
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        items.add("Найти населённый пункт или место"); actions.add { searchDialog() }
        items.add("Из моих точек"); actions.add {
            val list = host.waypoints()
            if (list.isEmpty()) host.toast("Сохранённых точек нет") else choose("Мои точки", list)
        }
        host.navTarget()?.let { t -> items.add("Цель навигатора: ${t.name}"); actions.add { setPoint(t) } }
        items.add("Центр карты"); actions.add { host.mapCenter()?.let { setPoint(Target("Центр карты", it.lat, it.lon)) } }
        items.add("Координаты"); actions.add { coordsDialog() }
        MaterialAlertDialogBuilder(act).setTitle("Куда показывать").setItems(items.toTypedArray()) { _, i -> actions[i]() }.show()
    }

    private fun setPoint(t: Target) { point = t; mode = Mode.POINT; refresh() }

    private fun choose(title: String, list: List<Target>) {
        MaterialAlertDialogBuilder(act).setTitle(title)
            .setItems(list.map { it.name }.toTypedArray()) { _, i -> setPoint(list[i]) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun searchDialog() {
        val et = EditText(act)
        et.hint = "Например: Кутаиси, Казбеги, Мекка"
        et.setSingleLine()
        MaterialAlertDialogBuilder(act).setTitle("Название").setView(et)
            .setPositiveButton("Найти") { _, _ ->
                val q = et.text.toString().trim()
                val res = if (q.length >= 2) host.searchPlaces(q) else emptyList()
                if (res.isEmpty()) host.toast("Ничего не найдено") else choose("Найдено", res)
            }.setNegativeButton("Отмена", null).show()
    }

    private fun coordsDialog() {
        val et = EditText(act)
        et.hint = "41.7151, 44.8271"
        MaterialAlertDialogBuilder(act).setTitle("Широта, долгота").setView(et)
            .setPositiveButton("OK") { _, _ ->
                val ll = Geo.parseCoord(et.text.toString())
                if (ll == null) host.toast("Не удалось разобрать координаты")
                else setPoint(Target(Geo.fmtCoord(ll.latitude, ll.longitude), ll.latitude, ll.longitude))
            }.setNegativeButton("Отмена", null).show()
    }
}
