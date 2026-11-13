package app.memoryvault.spike

import android.Manifest
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale

/** MV-02 only: deliberately kept out of the main/release source set. */
class ParkedProbeActivity : PlatformProbeActivity(false)

/** Minimal DO Activity for observing the OS gate, without production controls. */
class DrivingProbeActivity : PlatformProbeActivity(true)

open class PlatformProbeActivity(private val driving: Boolean) : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var result: TextView
    private val events = ArrayDeque<String>()
    private var carProbe: CarSignalProbe? = null
    private var speech: LocalSpeechProbe? = null
    private var supportProbe: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)
        }
        column.addView(TextView(this).apply {
            text = if (driving) "MV-02 · 주행 제한 화면 (DO)" else "MV-02 · 주차 진단 화면 (non-DO)"
            textSize = 28f
        })
        status = TextView(this).apply { textSize = 18f }
        result = TextView(this).apply { textSize = 22f }
        if (!driving) {
            val buttons = LinearLayout(this)
            fun button(label: String, action: () -> Unit) {
                buttons.addView(Button(this).apply { text = label; setOnClickListener { action() } })
            }
            button("권한 요청") { requestProbePermissions() }
            button("로컬 STT 준비") { prepareSpeech() }
            button("15초 발화 시작") { speech?.start() ?: event("STT_NOT_INITIALIZED") }
            button("중지·결과 지우기") { speech?.close(); speech = null; result.text = "" }
            column.addView(buttons)
            val second = LinearLayout(this)
            second.addView(Button(this).apply {
                text = "Car 연결 해제"
                setOnClickListener { carProbe?.close(); carProbe = null }
            })
            second.addView(Button(this).apply {
                text = "Car 다시 연결"
                setOnClickListener { connectCar() }
            })
            second.addView(Button(this).apply {
                text = "Android 로컬 인식 지원 확인"
                setOnClickListener { checkAndroidLocalSupport() }
            })
            second.addView(Button(this).apply {
                text = "DO 화면 열기"
                setOnClickListener { startActivity(Intent(this@PlatformProbeActivity, DrivingProbeActivity::class.java)) }
            })
            column.addView(second)
            column.addView(result)
        }
        column.addView(status)
        setContentView(ScrollView(this).apply {
            addView(column, ViewGroup.LayoutParams(-1, -2))
        })
        event("ACTIVITY_CREATE kind=${if (driving) "DRIVING_DO" else "PARKED_NON_DO"}")
    }

    override fun onStart() {
        super.onStart()
        event("ACTIVITY_START")
        connectCar()
        handleProbeAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProbeAction(intent)
    }

    // Explicit debug-only commands let the controller synchronize live speech.
    // No arbitrary text is accepted, persisted or written to diagnostics.
    private fun handleProbeAction(intent: Intent) {
        if (driving) return
        when (intent.getStringExtra("mv02_action")) {
            "permissions" -> requestProbePermissions()
            "prepare_stt" -> prepareSpeech()
            "start_stt" -> speech?.start() ?: event("STT_NOT_INITIALIZED")
            "stop_stt" -> { speech?.close(); speech = null; result.text = "" }
            "disconnect_car" -> { carProbe?.close(); carProbe = null }
            "connect_car" -> connectCar()
            "android_support" -> checkAndroidLocalSupport()
            "vendor_sentinel" -> VendorLogSentinel.run(handler) { event(it) }
        }
        intent.removeExtra("mv02_action")
    }

    override fun onStop() {
        speech?.close(); speech = null
        result.text = ""
        supportProbe?.destroy(); supportProbe = null
        carProbe?.close(); carProbe = null
        event("ACTIVITY_STOP")
        super.onStop()
    }

    private fun event(message: String) {
        // Only controlled event names, enum values and vehicle diagnostic numbers.
        Log.i("MV02", message)
        runOnUiThread {
            events.addLast(message)
            while (events.size > 23) events.removeFirst()
            status.text = events.joinToString("\n")
        }
    }

    private fun connectCar() {
        carProbe?.close()
        try {
            Class.forName("android.car.Car")
            event("CAR_CLASS_LINKED")
            carProbe = CarSignalProbe(this, handler, ::event).also { it.connect() }
        } catch (failure: Exception) {
            event("CAR_LINK_FAILURE type=${failure.javaClass.simpleName} state=UNKNOWN")
        } catch (failure: LinkageError) {
            event("CAR_LINK_FAILURE type=${failure.javaClass.simpleName} state=UNKNOWN")
        }
    }

    private fun requestProbePermissions() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, "android.car.permission.CAR_SPEED")
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        event("PERMISSIONS missing=${missing.size} stt=${checkSelfPermission("pleos.car.permission.STT_SERVICE")}")
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 2)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        event("PERMISSION_RESULT granted=${results.count { it == PackageManager.PERMISSION_GRANTED }} requested=${results.size}")
        connectCar()
    }

    private fun prepareSpeech() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            event("STT_RECORD_AUDIO_NOT_GRANTED"); return
        }
        speech?.close()
        try {
            speech = LocalSpeechProbe(this, handler, ::event) { text ->
                runOnUiThread { result.text = text }
            }.also { it.initialize() }
        } catch (failure: Exception) {
            event("STT_SETUP_FAILURE type=${failure.javaClass.simpleName}")
        } catch (failure: LinkageError) {
            event("STT_SETUP_FAILURE type=${failure.javaClass.simpleName}")
        }
    }

    private fun checkAndroidLocalSupport() {
        if (Build.VERSION.SDK_INT < 31) { event("ANDROID_ON_DEVICE_API_UNAVAILABLE"); return }
        val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        event("ANDROID_ON_DEVICE_AVAILABLE=$available")
        if (!available || Build.VERSION.SDK_INT < 33) return
        supportProbe?.destroy()
        supportProbe = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { recognizer ->
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            recognizer.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    event("ANDROID_KOREAN_INSTALLED=${support.installedOnDeviceLanguages.any { it.startsWith("ko") }} supported=${support.supportedOnDeviceLanguages.any { it.startsWith("ko") }} pending=${support.pendingOnDeviceLanguages.any { it.startsWith("ko") }}")
                }
                override fun onError(error: Int) { event("ANDROID_SUPPORT_ERROR=$error") }
            })
        }
    }
}

