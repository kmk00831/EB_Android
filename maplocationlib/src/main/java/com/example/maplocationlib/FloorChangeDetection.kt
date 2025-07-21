package com.fifth.maplocationlib

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

class FloorChangeDetection {
    private val appStartTime: Long = System.currentTimeMillis()

    // 기존 상태 변수 (계단 관련)
    var prevBaselinePressure: Float? = null
    var prevFloor: Int = 0
    var consecutiveSteps: Int = 0
    var stairSuspected: Boolean = false
    var stairConfirmed: Boolean = false
    var floorArrival: Boolean = false
    var baselinePressure: Float? = null
    var stairGyroValue: Float = 0.0f
    var arrivedStairGyroValue: Float = 0.0f

    // 엘리베이터 관련 추가 변수
    var arrivedElevatorGyroValue: Float = 0.0f

    var prevPressure: Float? = null
    var currentFloor: Int = 1
    var prevStairState: Int = 0
    var stairState: Int = 0
    var elevatorState: Int = 0
    var tempBaselinePressure: Float = 0.0f
    var lastFivePressures: MutableList<Float> = mutableListOf()

    // LPF 관련
    var filteredPressure: Float? = null
    val alpha: Float = 0.03f
    val pressure_history: ArrayDeque<Pair<Float, Float>> = ArrayDeque()

    var elevationStatus: String = "상승"

    // 엘리베이터 인식 알고리즘 관련 변수
    var elevator_slope_count: Int = 0
    var elevator_baseline_pressure: Float? = null
    var elevator_step_count: Int = 0
    var elevator_state5_time: Float? = null
    var prevFilteredPressure: Float? = null
    var prevTime: Float? = null

    private val scope = CoroutineScope(Dispatchers.Default)

    private val elevatorJson : Map<String, List<List<Int>>> = mapOf(
        "180" to listOf(listOf(362, 728)),
        "0" to listOf(listOf(383, 387))
    )

    // JSON 데이터를 Map 형태로 정의 (층 -> 방향 -> 각도 -> 좌표 리스트)
    private val stairsJson: Map<String, Map<String, Map<String, List<List<Int>>>>> = mapOf(
        "-2" to mapOf(
            "상승" to mapOf(
                "90" to listOf(listOf(324, 195), listOf(314, 692))
            )
        ),
        "-1" to mapOf(
            "하강" to mapOf(
                "90" to listOf(listOf(324, 210), listOf(315, 692))
            ),
            "상승" to mapOf(
                "90" to listOf(listOf(327, 195), listOf(321, 692))
            )
        ),
        "1" to mapOf(
            "하강" to mapOf(
                "90" to listOf(listOf(324, 692), listOf(333, 211))
            ),
            "상승" to mapOf(
                "90" to listOf(listOf(307, 692), listOf(323, 160))
            )
        ),
        "2" to mapOf(
            "하강" to mapOf(
                "90" to listOf(listOf(320, 175), listOf(315, 692))
            ),
            "상승" to mapOf(
                "90" to listOf(listOf(323, 159), listOf(315, 692))
            )
        ),
        "3" to mapOf(
            "하강" to mapOf(
                "90" to listOf(listOf(323, 175), listOf(315, 692))
            ),
            "상승" to mapOf(
                "90" to listOf(listOf(323, 159), listOf(312, 692))
            )
        ),
        "4" to mapOf(
            "하강" to mapOf(
                "90" to listOf(listOf(323, 175), listOf(315, 692))
            ),
            "상승" to mapOf(
                "90" to listOf(listOf(323, 159), listOf(312, 692))
            )
        ),
        "5" to mapOf(
            "하강" to mapOf(
                "90" to listOf(listOf(323, 175), listOf(315, 692))
            ),
            "상승" to mapOf(
                "90" to listOf(listOf(323, 159), listOf(312, 692))
            )
        ),
        "6" to mapOf(
            "하강" to mapOf(
                "90" to listOf(listOf(323, 175), listOf(315, 692))
            ),
            "상승" to mapOf(
                "90" to listOf(listOf(323, 159), listOf(312, 692))
            )
        ),
        "7" to mapOf(
            "하강" to mapOf(
                "90" to listOf(listOf(323, 175), listOf(315, 692))
            ),
            "상승" to mapOf(
                "90" to listOf(listOf(323, 159), listOf(312, 692))
            )
        ),
        "8" to mapOf(
            "하강" to mapOf(
                "90" to listOf(listOf(323, 175), listOf(315, 692))
            ),
            "상승" to mapOf(
                "90" to listOf(listOf(323, 159), listOf(312, 692))
            )
        )
    )


