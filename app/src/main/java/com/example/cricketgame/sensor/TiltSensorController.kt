package com.example.cricketgame.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads device tilt via TYPE_ACCELEROMETER for use as the batting/bowling aim input.
 *
 * Exposes a single normalized value in -1f (tilt left / leg side) .. +1f (tilt right / off side),
 * derived from the accelerometer's X axis. Start/stop are explicit (call from a DisposableEffect
 * tied to the composable's lifecycle) rather than self-registering, so the listener is never left
 * running while the match screen isn't visible.
 */
class TiltSensorController(context: Context) : SensorEventListener {

    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _tilt = MutableStateFlow(0f)
    val tilt: StateFlow<Float> = _tilt.asStateFlow()

    /** True if this device actually has an accelerometer to read from. */
    val isAvailable: Boolean get() = accelerometer != null

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // event.values[0] is lateral (X-axis) acceleration due to gravity, roughly
        // -GRAVITY_EARTH..+GRAVITY_EARTH as the device tilts from full-left to full-right.
        val normalized = (event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        _tilt.value = normalized
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op - accuracy changes don't affect how we use this reading
    }
}
