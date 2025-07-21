package com.example.zeromapapptemplate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.example.heropdr.HeroPDR
import com.example.heropdr.PDR
import com.fifth.maplocationlib.MapMatching
import com.kircherelectronics.fsensor.observer.SensorSubject
import com.kircherelectronics.fsensor.sensor.FSensor
import com.kircherelectronics.fsensor.sensor.gyroscope.GyroscopeSensor
import kotlinx.coroutines.*
import com.unity3d.player.IUnityPlayerLifecycleEvents
import com.unity3d.player.UnityPlayer
import com.fifth.maplocationlib.sensors.GyroscopeResetManager
import com.fifth.maplocationlib.NativeLib
import com.fifth.maplocationlib.UserStateMonitor
import com.fifth.maplocationlib.sensors.MovingAverage
import com.example.zeromapapptemplate.R
import java.util.LinkedList
import kotlin.math.asin
import kotlin.math.atan2
import com.fifth.maplocationlib.inpocketPDR.InPocketStep

//20250315 동근 추가
import com.fifth.maplocationlib.inpocketPDR.HandHeldSwing
import com.fifth.maplocationlib.inpocketPDR.PDR as InpocketPDR
//여기까지


import com.fifth.maplocationlib.wifiengine.RFlocalization
import android.net.wifi.WifiManager
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.view.WindowManager//0421동근
import com.fifth.maplocationlib.RotationPatternDetector //0406윤동근
import com.fifth.maplocationlib.utils.StairsAreaProvider

///// 앱 - 서버 통신용 라이브러리 - 0324 김명권 ///////////
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import java.io.IOException
import com.google.android.material.appbar.AppBarLayout.BaseOnOffsetChangedListener
import java.io.File
import java.util.Queue
import kotlin.math.round

data class LocationData(    // 데아터 전송용 모델 클래수 추가 - 0324 김명권
    val user_uid: Int,
    val user_x: Float,
    val user_y: Float,
    val user_floor: Int,
    val user_statereal: Int,
    val user_status: Boolean,
)

class MainActivity : AppCompatActivity(), SensorEventListener, IUnityPlayerLifecycleEvents{
    private var elevationMode: Int = 0
    private var appStartTime: Long = 0
    private var floorqueue: Queue<Int> = LinkedList(listOf(0,0,0,0,0,0,0,0,0,0))
    private var uid_save: Int = -1 // uid 저장 변수 - 0325 김명권
    private var isAppRunning: Boolean = false // 앱 실행 상태를 저장할 변수 - 0325 김명권
    private lateinit var locationData: LocationData // 데이터 전송 구조체 - 0325 김명권
    private var stairsHardResetFlag: Boolean = false
    private var calibratedGyroAngle: Float = 0.0f
    private var gyroResetFlag = false
    private var initialized: Boolean = false
    private var res_distance: Float = 10000.0f

    // 계단 영역 정의: 층 정보를 키로, 여러 계단 영역의 리스트를 값으로 가지는 Map
    private lateinit var stairsArea: Map<Int, List<FloatArray>>


    private var mapBasedGyroAngleCaliValue: Int = 0
    private lateinit var mUnityPlayer: UnityPlayer
    private lateinit var unityLayout: FrameLayout
    protected fun updateUnityCommandLineArguments(cmdLine: String?): String? {
        return cmdLine
    }
    private lateinit var mapMatching: MapMatching

    private var cur_pos: Array<Float> = arrayOf(0.0f, 0.0f)
    private var compassDirection = 0.0f
    private var cur_floor_int = 0
    private var pastFloorCngDetResult: Int = 0
    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator
    private var isFistInit: Boolean = true
    private var magMatrix = FloatArray(3)
    private var accMatrix = FloatArray(3)
    private val heroPDR: HeroPDR = HeroPDR()
    private var gravMatrix = FloatArray(3)
    private lateinit var pdrResult: Any
    private var stepLength: Float = 0.0f
    private var stepCount: Int = 0
    private val nativeLib: NativeLib = NativeLib()
    private lateinit var floorChangeDetection: com.fifth.maplocationlib.FloorChangeDetection
    private lateinit var IPpressurefilter: com.fifth.maplocationlib.RealtimeLowpassFilter //0421동근
    private lateinit var fSensor: FSensor
    private var fusedOrientation = FloatArray(3)
    private var mapBasedGyroAngle : Float = 0.0f
    private var angletmp: Float = 0.0f //0406윤동근 추가
    private var gyroCaliValue = 0.0f
    private var didConverge = false
    private val sensorObserver = SensorSubject.SensorObserver { values -> updateValues(values!!) }
    private fun updateValues(values: FloatArray) {
        // 250219 원준 : 문장 삭제
//        if (values[0] == -90.0f || values[0] == 90.0f) {
//            fusedOrientation[0] = 0.0f
//            fusedOrientation[1] = values[1]
//            fusedOrientation[2] = values[2]
//        } else {
//            fusedOrientation = values
//        }

        // 250219 원준 : 아래 문장 추가
        fusedOrientation = values
    }
    var isSensorStabled: Boolean = false
    private var accStableCount: Int = 50
    private var gyroStableCount: Int = 200
    private var pressureEvent: SensorEvent? = null
    private var pressureBias: Float = 0.48f//0421동근
    private var accelEvent: SensorEvent? = null
    //    private var liteEvent: SensorEvent? = null // 250219 원준 : 문장 삭제
    private var searchRange = 99999
    private var centerX = 0
    private var centerY = 0
    private var cur_floor = "0"