    /**
     * gyro가 허용 범위(0, 90, 180, 270 ±15°) 내에 있는지 판단하는 함수
     */
    fun isAllowed(angle: Float): Pair<Boolean, Float?> {
        val allowedAngles = listOf(0f, 90f, 180f, 270f)
        val tol = 20f
        for (a in allowedAngles) {
            val diff = abs(((angle - a + 180) % 360) - 180)
            if (diff <= tol) {
                return Pair(true, a)
            }
        }
        return Pair(false, null)
    }

    /**
     * 기압 센서 업데이트: newPressure, currentTime(초), gyroAngle을 인자로 받음.
     * 여기서 엘리베이터 인식 알고리즘도 함께 수행됨.
     */
    fun updatePressureData(newPressure: Float, currentTime: Float, gyroAngle: Float) {
        if (filteredPressure == null) {
            filteredPressure = newPressure
            prevFilteredPressure = newPressure
            prevTime = currentTime
        } else {
            filteredPressure = filteredPressure!! + alpha * (newPressure - filteredPressure!!)
        }
        pressure_history.add(Pair(currentTime, filteredPressure!!))


        var ref: Pair<Float, Float>? = null

        for (pair in pressure_history) {
            if (pair.first <= currentTime - 0.3f) {
                ref = pair
            } else {
                break
            }
        }
        if (ref != null) {
            val timeDiff = currentTime - ref.first
            val derivative = abs(filteredPressure!! - ref.second) / timeDiff
            Log.d("derivative", "${derivative}")

            if (derivative >= 0.05f) {
                if (elevator_slope_count == 0) {
                    elevator_baseline_pressure = filteredPressure
                    // 사용자가 바라보는 방향으로부터 [0, 180] 중 가까운 각도 계산
                    val allowedAngles = listOf(0f, 180f)
                    var diffMin = Float.MAX_VALUE
                    var nearestAngle = allowedAngles[0]
                    for (a in allowedAngles) {
                        val diff = abs(((gyroAngle - a + 180) % 360) - 180)
                        if (diff < diffMin) {
                            diffMin = diff
                            nearestAngle = a
                        }
                    }
                    arrivedElevatorGyroValue = nearestAngle
                }
                elevator_slope_count++
                if (elevator_slope_count >= 50) {
                    elevatorState = 1  // 엘리베이터 확신
                }
                Log.d("ElevatorDebug", "$currentTime / $elevator_slope_count / ElevatorState: $elevatorState")
            } else {
                if (elevator_slope_count > 0) {
                    if (elevatorState == 1) {
                        elevatorState = 2  // 엘리베이터 도착 (내리지 않음)
                        Log.d(
                            "ElevatorDebug",
                            "ElevatorState changed to 2 (도착) / ${abs(filteredPressure!! - elevator_baseline_pressure!!)} / " +
                                    "${abs(filteredPressure!! - elevator_baseline_pressure!!)/0.449f} / " +
                                    "${round(abs(filteredPressure!! - elevator_baseline_pressure!!)/0.37f)}"
                        )
                        Log.d("ElevatorDebug", "arrivedElevatorGyro : $arrivedElevatorGyroValue")
                        var floor_change = round(abs(filteredPressure!! - elevator_baseline_pressure!!) / 0.449f).toInt()
                        Log.d("ElevatorDebug", "$floor_change, ${abs(filteredPressure!! - elevator_baseline_pressure!!)/0.449f}")
                        if (floor_change > 0) {
                            if (filteredPressure!! < elevator_baseline_pressure!!) {
                                if (currentFloor == -1) {
                                    floor_change += 1
                                }
                                currentFloor += floor_change
                            } else {
                                currentFloor = max(currentFloor - floor_change, -1)
                                if (currentFloor == 0) {
                                    currentFloor = -1
                                }
                            }
                            Log.d("ElevatorDebug", "currentFloor: $currentFloor")
                        }
                    }
                }
                elevator_slope_count = 0
            }
        }

        // pressure_history에서 1초보다 오래된 데이터 제거
        while (pressure_history.isNotEmpty() && pressure_history.first().first < currentTime - 1.0f) {
            pressure_history.removeFirst()
        }

        // lastFivePressures 업데이트
        lastFivePressures.add(filteredPressure!!)
        if (lastFivePressures.size > 5) {
            lastFivePressures.removeAt(0)
        }
    }

    /**
     * 걸음 인식 시 자이로 업데이트: 계단/엘리베이터 인식 알고리즘 실행.
     */
    fun updateGyroData(gyroAngle: Float) {
        scope.launch {
            processStep(gyroAngle)
        }
    }

