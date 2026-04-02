package com.gateshot.platform.sensor

import kotlinx.coroutines.flow.Flow

data class MagnetometerData(val x: Float, val y: Float, val z: Float)
data class GyroscopeData(val x: Float, val y: Float, val z: Float)
data class AccelerometerData(val x: Float, val y: Float, val z: Float)
data class GpsLocation(val latitude: Double, val longitude: Double, val altitude: Double)

interface SensorPlatform {
    fun getMagnetometerReadings(): Flow<MagnetometerData>
    fun getGyroscopeReadings(): Flow<GyroscopeData>
    fun getAccelerometerReadings(): Flow<AccelerometerData>
    fun getBatteryTemperature(): Float?
    fun getBatteryLevel(): Int
    fun getGpsLocation(): GpsLocation?
    fun getAmbientTemperature(): Float?
}