private class CarSignalProbe(
    private val activity: Activity,
    private val handler: Handler,
    private val event: (String) -> Unit,
) {
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var properties: CarPropertyManager? = null
    private var active = true
    private var ready = false
    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (active && ready) recordProperty("CALLBACK", value)
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            if (active) event("PROPERTY_ERROR id=$propertyId area=$areaId state=UNKNOWN")
        }
    }

    fun connect() {
        event("CAR_CONNECTING state=UNKNOWN")
        car = Car.createCar(activity, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, isReady ->
            if (!active) return@createCar
            ready = isReady
            event("CAR_LIFECYCLE ready=$isReady state=${if (isReady) "READING" else "UNKNOWN"}")
            if (!isReady) { ux = null; properties = null; return@createCar }
            try {
                ux = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                ux?.registerListener { restriction -> if (active && ready) recordUx("CALLBACK", restriction) }
                val current = ux?.currentCarUxRestrictions
                if (current == null) event("UX_CURRENT_NULL state=UNKNOWN") else recordUx("CURRENT", current)
            } catch (failure: Exception) {
                event("UX_FAILURE type=${failure.javaClass.simpleName} state=UNKNOWN")
            }
            try {
                properties = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                val manager = properties ?: run { event("PROPERTY_MANAGER_NULL state=UNKNOWN"); return@createCar }
                listOf(VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.PERF_VEHICLE_SPEED).forEach { id ->
                    try {
                        val config = manager.getCarPropertyConfig(id)
                        event("PROPERTY_CONFIG id=$id supported=${config != null}")
                        val value = manager.getProperty<Any>(id, 0)
                        if (value == null) event("PROPERTY_NULL id=$id state=UNKNOWN") else recordProperty("CURRENT", value)
                        val registered = manager.registerCallback(callback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                        event("PROPERTY_SUBSCRIBE id=$id accepted=$registered")
                    } catch (failure: Exception) {
                        event("PROPERTY_FAILURE id=$id type=${failure.javaClass.simpleName} state=UNKNOWN")
                    }
                }
            } catch (failure: Exception) {
                event("PROPERTY_MANAGER_FAILURE type=${failure.javaClass.simpleName} state=UNKNOWN")
            }
        }
    }

    private fun recordUx(source: String, restriction: CarUxRestrictions) {
        event("UX_$source requiresDO=${restriction.isRequiresDistractionOptimization} mask=${restriction.activeRestrictions}")
    }

    private fun recordProperty(source: String, value: CarPropertyValue<*>) {
        val valid = value.status == CarPropertyValue.STATUS_AVAILABLE
        val number = if (valid) (value.value as? Number)?.toString() ?: "UNKNOWN" else "UNKNOWN"
        event("PROPERTY_$source id=${value.propertyId} area=${value.areaId} status=${value.status} value=$number ts=${value.timestamp}")
    }

    fun close() {
        if (!active) return
        active = false
        ready = false
        try { ux?.unregisterListener() } catch (_: Exception) { event("UX_UNREGISTER_FAILURE") }
        try { properties?.unregisterCallback(callback) } catch (_: Exception) { event("PROPERTY_UNREGISTER_FAILURE") }
        ux = null; properties = null
        car?.disconnect(); car = null
        event("CAR_LOCAL_DISCONNECT state=UNKNOWN")
    }
}

