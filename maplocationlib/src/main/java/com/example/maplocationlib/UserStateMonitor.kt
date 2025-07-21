package com.fifth.maplocationlib

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.content.Context
import android.hardware.SensorEvent
import android.provider.Settings
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt
import java.util.LinkedList
import java.util.ArrayDeque

class UserStateMonitor(private val context: Context) {

    // -------------------------------
    // 기존 센서 관련 변수들
    // -------------------------------
    private var lastStepTime: Long = 0
    private val stationaryThreshold: Long = 5000 // 5 seconds
    private val impactThreshold: Float = 25.0f   // 충격 임계값
    private val sensorErrorThreshold: Long = 1000 // 센서 에러 체크 주기 (1초)

    private var isMoving: Boolean = false
    private var hasImpact: Boolean = false
    private var hasSensorError: Boolean = false
    private var isFirstStep: Boolean = true // 첫 걸음 여부 체크

    private var accelerometerValues: FloatArray? = null
    private var pressureValue: Float = 0f
    // rotationValue: 0번 인덱스: x축, 1번 인덱스: y축, 등으로 가정
    private var rotationValue: FloatArray = floatArrayOf(0f, 0f, 0f, 0f)
    private var lightSensorValue: Float = 100f
    private var distanceSensorValue: Float = 5f
    private var pressureSensorValue: Float = 1000.0f
    private var lastSensorUpdateTime: Long = System.currentTimeMillis()

    // 보조 회전 변수
    private var lastYUpPeek = 0f
    private var lastXDownPeek = 0f
    private var lastYDownPeek = 0f

