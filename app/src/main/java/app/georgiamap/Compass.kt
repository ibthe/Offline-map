package app.georgiamap

import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
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

/** Animated figure-8 that shows how to move the phone for calibration. */
class CalibView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var colTrack = Color.LTGRAY
    var colDot = Color.BLUE
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var t = 0f
    private var anim: ValueAnimator? = null

    private fun px(t: Float, a: Float, cx: Float): Float { val s = sin(t); return cx + a * cos(t) / (1 + s * s) }
    private fun py(t: Float, a: Float, cy: Float): Float { val s = sin(t); return cy + a * 0.9f * s * cos(t) / (1 + s * s) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val a = min(width / 2f - 24 * d, height * 0.9f)
        path.reset()
        var k = 0f
        path.moveTo(px(0f, a, cx), py(0f, a, cy))
        while (k <= 6.2832f) { path.lineTo(px(k, a, cx), py(k, a, cy)); k += 0.05f }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5 * d
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = colTrack
        c.drawPath(path, paint)
        // fading trail behind the dot, then the dot
        paint.style = Paint.Style.FILL
        for (i in 12 downTo 1) {
            val tt = t - i * 0.07f
            paint.color = colDot
            paint.alpha = (255 * (1f - i / 13f) * 0.6f).toInt()
            c.drawCircle(px(tt, a, cx), py(tt, a, cy), (4 + (13 - i) * 0.5f) * d, paint)
        }
        paint.alpha = 255
        paint.color = colDot
        c.drawCircle(px(t, a, cx), py(t, a, cy), 11 * d, paint)
    }
}