private class LocalSpeechProbe(
    activity: Activity,
    private val handler: Handler,
    private val event: (String) -> Unit,
    private val display: (String) -> Unit,
) {
    private val sdk = SpeechToText(activity, Mode.ON_DEVICE)
    private var active = true
    private var initialized = false
    private var listening = false
    private var finalCount = 0
    private var rmsCount = 0
    private var maxRms = 0.0
    private val recordingLimit = Runnable {
        if (active && listening) {
            event("STT_RECORDING_LIMIT_15S")
            sdk.stop()
            listening = false
        }
    }
    private val resultLimit = Runnable {
        if (active) { event("STT_RESULT_WAIT_LIMIT_20S finalCount=$finalCount"); close() }
    }
    private val listener = object : ResultListener {
        override fun onReady() { if (active) event("STT_READY") }
        override fun onStartedRecognition() { if (active) event("STT_STARTED") }
        override fun onUpdated(stt: String, completed: Boolean) {
            if (!active) return
            if (completed) finalCount++
            display(stt)
            event("STT_UPDATED completed=$completed finalCount=$finalCount chars=${stt.length}")
        }
        override fun onUpdatedEpdData(on: Long, off: Long) { if (active) event("STT_EPD on=$on off=$off") }
        override fun onUpdatedRms(rms: Double) {
            if (!active) return
            rmsCount++; maxRms = maxOf(maxRms, rms)
            if (rmsCount % 25 == 0) event("STT_RMS count=$rmsCount max=$maxRms")
        }
        override fun onEndedRecognition() {
            if (!active) return
            listening = false
            handler.removeCallbacks(recordingLimit)
            event("STT_ENDED finalCount=$finalCount rmsCount=$rmsCount maxRms=$maxRms")
        }
        override fun onError(errorType: ErrorType) { if (active) event("STT_ERROR type=${errorType.name}") }
    }

    fun initialize() {
        event("STT_INITIALIZING mode=ON_DEVICE locale=ko-KR")
        sdk.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                if (!active) return
                initialized = true
                sdk.setLocale(Locale.KOREA)
                sdk.addListener(listener)
                event("STT_INITIALIZED mode=ON_DEVICE locale=ko-KR")
            }
            override fun onFailure(errorType: ErrorType) { if (active) event("STT_INIT_FAILURE type=${errorType.name}") }
        })
    }

    fun start() {
        if (!active || !initialized || listening) { event("STT_REQUEST_REJECTED_NOT_READY"); return }
        finalCount = 0; rmsCount = 0; maxRms = 0.0
        handler.removeCallbacks(recordingLimit)
        handler.removeCallbacks(resultLimit)
        display("")
        listening = true
        event("STT_REQUEST mode=ON_DEVICE")
        sdk.request()
        handler.postDelayed(recordingLimit, 15_000)
        handler.postDelayed(resultLimit, 20_000)
    }

    fun close() {
        if (!active) return
        active = false
        handler.removeCallbacks(recordingLimit)
        handler.removeCallbacks(resultLimit)
        if (initialized) {
            sdk.stop()
            sdk.removeListener(listener)
        }
        sdk.release()
        initialized = false; listening = false
        event("STT_RELEASED finalCount=$finalCount rmsCount=$rmsCount")
    }
}
