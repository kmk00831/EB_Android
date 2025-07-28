package com.fifth.maplocationlib

import android.util.Log
import kotlin.math.abs

class PocketInMonitor(
    // 모션 시작 임계치
    private val defaultThresholds: Pair<Float, Float> = 1.0f to 2.0f,
    // 자이로 안정성 임계치
    private val gyroStabilityThreshold: Pair<Float, Float> = 0.1f to 0.1f,
    // 조도 센서 안정성 임계치 (조도 변화량; 필요에 따라 조정)
    private val lightStabilityThreshold: Float = 10f
) {

    enum class MonitorState {
        NORMAL,             // 평상시
        GYRO_CHECK,         // 상태 전환 확정 후 자이로 값 체크
        MEASURE_PERIODIC,   // 각 축 별 회전 각 주기성 측정
        PENDING_STATE,      // 보류 상태 (주머니/손 흔들기 결정 전)
        BASIC_STATE,        // 기본 상태 (자이로 값이 안정적일 때)
        POCKET_STATE,       // 주머니 속 상태 (조도 센서 안정 시)
        HAND_SHAKING_STATE  // 손에 들고 흔들기 상태 (조도 센서 불안정 시)
    }

    // 현재 상태 (초기 상태: NORMAL)
    private var currentState = MonitorState.NORMAL

    // 이전 자이로(yaw)와 조도 센서 값을 저장하여 안정성 판단에 활용
    private var previousgyro: Pair<Float, Float> = 0.0f to 0.0f
    private var previousLight: Float? = null

    // 최종 상태 결정 시 호출할 콜백
    var onStateDetermined: ((MonitorState) -> Unit)? = null

//    /**
//     * 센서 데이터를 업데이트하면서 상태를 전환합니다.
//     * @param rotpitch       로테 pitch 값
//     * @param rotroll        로테 roll 값
//     * @param gyropitch     자이로 pitch
//     * @param gyroroll      자이로 roll
//     * @param light       현재 조도 센서 값
//     * @param timestamp   현재 시간 (ms) – 필요 시 타이밍 기반 로직 추가 가능
//     */
//    fun updateSensor(rotpitch: Float, rotroll: Float, gyropitch: Float, gyroroll:Float, light: Float, timestamp: Long) {
//        when (currentState) {
//            MonitorState.NORMAL -> {
//                Log.d("PocketStateMonitor", "상태: NORMAL")
//                // 피치, 롤이 일정 임계치를 넘으면 상태 전환 조건 만족
//                if (isTransitionConditionMet(rotpitch, rotroll)) {
//                    currentState = MonitorState.GYRO_CHECK
//                    Log.d("PocketStateMonitor", "상태 전환 조건 만족. → GYRO_CHECK")
//                }
//            }
//            MonitorState.GYRO_CHECK -> {
//                Log.d("PocketStateMonitor", "상태: GYRO_CHECK")
//                if (isGyroStable(gyropitch, gyroroll)) {
//                    currentState = MonitorState.BASIC_STATE
//                    Log.d("PocketStateMonitor", "자이로 값 안정적. → BASIC_STATE")
//                    onStateDetermined?.invoke(currentState)
//                    resetState()
//                } else {
//                    currentState = MonitorState.MEASURE_PERIODIC
//                    Log.d("PocketStateMonitor", "자이로 값 불안정. → MEASURE_PERIODIC")
//                }
//            }
//            MonitorState.MEASURE_PERIODIC -> {
//                Log.d("PocketStateMonitor", "상태: MEASURE_PERIODIC")
//                //여기서도 뭘 하려 했는데 뭔질 모르겠네
//                currentState = MonitorState.PENDING_STATE
//                Log.d("PocketStateMonitor", "주기성 측정 완료. → PENDING_STATE")
//            }
//            MonitorState.PENDING_STATE -> {
//                Log.d("PocketStateMonitor", "상태: PENDING_STATE")
//                if (isLightStable(light)) {
//                    currentState = MonitorState.POCKET_STATE
//                    Log.d("PocketStateMonitor", "조도 변화 안정적. → POCKET_STATE")
//                } else {
//                    currentState = MonitorState.HAND_SHAKING_STATE
//                    Log.d("PocketStateMonitor", "조도 변화 불안정. → HAND_SHAKING_STATE")
//                }
//                onStateDetermined?.invoke(currentState)
//                resetState()
//            }
//            else -> {
//                // 최종 상태에 도달한 경우, 이후 다시 정상 상태로 초기화
//                resetState()
//            }
//        }
//        // 비교를 위해 이전 자이로와 조도 값 업데이트
//        previousgyro = gyropitch to gyroroll
//        previousLight = light
//    }

    /**
     * 피치와 롤 값이 임계치를 초과하는지 여부를 판단합니다.
     */
    private fun isTransitionConditionMet(pitch: Float, roll: Float): Boolean {
        val (pitchThreshold, rollThreshold) = defaultThresholds
        return (abs(pitch) > pitchThreshold && abs(roll) > rollThreshold)
    }

    /**
     * 자이로(여기서는 yaw) 값이 안정적인지 판단합니다.
     * 이전 yaw 값과의 차이가 임계치 미만이면 안정적이라 판단합니다.
     */
    private fun isGyroStable(gyropitch: Float, gyroroll: Float): Boolean {
        val (prevpitch, prevroll) = previousgyro ?: return false

        val tmp = abs(gyropitch - prevpitch) < gyroStabilityThreshold.first
        val result = abs(gyroroll - prevroll) < gyroStabilityThreshold.second && tmp

        return result
    }

    /**
     * 조도 센서 값의 변화가 안정적인지 판단합니다.
     */
    private fun isLightStable(currentLight: Float): Boolean {
        val prev = previousLight ?: return false
        return abs(currentLight - prev) < lightStabilityThreshold
    }

    /**
     * 상태 전환 주기가 종료되면 초기 상태로 리셋합니다.
     */
    private fun resetState() {
        currentState = MonitorState.NORMAL
    }
}