    private val mHandler: Handler = Handler(Looper.myLooper()!!)
//    private lateinit var webView : WebView
//    private val HTML_FILE = "file:///android_asset/index.html"


    private lateinit var userStateMonitor: UserStateMonitor
    //20250315 동근 수정 Boolean->Any
    // private var currentUserStates: Map<String, Boolean> = mapOf()
    private var currentUserStates: Map<String, Any> = mapOf()
    private var currentPosition: Array<Float> = arrayOf(0.0f, 0.0f)
    var distance =0.0f
    private var currentFloor : Int = 0
    private var previousFloor : Int = 0
    private var previousPhoneState: Int = 0

    val headQueue: LinkedList<Float> = LinkedList(listOf(0f,0f,0f,0f)) // 250219 원준 : 문장 추가
    val stepQueue: LinkedList<Float> = LinkedList(listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)) // 250219 원준 : 문장 추가
    // 250219 원준 : 아래 문장 추가
    val quaternionQueue: LinkedList<FloatArray> = LinkedList(
        listOf(
            floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
        )
    )

    var rotangle = floatArrayOf(0f,0f,0f,0f)
    private var rotateCaliValue = 0.0f
    private val rotationMovingAveragex: MovingAverage = MovingAverage(10)
    private val rotationMovingAveragey: MovingAverage = MovingAverage(10)
    private val rotationMovingAveragez: MovingAverage = MovingAverage(10)
    private val rotationMovingAveragew: MovingAverage = MovingAverage(10)

    //0406윤동근 추가
    //var statequeue: LinkedList<Int> = LinkedList(listOf(0,0,0,0,0,0))
    var statequeue: LinkedList<Int> = LinkedList(listOf(0,0,0,0,0))

    var statereal: Int = 0
    var statetmp: Int = 0
    var stateflag: Boolean = false
    //0406윤동근여기까지

    private fun getLogFile(directory: File, baseName: String, extension: String): File {//로그 출력용
        val initialFile = File(directory, "$baseName$extension")
        if (!initialFile.exists()) {
            return initialFile
        }
        var fileIndex = 1
        var file: File
        do {
            val fileName = "${baseName}$fileIndex$extension"
            file = File(directory, fileName)
            fileIndex++
        } while (file.exists())
        return file
    }
    private var sensorLogFiles: MutableMap<Int, File> = mutableMapOf()


    // 250219 원준 : 아래 문장 추가
    private val inpocketStep: InPocketStep by lazy {
        InPocketStep(floorChangeDetection)
    }
    //20250315 동근 아래 변수 추가
    private val handheldswing: HandHeldSwing by lazy {
        HandHeldSwing(floorChangeDetection)
    }

    private var lightEventvalue: Float = 100.0f

    private var rotationPatternDetector: RotationPatternDetector? = null //0406윤동근 추가

    private val gyroResetScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var gyroscopeResetManager: GyroscopeResetManager

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    //동근새로 추가
    private lateinit var rflocalization: RFlocalization
    var rffloor =0.0f
    //여기까지

    var filteredpressure = 999.0f //0421동근
    private var lastPressureForUpdate: Float = 0f //0421동근
    private var lastStatereal: Int = 0 //0421동근

    data class LocationInfo(
        val x: Float,
        val y: Float,
        val floor: Int,
        val orientation : Float,
        val userstate : Int  // 20250315 동근 Boolean -> Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        val packageName = "com.example.zeromapapptemplate"  // uid 수집용 - 0325 김명권
        val uid = getUidForPackage(packageName)             // uid 수집용 - 0325 김명권
        uid_save = uid                                      // uid 수집용 - 0325 김명권
        // locationData 초기화
        locationData = LocationData( // 유저 정보 초기화 - 0325 김명권
            user_uid = uid_save,
            user_x = 0.0f,
            user_y = 0.0f,
            user_floor = 0,
            user_statereal = 0,
            user_status = true
        )

        val version = NativeLib.version // 버전 정보
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stairsArea = StairsAreaProvider.load(this) // ← 이제 context 안전하게 사용 가능

        // Initialize Unity
        mUnityPlayer = UnityPlayer(this, this)
        val cmdLine = updateUnityCommandLineArguments(intent.getStringExtra("unity"))
        intent.putExtra("unity", cmdLine)
        val glesMode = mUnityPlayer.settings.getInt("gles_mode", 1)
        val trueColor8888 = false
        mUnityPlayer.init(glesMode, trueColor8888)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        unityLayout = findViewById(R.id.unitySurfaceView)
        unityLayout.addView(mUnityPlayer.view, 0, lp)
        mUnityPlayer.requestFocus()

        mapMatching = MapMatching(this)
        mapMatching.initialize()


        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // val binaryMapData = readRawBinaryFile(R.raw.hansando_0f_binarymap)  // 250219 원준 : 문장 삭제
        // val jsonMapData = readJsonFile(R.raw.indoor_map_0f)  // 250219 원준 : 문장 삭제

        // enginePtr = nativeLib.initializeEngine(binaryMapData, jsonMapData) // 250219 원준 : 문장 삭제

        nativeLib.setAssetManager(assets)  // 250219 원준 : 문장 추가
//        nativeLib.initializeEngine() // 250219 원준 : 문장 추가

        floorChangeDetection = com.fifth.maplocationlib.FloorChangeDetection()
        IPpressurefilter = com.fifth.maplocationlib.RealtimeLowpassFilter() //0421동근

        fSensor = GyroscopeSensor(this)
        (fSensor as GyroscopeSensor).register(sensorObserver)
        (fSensor as GyroscopeSensor).start()

//        CoroutineScope(Dispatchers.IO).launch {
//            while (isActive) {
//                printArrowInWebView(mapBasedGyroAngle)
//                delay(arrow_update_time)
//            }
//        }

//        stateDisplayTextView = findViewById(R.id.result_info)
        userStateMonitor = UserStateMonitor(this)

        rotationPatternDetector = RotationPatternDetector()
        rotationPatternDetector?.onStateChanged = {
            val paststate = statereal

            statetmp = userStateMonitor.getStatus(statereal)
            GlobalScope.launch(Dispatchers.Main) {
                delay(1000) // 짧은 딜레이 후
                statetmp = userStateMonitor.getStatus(statereal)
            }
            if(paststate != statetmp) {
                stateflag=true
                gyroCaliValue =
                    (toNormalizedDegree(fusedOrientation[0]) + mapBasedGyroAngleCaliValue - compassDirection + 360) % 360
            }
        }

        rflocalization = RFlocalization(this) //동근새로 추가

        appStartTime = System.currentTimeMillis()

        CoroutineScope(Dispatchers.Main).launch {
            val floorQueue = mutableListOf<Float>() // Queue to track recent floor values
            while (isActive) {
                currentUserStates = userStateMonitor.getStates()
                //동근새로 추가 디버깅용
                var floor = rflocalization.getRfFloor()

                if(floor.isEmpty()){
                    rffloor = 0f
                }else{
                    rffloor = (floor["floor"] as String).toFloat()
//                    rffloor = 1f
                }
                // Add current floor to the queue
                floorQueue.add(rffloor)

                // Keep only the last 3 values
                if (floorQueue.size > 5) {
                    floorQueue.removeAt(0)
                }

                // Check if we have 3 non-zero identical floor values and not initialized yet
                if (rffloor != 0f && !initialized && floorQueue.size == 5 &&
                    floorQueue.all { it == rffloor }) {
                    nativeLib.initializeEngine(floor=rffloor.toInt()) // 250219 원준 : 문장 추가
                    floorChangeDetection.currentFloor = rffloor.toInt()
                    //&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
                    repeat(10) {
                        floorqueue.add(rffloor.toInt())
                        floorqueue.poll()
                    }
                    //&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
                    floorChangeDetection.prevFloor = rffloor.toInt()
                    initialized = true
                }

                delay(1000) // 매 1초마다 사용자 상태 정보 가져오기. 이 지연시간은 조절하셔도 됩니다. 예를 들어 3000 이라고 설정하면, 3초마다 사용자 상태 정보를 가져오게 됩니다.
            }
        }
        checkPermission()

    }

    private fun getUidForPackage(packageName: String): Int {    // uid 수집용 - 0325 김명권
        return try {
            val appInfo: ApplicationInfo = packageManager.getApplicationInfo(packageName, 0)
            appInfo.uid // UID 반환
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e("DeviceInfo", "패키지를 찾을 수 없습니다: $packageName", e)
            -1 // 패키지를 찾을 수 없을 경우 -1 반환
        }
    }

    private fun checkPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
                && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ), 101)
            } else {
                ActivityCompat.requestPermissions(this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ), 101)
            }
        }
    }

    // 250219 원준 : 아래 함수 두개 삭제
