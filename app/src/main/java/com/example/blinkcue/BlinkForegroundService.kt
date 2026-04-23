package com.example.blinkcue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BlinkForegroundService : Service(), LifecycleOwner {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isAnalyzing = AtomicBoolean(false)
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    private val cameraRetryRunnable = Runnable {
        if (serviceRunning && !cameraReady) {
            startCameraAnalysis()
        }
    }
    private val firstFrameWatchdogRunnable = Runnable {
        if (serviceRunning && cameraReady && !hasReceivedFrame) {
            Log.e(TAG, "No frames received after camera bind. Rebinding camera.")
            cameraReady = false
            startCameraAnalysis()
        }
    }

    private lateinit var faceDetector: FaceDetector
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var windowManager: WindowManager

    private var cameraProvider: ProcessCameraProvider? = null
    private var overlayView: FrameLayout? = null
    private var timeoutMs: Long = DEFAULT_TIMEOUT_MS
    private var isOverlayShowing = false
    private var faceDetected = false
    private var closedFrameCount = 0
    private var isEyeClosed = false
    private var lastBlinkElapsedMs = 0L
    private var cameraReady = false
    private var serviceRunning = false
    private var isStopping = false
    private var hasReceivedFrame = false
    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        cameraExecutor = Executors.newSingleThreadExecutor()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.10f)
                .enableTracking()
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    Log.d(TAG, "STOP CLICKED")
                    stopSelfAndCleanup()
                    return START_NOT_STICKY
                }

                ACTION_START, null -> {
                    timeoutMs = intent?.getLongExtra(EXTRA_TIMEOUT_MS, loadSavedTimeoutMs()) ?: loadSavedTimeoutMs()
                    saveTimeoutMs(timeoutMs)
                    if (serviceRunning) {
                        updateRunningState(true)
                        return START_STICKY
                    }
                    isStopping = false
                    updateRunningState(true)
                    lastBlinkElapsedMs = SystemClock.elapsedRealtime()
                    startForegroundInternal()
                    startCameraAnalysis()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service start failed", e)
            stopSelfAndCleanup()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        hideOverlay()
        updateRunningState(false)
        mainHandler.removeCallbacks(cameraRetryRunnable)
        mainHandler.removeCallbacks(firstFrameWatchdogRunnable)
        stopCameraAnalysis()
        if (::faceDetector.isInitialized) {
            faceDetector.close()
        }
        cameraExecutor.shutdown()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun startForegroundInternal() {
        Log.d(TAG, "startForegroundInternal()")
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BlinkForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.service_running_title))
            .setContentText(getString(R.string.service_running_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "BlinkCue Service",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun startCameraAnalysis() {
        if (!serviceRunning || isStopping) {
            return
        }

        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        cameraReady = false
        hasReceivedFrame = false
        mainHandler.removeCallbacks(cameraRetryRunnable)
        mainHandler.removeCallbacks(firstFrameWatchdogRunnable)
        cameraProvider?.unbindAll()

        // Android 14/15 can still restrict background camera access even with a camera foreground
        // service. We start analysis only after the user explicitly starts the service from the app UI.
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                bindAnalysis(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider failed", e)
                scheduleCameraRetry()
                pauseDetection()
                hideOverlay()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindAnalysis(provider: ProcessCameraProvider) {
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { imageAnalysis ->
                imageAnalysis.setAnalyzer(cameraExecutor, BlinkAnalyzer())
            }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                analysis
            )
            cameraReady = true
            mainHandler.postDelayed(firstFrameWatchdogRunnable, FRAME_WATCHDOG_MS)
        } catch (e: Exception) {
            cameraReady = false
            Log.e(TAG, "bindAnalysis failed", e)
            scheduleCameraRetry()
            pauseDetection()
            hideOverlay()
        }
    }

    private fun stopCameraAnalysis() {
        cameraReady = false
        hasReceivedFrame = false
        mainHandler.removeCallbacks(cameraRetryRunnable)
        mainHandler.removeCallbacks(firstFrameWatchdogRunnable)
        cameraProvider?.unbindAll()
        pauseDetection()
    }

    private fun pauseDetection() {
        faceDetected = false
        closedFrameCount = 0
        isEyeClosed = false
    }

    private fun onBlinkDetected() {
        lastBlinkElapsedMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "BLINK DETECTED -> HIDE OVERLAY")
        Log.d(TAG, "lastBlinkTime updated: $lastBlinkElapsedMs")
        hideOverlay(immediate = true)
    }

    private fun showOverlay() {
        if (overlayView != null || isOverlayShowing) {
            return
        }

        mainHandler.post {
            if (overlayView != null || isOverlayShowing) {
                return@post
            }

            val view = createOverlayView()
            Log.d(TAG, "SHOW overlayView hash: ${view.hashCode()}")

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            try {
                overlayView = view
                windowManager.addView(view, params)
                isOverlayShowing = true
                view.requestFocus()
            } catch (e: Exception) {
                overlayView = null
                isOverlayShowing = false
                Log.e(TAG, "Overlay addView failed", e)
            }
        }
    }

    private fun hideOverlay(immediate: Boolean = false) {
        val removeBlock: () -> Unit = {
            if (overlayView != null && isOverlayShowing) {
                val view = overlayView!!
                Log.d(TAG, "HIDE overlayView hash: ${view.hashCode()}")
                try {
                    windowManager.removeViewImmediate(view)
                } catch (e: Exception) {
                    Log.e(TAG, "Overlay removeView failed", e)
                } finally {
                    view.visibility = View.GONE
                    overlayView = null
                    isOverlayShowing = false
                }
            } else {
                overlayView = null
                isOverlayShowing = false
            }
        }

        if (immediate && Looper.myLooper() == Looper.getMainLooper()) {
            removeBlock()
        } else {
            mainHandler.post(removeBlock)
        }
    }

    private fun stopSelfAndCleanup() {
        if (isStopping) {
            return
        }
        isStopping = true
        hideOverlay(immediate = true)
        stopCameraAnalysis()
        updateRunningState(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun saveTimeoutMs(value: Long) {
        prefs.edit().putLong(KEY_TIMEOUT_MS, value).apply()
    }

    private fun loadSavedTimeoutMs(): Long {
        return prefs.getLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
    }

    private fun updateRunningState(running: Boolean) {
        serviceRunning = running
        isRunningState = running
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED).apply {
                setPackage(packageName)
                putExtra(EXTRA_IS_RUNNING, running)
            }
        )
    }

    private fun scheduleCameraRetry() {
        if (!serviceRunning) {
            return
        }
        mainHandler.removeCallbacks(cameraRetryRunnable)
        mainHandler.postDelayed(cameraRetryRunnable, CAMERA_RETRY_MS)
    }

    private inner class BlinkAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            Log.d(TAG, "analyze() called")
            if (!cameraReady) {
                Log.d(TAG, "analyze() exiting because cameraReady=false")
                imageProxy.close()
                return
            }

            hasReceivedFrame = true
            mainHandler.removeCallbacks(firstFrameWatchdogRunnable)
            Log.d(TAG, "Frame received")
            val mediaImage = imageProxy.image
            if (mediaImage == null || !isAnalyzing.compareAndSet(false, true)) {
                Log.d(TAG, "Frame skipped: mediaImage=${mediaImage != null}, analyzerBusy=${isAnalyzing.get()}")
                imageProxy.close()
                return
            }

            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    handleFaces(faces)
                }
                .addOnFailureListener {
                Log.e(TAG, "Face detection failed")
                cameraReady = false
                scheduleCameraRetry()
                pauseDetection()
                hideOverlay()
                }
                .addOnCompleteListener {
                    isAnalyzing.set(false)
                    imageProxy.close()
                }
        }

        private fun handleFaces(faces: List<Face>) {
            val face = faces.firstOrNull()
            if (face == null) {
                Log.d(TAG, "No face detected")
                pauseDetection()
                hideOverlay()
                return
            }

            Log.d(TAG, "Face detected")
            val left = face.leftEyeOpenProbability
            val right = face.rightEyeOpenProbability
            if (left == null || right == null) {
                pauseDetection()
                hideOverlay()
                return
            }

            Log.d(TAG, "Eye open probability - left=$left, right=$right")

            faceDetected = true

            val bothEyesClosed = left < EYES_CLOSED_THRESHOLD && right < EYES_CLOSED_THRESHOLD
            if (bothEyesClosed) {
                closedFrameCount += 1
                Log.d(TAG, "Closed-eye frame count: $closedFrameCount")
                if (closedFrameCount >= BLINK_CONSECUTIVE_FRAMES) {
                    closedFrameCount = 0
                    onBlinkDetected()
                    return
                }
            } else {
                closedFrameCount = 0
                isEyeClosed = false
            }

            val elapsed = SystemClock.elapsedRealtime() - lastBlinkElapsedMs
            Log.d(TAG, "Elapsed since last blink: ${elapsed}ms / timeout=${timeoutMs}ms")
            if (faceDetected && elapsed >= timeoutMs) {
                Log.d(TAG, "TIMEOUT TRIGGERED")
                showOverlay()
            } else {
                hideOverlay()
            }
        }
    }

    private class OverlayDismissLayout(context: android.content.Context) : FrameLayout(context) {
        private var onBackPressed: (() -> Unit)? = null

        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        fun setOnBackPressedListener(listener: () -> Unit) {
            onBackPressed = listener
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onBackPressed?.invoke()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun createOverlayView(): FrameLayout {
        return OverlayDismissLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { hideOverlay() }
            setOnBackPressedListener { hideOverlay() }

            addView(
                TextView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                    text = context.getString(R.string.overlay_text)
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 28f
                }
            )
        }
    }

    companion object {
        private const val TAG = "BlinkCue"
        const val ACTION_START = "com.example.blinkcue.action.START"
        const val ACTION_STOP = "com.example.blinkcue.action.STOP"
        const val ACTION_STATE_CHANGED = "com.example.blinkcue.action.STATE_CHANGED"
        const val EXTRA_TIMEOUT_MS = "extra_timeout_ms"
        const val EXTRA_IS_RUNNING = "extra_is_running"
        @Volatile
        private var isRunningState = false

        private const val PREFS_NAME = "blinkcue_prefs"
        private const val KEY_TIMEOUT_MS = "timeout_ms"
        private const val NOTIFICATION_CHANNEL_ID = "blinkcue_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_TIMEOUT_MS = 4_000L
        private const val CAMERA_RETRY_MS = 2_000L
        private const val FRAME_WATCHDOG_MS = 3_000L
        private const val EYES_CLOSED_THRESHOLD = 0.3f
        private const val BLINK_CONSECUTIVE_FRAMES = 3

        fun isRunning(): Boolean = isRunningState
    }
}