class RotationPatternDetector(
    // 한 윈도우에 수집할 샘플 개수 (필요에 따라 조정)
    private val windowSize: Int = 100,
    // 축별 피크-투-피크 변화량 차이가 임계치를 초과하면 상태 변화로 판단
    private val diffThreshold0: Float = 2.0f,
    private val diffThreshold1: Float = 2.0f
) {
    // 각 축의 센서 샘플을 저장할 리스트
    private val samplesAxis0 = mutableListOf<Float>()
    private val samplesAxis1 = mutableListOf<Float>()

    // 이전 윈도우에서 계산된 피크-투-피크 값 (최대-최소)
    private var previousDiff0: Float? = null
    private var previousDiff1: Float? = null

    // 상태 변화가 감지되었을 때 호출할 콜백
    var onStateChanged: (() -> Unit)? = null

    /**
     * 매번 센서 업데이트 시 호출합니다.
     * @param axis0 rotation 센서 0번 인덱스 값 (예: pitch)
     * @param axis1 rotation 센서 1번 인덱스 값 (예: roll)
     */
    fun addSample(axis0: Float, axis1: Float) {
        samplesAxis0.add(axis0)
        samplesAxis1.add(axis1)

        if (samplesAxis0.size >= windowSize) {
            processWindow()
            // 윈도우가 끝나면 다음 주기를 위해 샘플 초기화
            samplesAxis0.clear()
            samplesAxis1.clear()
        }
    }

    // 윈도우 내 최대/최소값을 이용해 피크-투-피크 차이를 계산하고, 이전 값과 비교합니다.
    private fun processWindow() {
        val max0 = samplesAxis0.maxOrNull() ?: return
        val min0 = samplesAxis0.minOrNull() ?: return
        val max1 = samplesAxis1.maxOrNull() ?: return
        val min1 = samplesAxis1.minOrNull() ?: return

        val diff0 = max0 - min0
        val diff1 = max1 - min1

        // 이전 윈도우가 있다면 비교
        if (previousDiff0 != null && previousDiff1 != null) {
            val delta0 = kotlin.math.abs(diff0 - previousDiff0!!)
            val delta1 = kotlin.math.abs(diff1 - previousDiff1!!)

            // 두 축 모두 임계치보다 큰 변화가 있으면 상태 변화로 인식
            if (delta0 > diffThreshold0 && delta1 > diffThreshold1) {
                onStateChanged?.invoke()
                samplesAxis0.clear()
                samplesAxis1.clear()
            }
        }

        // 이번 윈도우의 값을 저장하여 다음 비교에 사용
        previousDiff0 = diff0
        previousDiff1 = diff1
    }
}