    /**
     * 계단 및 엘리베이터 인식 알고리즘의 핵심 로직.
     */
    fun processStep(gyroAngle: Float) {
        // 앱 시작 이후 경과된 시간을 초 단위로 계산
        val currentTime = (System.currentTimeMillis() - appStartTime) / 1000f
        val (allowed, nearestAngle) = isAllowed(gyroAngle)

        // 엘리베이터 상태 처리
        if (elevatorState != 0) {
            prevBaselinePressure = null
            if (elevatorState == 2) {
                elevator_step_count++
                if (elevator_step_count >= 5) {
                    elevatorState = 3  // 엘리베이터 내림
                    elevator_step_count = 0
                }
            } else if (elevatorState == 3) {
                elevatorState = 0
            }
            return
        }

        // 계단 인식 알고리즘 (Stair Detection)
        val currentPressure = filteredPressure ?: return

        lastFivePressures.add(currentPressure)
        if (lastFivePressures.size > 5) {
            lastFivePressures.removeAt(0)
        }

        var allowedLocal = allowed
        if (!allowedLocal) {
            if (prevStairState == 2) {
                allowedLocal = true
            }
        }
        if (allowedLocal) {
            consecutiveSteps++
        } else {
            prevFloor = currentFloor
            stairState = 0
            if (prevStairState == 1) {
                prevBaselinePressure = baselinePressure
            }
            resetDetection()
        }
        Log.d("StairDebug", "consecutiveSteps: $consecutiveSteps / stairState: $stairState")

        if (consecutiveSteps >= 5 && !stairSuspected) {
            stairSuspected = true
            if (prevBaselinePressure != null && abs(prevBaselinePressure!! - currentPressure) >= 0.16f) {
                if ((prevFloor == 1 && (prevBaselinePressure!! < currentPressure)) ||
                    (prevFloor == -1 && (prevBaselinePressure!! > currentPressure))
                ) {
                    baselinePressure = prevBaselinePressure
                } else {
                    baselinePressure = prevBaselinePressure
                }
            } else {
                baselinePressure = if (lastFivePressures.isNotEmpty()) lastFivePressures.first() else currentPressure
            }
            stairGyroValue = nearestAngle ?: gyroAngle
        } else {
            stairState = 0
        }

        if (stairSuspected && !stairConfirmed && baselinePressure != null) {
            stairState = 1
            var stair_confirmed_threshold = 0.3f
            if ((prevFloor == 1 && baselinePressure!! < currentPressure) ||
                (prevFloor == -1 && baselinePressure!! > currentPressure)
            ) {
                stair_confirmed_threshold = 0.23f
            }
            if (abs(currentPressure - baselinePressure!!) > stair_confirmed_threshold) {
                stairConfirmed = true
                elevationStatus = if (currentPressure > baselinePressure!!) "하강" else "상승"
                arrivedStairGyroValue = stairGyroValue
            }
        }
        var currentDiff: Float? = null
        if (stairConfirmed && !floorArrival) {
            var arrival = false
            stairState = 2
            if (prevPressure != null) {
                currentDiff = currentPressure - prevPressure!!
                var arrival_threshold_1 = 0.3f
                var arrival_threshold_2 = 0.01f
                if ((prevFloor == 1 && baselinePressure!! < currentPressure) ||
                    (prevFloor == -1 && baselinePressure!! > currentPressure)
                ) {
                    arrival_threshold_1 = 0.3f
                    arrival_threshold_2 = 0.001f
                }
                if (baselinePressure != null &&
                    abs(currentPressure - baselinePressure!!) > arrival_threshold_1 &&
                    (currentDiff * (prevPressure!! - baselinePressure!!) < 0 || abs(currentDiff) < arrival_threshold_2)
                ) {
                    arrival = true
                }
            }
            val diffGyro = abs(((gyroAngle - stairGyroValue + 180) % 360) - 180)
            if (diffGyro > 15f) {
                if ((prevFloor == 1 && baselinePressure!! < currentPressure) ||
                    (prevFloor == -1 && baselinePressure!! > currentPressure)
                ) {
                    if (abs(currentPressure - baselinePressure!!) > 0.3f) {
                        arrival = true
                    }
                    else {
                        arrival = false
                    }
                } else {
                    arrival = true
                }
            }
            if (arrival) {
                stairState = 3
                arrivedStairGyroValue = stairGyroValue
                floorArrival = true
                if (baselinePressure != null && currentPressure > baselinePressure!!) {
                    if (currentFloor == 1) {
                        currentFloor = -1
                    } else {
                        currentFloor = max(currentFloor - 1, -2)
                    }
                    prevBaselinePressure = null
                } else {
                    if (currentFloor == -1) {
                        currentFloor = 1
                    } else {
                        currentFloor = min(currentFloor + 1, 8)
                    }
                    prevBaselinePressure = null
                }
                resetDetection()
            }
        }
        prevPressure = currentPressure
        prevStairState = stairState

        return
    }

