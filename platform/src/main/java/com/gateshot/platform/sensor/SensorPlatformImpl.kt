package com.gateshot.platform.sensor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensorPlatformImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorPlatform {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override fun getMagnetometerReadings(): Flow<MagnetometerData> = sensorFlow(
        Sensor.TYPE_MAGNETIC_FIELD
    ) { event -> MagnetometerData(event.values[0], event.values[1], event.values[2]) }

    override fun getGyroscopeReadings(): Flow<GyroscopeData> = sensorFlow(
        Sensor.TYPE_GYROSCOPE
    ) { event -> GyroscopeData(event.values[0], event.values[1], event.values[2]) }

    override fun getAccelerometerReadings(): Flow<AccelerometerData> = sensorFlow(
        Sensor.TYPE_ACCELEROMETER
    ) { event -> AccelerometerData(event.values[0], event.values[1], event.values[2]) }

    override fun getBatteryTemperature(): Float? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (temp > 0) temp / 10f else null  // Battery temp is in tenths of degrees
    }

    override fun getBatteryLevel(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    override fun getGpsLocation(): GpsLocation? {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            location?.let { GpsLocation(it.latitude, it.longitude, it.altitude) }
        } catch (_: SecurityException) {
            null
        }
    }

    // Cache the latest ambient temperature reading from the sensor listener
    @Volatile
    private var latestAmbientTemp: Float? = null
    private var ambientTempListener: SensorEventListener? = null

    override fun getAmbientTemperature(): Float? {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
            ?: return null

        // Register a one-shot listener if not already listening
        if (ambientTempListener == null) {
            ambientTempListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    latestAmbientTemp = event.values[0]
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(
                ambientTempListener, sensor, SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        return latestAmbientTemp
    }

    private fun <T> sensorFlow(sensorType: Int, transform: (SensorEvent) -> T): Flow<T> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(sensorType)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(transform(event))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        // Use SENSOR_DELAY_FASTEST for gyroscope (needed for frame alignment integration)
        // and SENSOR_DELAY_UI for others to save power
        val delay = if (sensorType == Sensor.TYPE_GYROSCOPE)
            SensorManager.SENSOR_DELAY_FASTEST else SensorManager.SENSOR_DELAY_UI
        sensorManager.registerListener(listener, sensor, delay)
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