/** Compass sheet: north / qibla / any point, with calibration and sensor handling. The activity only supplies data through [Host]. */
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

    private val prefs = act.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val sm = act.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rot: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val mag: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private var registered = false
    private var sheetOpen = false
    private var mapFollow = false
    private var mode = Mode.NORTH
    private var point: Target? = null
    private var rotAccuracy = -1
    private var magAccuracy = -1          // -1 = the sensor has not reported yet
    private var fieldUt = Float.NaN
    private var calibrating = false
    private var calibForced = false       // first ever use: the user has to confirm
    private var calibDismissed = false    // "continue anyway" was pressed in this session
    private var calibOkSince = 0L

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
    private var mainGroup: View? = null
    private var calibGroup: View? = null
    private var tvCalibStatus: TextView? = null
    private var tvCalibField: TextView? = null
    private var btnCalibDone: TextView? = null
    private var chips: List<Pair<Mode, TextView>> = emptyList()

    private val rotListener = object : SensorEventListener {
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

        override fun onAccuracyChanged(s: Sensor?, a: Int) { rotAccuracy = a }
    }

    /** The magnetometer is only listened to for its calibration status and field strength. */
    private val magListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values
            fieldUt = Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {
            magAccuracy = a
            // the sensor says it is badly calibrated: ask for the figure-8 once per opening of the compass
            if (sheetOpen && !calibrating && !calibDismissed && a <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) showCalib(true)
        }
    }

    // ---------------------------------------------------------------- lifecycle
    fun onStart() = updateSensors()
    fun onStop() = unregister()

    private fun updateSensors() { if (sheetOpen || mapFollow) register() else unregister() }

    private fun register() {
        if (registered) return
        if (rot == null) { host.toast("В телефоне нет датчика ориентации"); return }
        refreshDeclination()
        registered = sm.registerListener(rotListener, rot, SensorManager.SENSOR_DELAY_GAME)
        if (mag != null) sm.registerListener(magListener, mag, SensorManager.SENSOR_DELAY_UI)
    }

    private fun unregister() {
        if (!registered) return
        sm.unregisterListener(rotListener)
        sm.unregisterListener(magListener)
        registered = false
        have = false
    }

    private fun refreshDeclination() {
        val o = host.origin() ?: return
        declination = GeomagneticField(o.lat.toFloat(), o.lon.toFloat(), o.alt.toFloat(), System.currentTimeMillis()).declination
        declAt = System.currentTimeMillis()
    }

    private fun accuracy() = if (magAccuracy >= 0) magAccuracy else rotAccuracy
    private fun accuracyName(a: Int) = when (a) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "высокая"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "средняя"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "низкая"
        SensorManager.SENSOR_STATUS_UNRELIABLE -> "ненадёжная"
        else -> "определяется…"
    }
    private fun fieldOk() = fieldUt.isNaN() || (fieldUt in 25f..65f)

    private fun onHeading(h: Double) {
        val a = Math.toRadians(h)
        if (!have) { sx = cos(a); sy = sin(a); have = true } else { sx += 0.18 * (cos(a) - sx); sy += 0.18 * (sin(a) - sy) }
        heading = (Math.toDegrees(atan2(sy, sx)) + 360.0) % 360.0
        val now = System.currentTimeMillis()
        if (now - declAt > 60_000) refreshDeclination()
        if (mapFollow && now - lastMap > 200) { lastMap = now; host.setMapBearing(heading) }
        if (sheetOpen && now - lastUi > 50) { lastUi = now; if (calibrating) refreshCalib(now) else refresh() }
    }

    // ---------------------------------------------------------------- calibration page
    private fun showCalib(on: Boolean) {
        calibrating = on
        calibOkSince = 0L
        mainGroup?.visibility = if (on) View.GONE else View.VISIBLE
        calibGroup?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) refreshCalib(System.currentTimeMillis()) else refresh()
    }

    private fun refreshCalib(now: Long) {
        val a = accuracy()
        val good = a >= SensorManager.SENSOR_STATUS_ACCURACY_HIGH && fieldOk()
        tvCalibStatus?.text = "Точность компаса: " + accuracyName(a) + if (good) " ✓" else ""
        tvCalibField?.text = if (fieldUt.isNaN()) "Магнитное поле: измеряю…"
        else if (fieldOk()) "Магнитное поле: ${fieldUt.roundToInt()} мкТл (норма 25–65)"
        else "Магнитное поле: ${fieldUt.roundToInt()} мкТл. " + (if (fieldUt > 65f) "Слишком сильное" else "Слишком слабое") +
            ": отойдите от металла, магнитов, колонок и проводов (машина, лифт и здание тоже мешают)."
        btnCalibDone?.text = if (good) "Готово, к компасу" else "Всё равно продолжить"
        if (good) {
            if (calibOkSince == 0L) calibOkSince = now
            // after the first confirmed use the page closes itself once the sensor has been fine for 2 s
            if (!calibForced && now - calibOkSince > 2000) finishCalib()
        } else calibOkSince = 0L
    }

    private fun finishCalib() {
        calibForced = false
        calibDismissed = true
        prefs.edit().putBoolean("calib_seen", true).apply()
        showCalib(false)
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
        val main: String
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
        // the arrow is only trusted when the sensor is reasonably calibrated
        alignedNow = !d.isNaN() && abs(d) <= (if (was) 6.0 else 3.0) && accuracy() >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
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
        val a = accuracy()
        parts.add("точность компаса: " + accuracyName(a) +
            if (a in 0..SensorManager.SENSOR_STATUS_ACCURACY_LOW) " — откалибруйте (ссылка ниже)" else "")
        if (!fieldOk()) parts.add("рядом сильные помехи магнитному полю")
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
        calibDismissed = false
        calibForced = !prefs.getBoolean("calib_seen", false)
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
        val cv = root.findViewById<CalibView>(R.id.cmpCalibView)
        cv.colTrack = color(R.attr.gmAccentContainer, Color.LTGRAY)
        cv.colDot = color(R.attr.gmAccent, Color.BLUE)
        mainGroup = root.findViewById(R.id.cmpMainGroup)
        calibGroup = root.findViewById(R.id.cmpCalibGroup)
        tvCalibStatus = root.findViewById(R.id.cmpCalibStatus)
        tvCalibField = root.findViewById(R.id.cmpCalibField)
        btnCalibDone = root.findViewById(R.id.cmpCalibDone)
        root.findViewById<TextView>(R.id.cmpCalibSteps).text =
            "1. Отойдите от металла, магнитов, чехлов-магнитов, колонок и проводов. Лучше на улице.\n" +
            "2. Возьмите телефон в руку и плавно рисуйте им в воздухе «восьмёрку» (∞): как показано выше, с наклоном и поворотом кисти.\n" +
            "3. Повторите 5–10 раз, пока не появится «Точность компаса: высокая ✓».\n" +
            "4. Держите телефон ровно, не рядом с другими устройствами. После этого нажмите «Готово»."
        btnCalibDone?.setOnClickListener { finishCalib() }
        root.findViewById<View>(R.id.cmpCalibLink).setOnClickListener { calibForced = false; showCalib(true) }
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
        dialog.setOnDismissListener {
            sheetOpen = false; view = null; mainGroup = null; calibGroup = null
            tvCalibStatus = null; tvCalibField = null; btnCalibDone = null; calibrating = false
            updateSensors()
        }
        sheetOpen = true
        // the very first use always starts with the calibration page
        showCalib(calibForced)
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