    fun setStairsInfo(
        currentPos: Array<Float>,
        currentFloor: Int,
        arrivedStairGyroValue: Float,
        elevation: String
    ): Array<Float>? {
        // 현재 층을 문자열 키로 변환 (예: -1 -> "-1")
        val floorKey = currentFloor.toString()
        // 자이로 각도를 정수형 문자열 키로 변환 (예: 90 -> "90")
        val angleKey = arrivedStairGyroValue.toInt().toString()

        // JSON 데이터에서 해당 층의 전달받은 elevation의 좌표 리스트를 가져옴
        val coordsList = stairsJson[floorKey]?.get(elevation)?.get(angleKey)

        // 좌표 리스트가 없으면 null 반환
        if (coordsList.isNullOrEmpty()) {
            return null
        }

        // JSON 좌표 데이터를 Pair<Int, Int>로 변환
        val matchingCoords = coordsList.mapNotNull { coord ->
            if (coord.size >= 2) Pair(coord[0], coord[1]) else null
        }
        // 2개 이상이면 현재 위치와의 거리가 가장 짧은 좌표 선택 (존재 여부 확인용)
        // 2개 이상이면 현재 위치와의 거리가 가장 짧은 좌표 선택
        var minDistance = Float.MAX_VALUE
        var closestCoord: Pair<Int, Int>? = null

        for (coord in matchingCoords) {
            val dist = distance(arrayOf(currentPos[0], currentPos[1]), coord)
            Log.d("StairDebug", "Checking coord (${coord.first}, ${coord.second}) with distance: $dist")
            if (dist < minDistance) {
                minDistance = dist
                closestCoord = coord
            }
        }

        val candidate = closestCoord ?: return null


        // 추가 기능: 현재 위치를 기준으로 arrivedStairGyroValue의 반대 방향(180° 회전)으로 1.5미터 떨어진 좌표 계산
        // 좌표 1 단위는 0.1미터이므로, 1.5미터는 15 단위임.
        val adjustedAngle = (arrivedStairGyroValue + 180) % 360
        val rad = Math.toRadians(adjustedAngle.toDouble())
        val stepLength = 5  // 미터 단위
        val offset = stepLength * 10  // 좌표 단위 (0.1m 당 1 단위)
        val newX = candidate.first - (Math.sin(rad) * offset).toFloat()
        val newY = candidate.second + (Math.cos(rad) * offset).toFloat()

        return arrayOf(newX, newY)
    }

    // 두 좌표 간의 유클리드 거리 계산 함수
    private fun distance(p1: Array<Float>, p2: Pair<Int, Int>): Float {
        val dx = p1[0] - p2.first.toFloat()
        val dy = p1[1] - p2.second.toFloat()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * 상태 리셋 함수: 계단 관련 상태 변수 초기화
     */
    fun resetDetection() {
        consecutiveSteps = 0
        stairSuspected = false
        stairConfirmed = false
        floorArrival = false
        baselinePressure = null
    }

    /**
     * 현재 상태 문자열 반환 (계단 및 엘리베이터)
     */
    fun getCurrentStairsState(): String {
        return when (stairState){
            3 -> "층 도착"
            2 -> "계단 확정"
            1 -> "계단 의심"
            else -> "계단 아님"
        }
    }

    fun getCurrentElevatorState(): String {
        return when (elevatorState){
            1 -> "엘리베이터 확신"
            2 -> "엘리베이터 도착 (내리지 않음)"
            3 -> "엘리베이터 내림"
            else -> "엘리베이터 아님"
        }
    }

    /**
     * 층 올리기
     */
    fun upperFloor() {
        if (currentFloor == -1) {
            currentFloor = 1
        } else {
            currentFloor = min(currentFloor + 1, 8)
        }
    }

    /**
     * 층 내리기
     */
    fun lowerFloor() {
        if (currentFloor == 1) {
            currentFloor = -1
        } else {
            currentFloor = max(currentFloor - 1, -2)
        }
    }
}



/**
 *
 * 주머니속 필터링을 위한 클래스
 *
 */

class RealtimeLowpassFilter(val RTalpha: Float = 0.05f) {
    private var lastTime: Float? = null
    private var s1 = 0f
    private var s2 = 0f
//    private var s3 = 0f
//    private var s4 = 0f

    fun process(sample: Float, bias: Float = 0f): Float {
        if (lastTime == null) {
            s1 = sample
            s2 = sample
//            s3 = sample
//            s4 = sample
            lastTime = 0f
            return s2 + bias
        }
        s1 += RTalpha * (sample - s1)
        s2 += RTalpha * (s1 - s2)
        //s3 += RTalpha * (s2 - s3)
        //s4 += RTalpha * (s3 - s4)

        return s2
    }
}
