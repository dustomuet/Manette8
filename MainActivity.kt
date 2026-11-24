package com.exemple.manettes

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.sqrt

class MainActivity : Activity(), InputManager.InputDeviceListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var inputManager: InputManager

    private lateinit var devicesText: TextView
    private lateinit var liveText: TextView
    private lateinit var labelL: TextView
    private lateinit var labelR: TextView
    private lateinit var seekL: SeekBar
    private lateinit var seekR: SeekBar

    // Valeurs brutes lues sur la manette
    private var rawLX = 0f
    private var rawLY = 0f
    private var rawRX = 0f
    private var rawRY = 0f
    private var trigL = 0f
    private var trigR = 0f
    private var hatX = 0f
    private var hatY = 0f

    // Calibration (décalage au repos) et zones mortes en %
    private var offLX = 0f
    private var offLY = 0f
    private var offRX = 0f
    private var offRY = 0f
    private var dzL = 10
    private var dzR = 10

    private val pressed = linkedSetOf<String>()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("manettes", Context.MODE_PRIVATE)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager

        offLX = prefs.getFloat("offLX", 0f)
        offLY = prefs.getFloat("offLY", 0f)
        offRX = prefs.getFloat("offRX", 0f)
        offRY = prefs.getFloat("offRY", 0f)
        dzL = prefs.getInt("dzL", 10)
        dzR = prefs.getInt("dzR", 10)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(24))
        }

        val title = TextView(this).apply {
            text = "Manettes"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        col.addView(title)

        devicesText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }
        col.addView(devicesText)

        liveText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
        }
        col.addView(liveText)

        labelL = TextView(this).apply {
            text = "Zone morte gauche : $dzL %"
            setPadding(0, dp(16), 0, 0)
        }
        col.addView(labelL)
        seekL = makeSeek(dzL) {
            dzL = it
            prefs.edit().putInt("dzL", it).apply()
            labelL.text = "Zone morte gauche : $it %"
            refresh()
        }
        col.addView(seekL)

        labelR = TextView(this).apply {
            text = "Zone morte droite : $dzR %"
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(labelR)
        seekR = makeSeek(dzR) {
            dzR = it
            prefs.edit().putInt("dzR", it).apply()
            labelR.text = "Zone morte droite : $it %"
            refresh()
        }
        col.addView(seekR)

        val calibrate = Button(this).apply {
            text = "Calibrer (joysticks relâchés)"
            setOnClickListener { calibrer() }
        }
        col.addView(calibrate)

        val reset = Button(this).apply {
            text = "Réinitialiser les réglages"
            setOnClickListener { reinitialiser() }
        }
        col.addView(reset)

        val vibrate = Button(this).apply {
            text = "Tester la vibration"
            setOnClickListener { vibrer() }
        }
        col.addView(vibrate)

        setContentView(ScrollView(this).apply { addView(col) })

        refreshDevices()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, null)
        refreshDevices()
    }

    override fun onPause() {
        super.onPause()
        inputManager.unregisterInputDeviceListener(this)
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
    override fun onInputDeviceRemoved(deviceId: Int) = refreshDevices()
    override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()

    private fun makeSeek(initial: Int, onChange: (Int) -> Unit): SeekBar {
        return SeekBar(this).apply {
            max = 50
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(p)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
    }

    // ---------- Manettes connectées ----------

    private fun gamepads(): List<InputDevice> {
        return InputDevice.getDeviceIds()
            .mapNotNull { InputDevice.getDevice(it) }
            .filter {
                !it.isVirtual &&
                    ((it.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (it.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
            }
    }

    private fun refreshDevices() {
        val list = gamepads()
        if (list.isEmpty()) {
            devicesText.text = "Aucune manette détectée.\nBranche une manette (câble USB-C OTG ou Bluetooth)."
            return
        }
        val sb = StringBuilder("Manettes détectées :\n")
        for (d in list) {
            sb.append("• ").append(d.name)
            sb.append(" (").append(String.format("%04x:%04x", d.vendorId, d.productId)).append(")")
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    val b = d.batteryState
                    if (b.isPresent && !b.capacity.isNaN()) {
                        sb.append(" – batterie ").append((b.capacity * 100).toInt()).append(" %")
                    }
                } catch (e: Exception) {
                    // batterie non disponible
                }
            }
            sb.append("\n")
        }
        devicesText.text = sb.toString().trimEnd()
    }

    // ---------- Lecture des entrées ----------

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if ((ev.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            ev.action == MotionEvent.ACTION_MOVE
        ) {
            readAxes(ev)
            refresh()
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val src = ev.source
        val isPad = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (isPad) {
            val name = KeyEvent.keyCodeToString(ev.keyCode).removePrefix("KEYCODE_")
            if (ev.action == KeyEvent.ACTION_DOWN) {
                pressed.add(name)
            } else if (ev.action == KeyEvent.ACTION_UP) {
                pressed.remove(name)
            }
            refresh()
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    private fun readAxes(ev: MotionEvent) {
        val dev = ev.device
        val src = ev.source

        rawLX = ev.getAxisValue(MotionEvent.AXIS_X)
        rawLY = ev.getAxisValue(MotionEvent.AXIS_Y)

        val hasTriggerAxis = dev?.getMotionRange(MotionEvent.AXIS_LTRIGGER, src) != null ||
            dev?.getMotionRange(MotionEvent.AXIS_BRAKE, src) != null
        val hasRx = dev?.getMotionRange(MotionEvent.AXIS_RX, src) != null
        val useZ = hasTriggerAxis || !hasRx

        rawRX = ev.getAxisValue(if (useZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)
        rawRY = ev.getAxisValue(if (useZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY)

        trigL = maxOf(
            ev.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            ev.getAxisValue(MotionEvent.AXIS_BRAKE)
        )
        trigR = maxOf(
            ev.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            ev.getAxisValue(MotionEvent.AXIS_GAS)
        )

        hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
        hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
    }

    // ---------- Correction du drift ----------

    // Retire le décalage au repos, puis applique une zone morte circulaire.
    private fun corriger(x: Float, y: Float, ox: Float, oy: Float, dzPercent: Int): Pair<Float, Float> {
        val dz = dzPercent / 100f
        val ax = x - ox
        val ay = y - oy
        val mag = sqrt(ax * ax + ay * ay)
        if (mag <= dz || mag == 0f) return Pair(0f, 0f)
        val scale = ((mag - dz) / (1f - dz)).coerceAtMost(1f) / mag
        return Pair(ax * scale, ay * scale)
    }

    private fun calibrer() {
        offLX = rawLX
        offLY = rawLY
        offRX = rawRX
        offRY = rawRY
        prefs.edit()
            .putFloat("offLX", offLX)
            .putFloat("offLY", offLY)
            .putFloat("offRX", offRX)
            .putFloat("offRY", offRY)
            .apply()
        Toast.makeText(this, "Calibration enregistrée", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun reinitialiser() {
        offLX = 0f
        offLY = 0f
        offRX = 0f
        offRY = 0f
        dzL = 10
        dzR = 10
        prefs.edit().clear().apply()
        seekL.progress = dzL
        seekR.progress = dzR
        labelL.text = "Zone morte gauche : $dzL %"
        labelR.text = "Zone morte droite : $dzR %"
        Toast.makeText(this, "Réglages réinitialisés", Toast.LENGTH_SHORT).show()
        refresh()
    }

    // ---------- Vibration ----------

    private fun vibrer() {
        val d = gamepads().firstOrNull()
        if (d == null) {
            Toast.makeText(this, "Aucune manette", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                d.vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                d.vibrator.vibrate(300)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Vibration non disponible", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Affichage ----------

    private fun f(v: Float): String = String.format("%+.2f", v)

    private fun refresh() {
        if (!::liveText.isInitialized) return
        val l = corriger(rawLX, rawLY, offLX, offLY, dzL)
        val r = corriger(rawRX, rawRY, offRX, offRY, dzR)

        val sb = StringBuilder()
        sb.append("Joystick gauche\n")
        sb.append("  brut    X ").append(f(rawLX)).append("  Y ").append(f(rawLY)).append("\n")
        sb.append("  corrigé X ").append(f(l.first)).append("  Y ").append(f(l.second)).append("\n\n")
        sb.append("Joystick droit\n")
        sb.append("  brut    X ").append(f(rawRX)).append("  Y ").append(f(rawRY)).append("\n")
        sb.append("  corrigé X ").append(f(r.first)).append("  Y ").append(f(r.second)).append("\n\n")
        sb.append("Gâchettes  L ").append(f(trigL)).append("  R ").append(f(trigR)).append("\n")
        sb.append("Croix      X ").append(f(hatX)).append("  Y ").append(f(hatY)).append("\n\n")
        sb.append("Boutons : ")
        sb.append(if (pressed.isEmpty()) "-" else pressed.joinToString(", "))
        liveText.text = sb.toString()
    }
}