//    private fun readRawBinaryFile(resId: Int): ByteArray {
//        return resources.openRawResource(resId).use { it.readBytes() }
//    }
//    private fun readJsonFile(resId: Int): ByteArray {
//        return resources.openRawResource(resId).use { it.readBytes() }
//    }

    override fun onResume() {
        super.onResume()
        mUnityPlayer.resume()
        registerReceiver(rflocalization.wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))//동근새로 추가 와이파이 리시버 등록
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), SensorManager.SENSOR_DELAY_GAME)  // 250213 원준 : SENSOR_DELAY_FASTEST --> SENSOR_DELAY_GAME 으로 수정
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE), SensorManager.SENSOR_DELAY_FASTEST)
//        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_GAME)   // 250219 원준 : 문장 삭제
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT), SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY), SensorManager.SENSOR_DELAY_NORMAL) // 20250315 동근 : 문장 추가
    }

    override fun onPause() {
        super.onPause()
        mUnityPlayer.pause()
        sensorManager.unregisterListener(this)

        // 앱 종료시 상태 전송 0402 김명권
        isAppRunning = false
        val updatedData = locationData.copy(user_status = isAppRunning) // 앱 종료 - 0402 김명권
        sendLocationToServer(updatedData)
    }

    override fun onDestroy() {
        super.onDestroy()
        mUnityPlayer.destroy()
        nativeLib.destroyEngine()  // 250219 원준 : 위 문장을 이 문장으로 대체

        // 앱 종료시 상태 전송 0402 김명권
        isAppRunning = false
        val updatedData = locationData.copy(user_status = isAppRunning) // 앱 종료 - 0402 김명권
        sendLocationToServer(updatedData)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mUnityPlayer.lowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_CRITICAL) {
            mUnityPlayer.lowMemory()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mUnityPlayer.configurationChanged(newConfig)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        mUnityPlayer.windowFocusChanged(hasFocus)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE) return mUnityPlayer.injectEvent(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return mUnityPlayer.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // 볼륨 업 키가 눌렸을 때 실행할 함수
                floorChangeDetection.upperFloor()
                return true // 이벤트가 처리되었음을 시스템에 알림
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // 볼륨 다운 키가 눌렸을 때 실행할 함수
                floorChangeDetection.lowerFloor()
                return true
            }
        }

        return mUnityPlayer.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return mUnityPlayer.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return mUnityPlayer.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
    }

    private fun isReadyLocalization(event: SensorEvent, fusedOrientation: FloatArray): Boolean {
        if (isSensorStabled) {
            return true
        } else {
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    accStableCount += if (event.values[0] in -0.3..0.3 && event.values[1] in -0.3..0.3 && event.values[2] in -0.3..0.3) -1 else 1
                    accStableCount = if (accStableCount > 50) 50 else accStableCount
                }
                Sensor.TYPE_GYROSCOPE -> {
                    gyroStableCount--
                }
                Sensor.TYPE_GAME_ROTATION_VECTOR -> {   // 250219 원준 : TYPE_ROTATION_VECTOR --> TYPE_GAME_ROTATION_VECTOR 로 수정
                    rotangle = getRotationFromQuaternion(event.values)//roll pitch yaw
                }
            }
            if (gyroStableCount <= 0) {
//                rotateCaliValue = rotangle[2] // 250219 원준 : 문장 삭제
                gyroCaliValue = ((Math.toDegrees(fusedOrientation[0].toDouble()) + 360) % 360).toFloat()
            }
            isSensorStabled = accStableCount < 0 && gyroStableCount <= 0
            return isSensorStabled
        }
    }


    override fun onSensorChanged(event: SensorEvent) {
        //0406윤동근 아래 수정
        mapBasedGyroAngle = when(statereal) {
            1, 2 -> {
                (angletmp - rotateCaliValue + mapBasedGyroAngleCaliValue+ 360) % 360
            }
            3 -> {
                ((toNormalizedDegree(fusedOrientation[0]) - gyroCaliValue) + mapBasedGyroAngleCaliValue +180 + 360) % 360
            }
            else -> {
                ((toNormalizedDegree(fusedOrientation[0]) - gyroCaliValue) + mapBasedGyroAngleCaliValue + 360) % 360
            }
        }
        if (!(statereal == 2 || statereal == 1)){
            UnityPlayer.UnitySendMessage("GameObject", "OnReceiveGyroData", mapBasedGyroAngle.toString())//0315 gaeun
            UnityPlayer.UnitySendMessage("Main Camera", "SetCameraYawRotation", mapBasedGyroAngle.toString())//gaeun showmyposition
        }
        //0406윤동근 여기까지


        var hasWalked = false
        if ((event ?: false).let { isReadyLocalization(event!!, fusedOrientation) }) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> accMatrix = event.values.clone()
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magMatrix = event.values.clone()
                }
                Sensor.TYPE_GRAVITY -> gravMatrix = event.values.clone()
                Sensor.TYPE_PRESSURE -> {
                    //0421동근 내부 대폭변경
                    pressureEvent = event
                    val currentTime = System.currentTimeMillis()
                    val elapsedSeconds = (currentTime - appStartTime).toFloat() / 1000f
                    val rawPressure = event.values[0]
                    filteredpressure = IPpressurefilter.process(rawPressure)
                    //pressureBias=0f
                    if (previousPhoneState != statereal) {
//                        if(statereal != statetmp){
//                            pressureBias = lastPressureForUpdate - IPpressurefilter.process(filteredpressure)
//                        }
//                        else
                        if(statereal == 2) {
                            pressureBias = lastPressureForUpdate - filteredpressure
                        } else if (statereal == 0) {
                            pressureBias = lastPressureForUpdate - rawPressure
                        }
                    }
                    val pressureForUpdate = if (statereal != statetmp) IPpressurefilter.process(filteredpressure) + pressureBias else if (statereal == 2) filteredpressure + pressureBias else rawPressure + pressureBias

                    floorChangeDetection.updatePressureData(pressureForUpdate, elapsedSeconds, mapBasedGyroAngle)
                    Log.d("tttest", "$currentFloor")
                    lastPressureForUpdate = pressureForUpdate
                    lastStatereal = statereal

                    val directory = this.filesDir
                    val logData = "${rawPressure},${pressureForUpdate},${elapsedSeconds},${statetmp},$statereal\n"
                    try {
                        val logFile = sensorLogFiles.getOrPut(Sensor.TYPE_PRESSURE) {
                            getLogFile(directory, "pressure_log", ".txt")
                        }
                        logFile.appendText(logData)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }

                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    accelEvent = event
                    var currentValues = event.values.clone()
//                    floorChangeDetection.updateAccelData(accelEvent!!.values)
                    userStateMonitor.updateAccelData(event)
                    //if (!inpocketing) {//0406윤동근 if문 삭제
                    val isstep = when (statetmp) {//0406윤동근 변수 수정
                        0 -> {//수평파지
                            heroPDR.isStep(currentValues.clone(), accMatrix)
                        }

                        1 -> {//쥐고 손에 흔들기
                            handheldswing.isStep(rotangle, stepQueue)
                        }

                        2 -> {//주머니속
                            //inpocketStep.isStep(rotangle, stepQueue, mapBasedGyroAngle) //0406윤동근 삭제
                            inpocketStep.isStep(rotangle, stepQueue)
                        }

                        3 -> {//전화(other)
                            heroPDR.isStep(currentValues, accMatrix)
                        }

                        else -> {
                            false
                        }
                    }
                    //여기까지

                    if (isstep) {
                        if(statequeue.all { it == statequeue.first() }){ //0406윤동근 추가
                            headQueue.add(mapBasedGyroAngle)
                            headQueue.pop()
                        }
                        vibrator.vibrate(30)
                        //0406윤동근 아래 when문 복붙 하시는게 편할것 같습니다.
                        when (statereal) {
                            0, 3 -> {
                                pdrResult = heroPDR.getStatus()
                                stepCount = (pdrResult as PDR).totalStepCount
                                stepLength = (pdrResult as PDR).stepLength.toFloat() - 0.035f
                                stepQueue.add(stepLength)
                                stepQueue.pop()
                                hasWalked = true
                            }
                            2 -> {
                                pdrResult = inpocketStep.getStatus()
                                stepCount = (pdrResult as InpocketPDR).totalStepCount
                                stepLength = (pdrResult as InpocketPDR).stepLength.toFloat()
                                mapBasedGyroAngle = ((pdrResult as InpocketPDR).direction.toFloat()- rotateCaliValue + mapBasedGyroAngleCaliValue+ 360) % 360

                                angletmp = (pdrResult as InpocketPDR).direction.toFloat()
                                hasWalked = true
                                UnityPlayer.UnitySendMessage(
                                    "GameObject",
                                    "OnReceiveGyroData",
                                    mapBasedGyroAngle.toString()
                                )
                                UnityPlayer.UnitySendMessage("Main Camera", "SetCameraYawRotation", mapBasedGyroAngle.toString())//gaeun showmyposition
                            }
                            1 -> {
                                pdrResult = handheldswing.getStatus()
                                stepCount = (pdrResult as InpocketPDR).totalStepCount
                                stepLength = (pdrResult as InpocketPDR).stepLength.toFloat()
                                mapBasedGyroAngle = ((pdrResult as InpocketPDR).direction.toFloat() - rotateCaliValue + mapBasedGyroAngleCaliValue+ 360) % 360

                                angletmp = (pdrResult as InpocketPDR).direction.toFloat()
                                hasWalked = true
                                UnityPlayer.UnitySendMessage(
                                    "GameObject",
                                    "OnReceiveGyroData",
                                    mapBasedGyroAngle.toString()
                                )
                                UnityPlayer.UnitySendMessage("Main Camera", "SetCameraYawRotation", mapBasedGyroAngle.toString())//gaeun showmyposition
                            }
//                            //&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
//                            //걸음마다 rf층 정보를 큐잉함
//                            floorqueue.add(rffloor.toInt())
//                            floorqueue.poll()
//                            //&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
                        }
                    }
                }

                Sensor.TYPE_LIGHT -> {
//                    lightEventvalue = event.values[0]//20250315 동근 light 오타 수정
                    lightEventvalue = event.values[0]                    //isinPocket = userStateMonitor.inpoketDetect(liteEventvalue) //20250315 동근 문장 삭제
                    userStateMonitor.updateLightData(lightEventvalue) // 20250315 동근 문장 추가
                }
                Sensor.TYPE_PROXIMITY -> {
                    distance = event.values[0]
                    userStateMonitor.updateProximityData(distance)
                }
                Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    var currentValues: FloatArray
                    if (statereal==2 || statereal == 1) { //0406윤동근 수정
                        val tmp = event.values.clone()
                        rotationMovingAveragex.newData(tmp[0])
                        rotationMovingAveragey.newData(tmp[1])
                        rotationMovingAveragez.newData(tmp[2])
                        rotationMovingAveragew.newData(tmp[3])
                        currentValues = floatArrayOf(
                            rotationMovingAveragex.getAvg().toFloat(), rotationMovingAveragey.getAvg().toFloat(),
                            rotationMovingAveragez.getAvg().toFloat(), rotationMovingAveragew.getAvg().toFloat()
                        )
                    } else {
                        currentValues = event.values.clone()
                        quaternionQueue.add(currentValues)
                        quaternionQueue.pop()
                    }
                    var rotvalue = currentValues
                    val nowMs = System.currentTimeMillis()
                    rotangle = getRotationFromQuaternion(rotvalue)
                    rotationPatternDetector?.addSample(rotangle[0], rotangle[1])//0406윤동근 추가


                    userStateMonitor.updateRotationData(rotangle)

                    heroPDR.setQuaternion(rotvalue)
                    if (quaternionQueue.size > 5) {
                        quaternionQueue.poll()
                    }
                }

            }

            UnityPlayer.UnitySendMessage("GameObject", "OnReceiveState", statereal.toString())
            // 0421동근 주머니속일때 밝기 최소
            window.attributes = window.attributes.apply {
                screenBrightness =
                    if (statereal == 2 || statetmp == 2)
                        0f
                    else
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }

            if (isFistInit && isSensorStabled) {
                Toast.makeText(this, "지금부터 이동을 시작해주세요.", Toast.LENGTH_SHORT).show()
                vibrator.vibrate(160)
                isFistInit = false
            }

            if (hasWalked) {
                //0406윤동근 추가
                statequeue.add(statetmp)
                statequeue.poll()
                val moststate = statequeue
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                if (moststate != null) {
                    statereal = moststate
                }
                if(stateflag && statequeue.all { it == statequeue.first() }){
                    stateflag = false
                    rotateCaliValue = ((angletmp - headQueue.peek()!! + mapBasedGyroAngleCaliValue)+360)%360

                    nativeLib.reSearchStart()

                }
                //0406윤동근 여기까지
                calculateCompassDirection()

                // 다른 자세였다가 기본 자세로 돌아온 순간을 확인
//                if (previousPhoneState != 0 && statereal == 0) {
//                    nativeLib.reSearchStart()
//                }772line에 통합됨

                // 250219 원준 :  위 문장들 아래 문장들로 대체
                CoroutineScope(Dispatchers.Default).launch {
                    if (statequeue.all { it == statequeue.first() }) {//0406윤동근 if문 추가
                        Log.d("codetime", "OK1")
                        val location = updateLocation(stepLength, mapBasedGyroAngle,  compassDirection,  stepCount)
                        Log.d("codetime", "OK4")
                        val mapMatchingLocation = mapMatching.getMapMatchingResult(location.x, location.y, location.floor)
                        currentPosition = arrayOf(mapMatchingLocation.x, mapMatchingLocation.y)
                        withContext(Dispatchers.Main) {
                            if ((location.x > 0.0f) && (location.y > 0.0f)) {   // 250219 원준 : 중요! location.x 와 location.y 일 때에는 해당 좌표값을 사용하면 안됩니다. (x,y)가 (-1.0, -1.0)이라는 것은 비정상적인 좌표값임을 나타냅니다.
//                            printDotInWebView(location.x, location.y, location.floor) // location.x / location.y / location.floor / location.orientation 가 각각 x좌표, y좌표, 층 정보, 이동 방향 정보를 갖고 있습니다.
                                UnityPlayer.UnitySendMessage( "GameObject", "ShowMyPosition", "${Math.round(mapMatchingLocation.x)}\t${Math.round(mapMatchingLocation.y)}\t${mapMatchingLocation.floor}\t${Math.round(res_distance)}")//gaeun showmyposition
                                // 변수 저장 & 송신 - 0324 김명권
                                locationData = LocationData(
                                    user_uid = uid_save,
                                    user_x = location.x,
                                    user_y = location.y,
                                    user_floor = location.floor,
                                    user_statereal = statereal,
                                    user_status = isAppRunning
                                )
                                sendLocationToServer(locationData)
                            }//0406윤동근 if문 닫기
                        }
                    }
                    else{
                        val location = updateLocation(stepQueue.peek(), headQueue.peek(),  compassDirection,  stepCount)
                        Log.d("result1 location", "${location.x}, ${location.y}")
                        val mapMatchingLocation = mapMatching.getMapMatchingResult(location.x, location.y, location.floor)
                        Log.d("result1 mapMatchi", "${mapMatchingLocation.x}, ${mapMatchingLocation.y}")
                        currentPosition = arrayOf(mapMatchingLocation.x, mapMatchingLocation.y)
                        withContext(Dispatchers.Main) {
                            if ((location.x > 0.0f) && (location.y > 0.0f)) {   // 250219 원준 : 중요! location.x 와 location.y 일 때에는 해당 좌표값을 사용하면 안됩니다. (x,y)가 (-1.0, -1.0)이라는 것은 비정상적인 좌표값임을 나타냅니다.
                                UnityPlayer.UnitySendMessage( "GameObject", "ShowMyPosition", "${Math.round(mapMatchingLocation.x)}\t${Math.round(mapMatchingLocation.y)}\t${mapMatchingLocation.floor}\t${Math.round(res_distance)}")//gaeun showmyposition
                                locationData = LocationData(
                                    user_uid = uid_save,
                                    user_x = location.x,
                                    user_y = location.y,
                                    user_floor = location.floor,
                                    user_statereal = statereal,
                                    user_status = isAppRunning
                                )
                                sendLocationToServer(locationData)
                            }//0406윤동근 if문 닫기
                        }

                    }
                }

                previousPhoneState = statereal

            }
        }
    }

    override fun onStart() { // 앱이 시작됨을 감지 - 0325 김명권
        super.onStart()
        isAppRunning = true
        val updatedData = locationData.copy(user_status = isAppRunning) // 앱 시작 시간 - 0402 김명권
        sendLocationToServer(updatedData)
    }

    private fun sendLocationToServer(locationData: LocationData) { // 서버 통신 부 - 0324 김명권
        val client = OkHttpClient()
        val gson = Gson()
        val json = gson.toJson(locationData)
        val requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("http://163.152.52.123:3896/INB") // 서버의 URL & 포트 & API 엔드포인트
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d("MainActivity", "Data sent successfully: ${response.body?.string()}")
                } else {
                    Log.e("MainActivity", "Failed to send data: ${response.message}")
                }
            }

            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("MainActivity", "Error: ${e.message}")
            }
        })
    }

    private fun updateLocation(stepLength: Float, mapBasedGyroAngle: Float, compassDirection: Float, stepCount: Int): LocationInfo {
        floorChangeDetection.updateGyroData(mapBasedGyroAngle)
        currentFloor = floorChangeDetection.currentFloor

        if ((floorChangeDetection.getCurrentStairsState() == "계단 확정") || (floorChangeDetection.getCurrentStairsState() == "층 도착")) {
            elevationMode = 0
        }
        else if ((floorChangeDetection.getCurrentElevatorState() == "엘리베이터 확신") || (floorChangeDetection.getCurrentElevatorState() == "엘리베이터 도착 (내리지 않음)") || (floorChangeDetection.getCurrentElevatorState() == "엘리베이터 내림")) {
            elevationMode = 2
        }

        if (hasFloorChanged()) {
            searchRange = 50
            stairsHardResetFlag = false
        }
        else {
            if (!stairsHardResetFlag && floorChangeDetection.getCurrentStairsState() == "계단 확정") {
                var starisCoord = floorChangeDetection.setStairsInfo(currentPosition, currentFloor, floorChangeDetection.arrivedStairGyroValue, floorChangeDetection.elevationStatus)
                nativeLib.reSearchStartInStairs(starisCoord!![0].toInt(), starisCoord[1].toInt())
                stairsHardResetFlag = true
            }
        }
        previousFloor = currentFloor

        // 현재 좌표가 계단 영역에 있는지 확인
        var isInStairsArea = false
        val floorStairsAreas = stairsArea[currentFloor]
        if (floorStairsAreas != null) {
            for (area in floorStairsAreas) {
                val x_min = area[0]
                val y_min = area[1]
                val x_max = area[2]
                val y_max = area[3]

                if (cur_pos[0] >= x_min && cur_pos[0] <= x_max &&
                    cur_pos[1] >= y_min && cur_pos[1] <= y_max) {
                    isInStairsArea = true
                    break
                }
            }
        }

        // 계단 영역에 있다면 걸음 길이 조정
        var adjustedStepLength = stepLength
        if (isInStairsArea && (res_distance < 4.0f)) {
            adjustedStepLength = 0.3f
        }

        if (initialized) {
            var arrivedGyroValue = floorChangeDetection.arrivedStairGyroValue
            if (elevationMode == 2) {
                arrivedGyroValue = floorChangeDetection.arrivedElevatorGyroValue
            }
            val result: FloatArray? = nativeLib.processStep(mapBasedGyroAngle-mapBasedGyroAngleCaliValue, compassDirection, adjustedStepLength, stepCount, currentFloor, arrivedGyroValue, elevationMode)
            if (result != null) {
                centerX = result[0].toInt()
                centerY = result[1].toInt()
                res_distance = result[2]
                mapBasedGyroAngleCaliValue = result[3].toInt()
                cur_pos = arrayOf(centerX.toFloat(), centerY.toFloat())
                Log.d("result", "$centerX\t$centerY")
            }
        }


//        //&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
//        if(currentFloor != (rflocalization.getRfFloor())["floor"]){ //현재 층으로 알고 있는 정보와 rf층정보가 불일치하면
//            var mostfloor = floorqueue                              //rffloor큐에서 가장 많은개수의 원소를 추출
//                .groupingBy { it }
//                .eachCount()
//                .maxByOrNull { it.value }
//                ?.key
//            if (mostfloor != null && currentFloor != mostfloor) {   // 그게 현재 층과 다르면
//                floorChangeDetection.currentFloor = mostfloor       // 층 이동 조치후
//                nativeLib.reSearchStart()                           // 재탐색 실시
//            }
//        }
//        //&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&

        return LocationInfo(cur_pos[0], cur_pos[1], currentFloor, mapBasedGyroAngle, statereal) // //동근새로 statetmp -> statereal
    }

    private fun calculateCompassDirection() {
        if (magMatrix.isNotEmpty() && accMatrix.isNotEmpty()) {
            try {
                val rotationMatrix = FloatArray(9)
                val orientationAngles = FloatArray(3)

                SensorManager.getRotationMatrix(rotationMatrix, null, accMatrix, magMatrix)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                compassDirection = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360) % 360).toFloat()
            } catch (e: Exception) {
                Log.e("MainActivity", "나침반 방향 계산 오류: ${e.message}")
            }
        }
    }

    // 250206 원준 : 아래 함수 추가
    private fun multiplyQuaternions(q1: FloatArray, q2: FloatArray): FloatArray {
        val x1 = q1[0]
        val y1 = q1[1]
        val z1 = q1[2]
        val w1 = q1[3]
        val x2 = q2[0]
        val y2 = q2[1]
        val z2 = q2[2]
        val w2 = q2[3]
        return floatArrayOf(
            w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
            w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
            w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2
        )
    }

    // 250206 원준 : 아래 함수 추가
    private fun getRotationFromQuaternion(quaternion: FloatArray): FloatArray {
        val x = quaternion[0]
        val y = quaternion[1]
        val z = quaternion[2]
        val w = quaternion[3]
        val roll = Math.toDegrees(
            atan2(
                2.0 * (w * x + y * z),
                1.0 - 2.0 * (x * x + y * y)
            )
        ).toFloat()
        val pitch = Math.toDegrees(
            asin(
                (2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0) // asin 범위 제한
            )
        ).toFloat()
        val yaw = Math.toDegrees(
            atan2(
                2.0 * (w * z + x * y),
                1.0 - 2.0 * (y * y + z * z)
            )
        ).toFloat()
        return floatArrayOf(roll, pitch, yaw)
    }

    private fun hasFloorChanged(): Boolean {
        return currentFloor != previousFloor
    }


    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
    }

    fun toNormalizedDegree(value: Float): Float = ((Math.toDegrees(value.toDouble()) + 360) % 360).toFloat()  // radian 넣으면 0~360도 사이의 값으로 반환 (radian to degree)


    override fun onUnityPlayerUnloaded() {
        moveTaskToBack(true)
    }
    override fun onUnityPlayerQuitted() {
        // TODO: Implement this method if needed
    }
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        mUnityPlayer.newIntent(intent)
    }

    private fun updateUI(x: Double, y: Double) {
        // UI 업데이트 로직
    }

}