    // 밝기 임계값 관련
    private var prebrightness = 0.0f
    private val lightqueue: LinkedList<Float> = LinkedList(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))

    /**
     * 전화 상태 코드:
     * 0: normal
     * 1: handheld swing
     * 2: in pocket
     * 3: other ~ phone call
     */
    private var phoneState: Int = 0

    // -------------------------------
    // ★ 추가: 센서 레코드를 1초간 큐잉하는 구조
    // -------------------------------
    private data class SensorRecord(
        val timestamp: Long,
        val rotation: FloatArray, // 현재 rotationValue의 복사본
        val light: Float,         // 현재 lightSensorValue
        val distance: Float,       // 현재 distanceSensorValue
        val pressure: Float
    )

    // 1초간의 데이터를 저장하는 큐
    private val sensorRecords = ArrayDeque<SensorRecord>()


    // 센서 업데이트 후 현재 상태를 하나의 레코드로 큐에 추가
    private fun recordSensorData() {
        val record = SensorRecord(
            timestamp = System.currentTimeMillis(),
            rotation = rotationValue.copyOf(),
            light = lightSensorValue,
            distance = distanceSensorValue,
            pressure = pressureSensorValue
        )
        sensorRecords.add(record)
        // Remove records older than 1 second
        val cutoff = System.currentTimeMillis() - 1000
        while (sensorRecords.isNotEmpty() && sensorRecords.first().timestamp < cutoff) {
            sensorRecords.removeFirst()
        }
    }

    /**
     * getStatus() 호출 시, 최근 1초 동안의 센서 데이터를 분석하여 최종 상태를 결정합니다.
     *
     * 반환 상태 코드:
     * 0: normal
     * 1: handheld swing
     * 2: in pocket
     * 3: other (예: 전화중)
     */
    fun getStatus(paststatus:Int): Int {
        val now = System.currentTimeMillis()
        if (sensorRecords.isEmpty()) return getPhoneState()

        // Compute peaks in one pass
        var maxRot0 = Float.MIN_VALUE
        var minRot0 = Float.MAX_VALUE
        var maxRot1 = Float.MIN_VALUE
        var minRot1 = Float.MAX_VALUE
        var maxLight = Float.MIN_VALUE
        var minLight = Float.MAX_VALUE
        var maxPressure = Float.MIN_VALUE
        var minPressure = Float.MAX_VALUE
        val lastDistance = sensorRecords.last().distance

        for (record in sensorRecords) {
            val rot = record.rotation
            if (rot[0] > maxRot0) maxRot0 = rot[0]
            if (rot[0] < minRot0) minRot0 = rot[0]
            if (rot[1] > maxRot1) maxRot1 = rot[1]
            if (rot[1] < minRot1) minRot1 = rot[1]
            if (record.pressure > maxPressure) maxPressure = record.pressure
            if (record.pressure < minPressure) minPressure = record.pressure
            if (record.light > maxLight) maxLight = record.light
            if (record.light < minLight) minLight = record.light
        }


        val diffRot0 = maxRot0 - minRot0
        val diffRot1 = maxRot1 - minRot1
        val diffLight = maxLight - minLight
        val diffPressure = maxPressure - minPressure

        val resultstate = when {
            lastDistance <= 1f -> 3
            diffRot0 < 15f && diffRot1 < 15f -> 0
            diffLight < 10f && diffRot1 < 50f && maxLight < 20 -> 2
            diffRot1 > 50 -> 1
            else -> 0
        }


        return resultstate
    }

    // -------------------------------
    // 기존 센서 업데이트 메서드들 (필요 시 recordSensorData() 호출 추가)
    // -------------------------------
    fun updateLightData(lightValue: Float) {
        prebrightness = lightSensorValue
        lightSensorValue = lightValue
        lightqueue.add(lightSensorValue)
        lightqueue.poll()
        updateSensorTimestamp()
        recordSensorData() // 큐에 현재 상태 기록
    }

    fun updateProximityData(distance: Float) {
        distanceSensorValue = distance
        updateSensorTimestamp()
        recordSensorData()
    }

    fun updateRotationData(rotAngle: FloatArray) {
        val previousX = rotationValue[0]
        rotationValue = rotAngle.copyOf()
        updateSensorTimestamp()

        // 회전 변화에 따른 보조 변수 업데이트
        if (rotationValue[1] < previousX) {
            lastYDownPeek = rotationValue[1]
        } else if (rotationValue[1] > previousX) {
            lastYUpPeek = rotationValue[1]
        }
        recordSensorData()
    }

    fun updateAccelData(event: SensorEvent) {
        accelerometerValues = event.values.clone()
        checkImpact()
        updateSensorTimestamp()
        recordSensorData()
    }

    fun updatePressureData(event: SensorEvent) {
        pressureValue = event.values[0]
        updateSensorTimestamp()
        recordSensorData()
    }

    fun updateWalkingState(walked: Boolean) {
        if (walked) {
            if (isFirstStep) {
                isFirstStep = false
            }
            updateStepDetection()
            recordSensorData()
        }
    }

    private fun updateStepDetection() {
        lastStepTime = System.currentTimeMillis()
        isMoving = true
    }

    private fun checkMovementState() {
        if (isFirstStep) {
            isMoving = false
            return
        }
        val currentTime = System.currentTimeMillis()
        isMoving = (currentTime - lastStepTime) < stationaryThreshold
    }

    private fun checkImpact() {
        accelerometerValues?.let {
            val magnitude = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])
            hasImpact = magnitude > impactThreshold
        }
    }

    private fun checkSensorError() {
        val currentTime = System.currentTimeMillis()
        hasSensorError = (currentTime - lastSensorUpdateTime) > sensorErrorThreshold
    }

    private fun updateSensorTimestamp() {
        lastSensorUpdateTime = System.currentTimeMillis()
    }

    /**
     * 기존 getPhoneState() 로직 (큐에 데이터가 없을 때의 fallback)
     */
    fun getPhoneState(): Int {
        return when {
            distanceSensorValue <= 1f -> 3

            phoneState == 0 && ((rotationValue[1] in -30f..20f)) && (abs(lightqueue.max() - lightqueue.min()) < 15) -> 2
            phoneState != 0 && rotationValue[0] in -100f..-75f && (abs(lightqueue.max() - lightqueue.min()) < 15) -> 2
            lastYDownPeek < -50 -> 1
            else -> 4
        }
    }

    // 사용자 상태 조회 메서드들
    fun getStates(): Map<String, Boolean> {
        checkMovementState()
        checkSensorError()
        return mapOf(
            "isMoving" to isMoving,
            "hasImpact" to hasImpact,
            "hasSensorError" to hasSensorError
        )
    }

    fun isMoving(): Boolean {
        checkMovementState()
        return isMoving
    }

    fun hasImpact(): Boolean = hasImpact

    fun hasSensorError(): Boolean {
        checkSensorError()
        return hasSensorError
    }

    fun getAccelerometerValues(): FloatArray? = accelerometerValues
    fun getPressureValue(): Float = pressureValue

    /**
     * 단순 예시로 조도 값이 15 미만이면 주머니 안에 있다고 판단
     */
    fun inPocketDetect(brightness: Float): Boolean {
        return brightness < 15f
    }
}
