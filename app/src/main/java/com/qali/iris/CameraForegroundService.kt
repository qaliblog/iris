package com.qali.iris

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.qali.iris.fragment.CameraFragment
import java.util.concurrent.Executors

/**
 * Comprehensive foreground service that handles all camera operations,
 * eye tracking, and pointer updates in the background
 */
class CameraForegroundService : Service(), FaceLandmarkerHelper.LandmarkerListener {
    
    companion object {
        private const val TAG = "CameraForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "camera_foreground_channel"
        const val ACTION_TOGGLE_WAKELOCK = "com.qali.iris.TOGGLE_WAKELOCK"
        private var instance: CameraForegroundService? = null
        
        fun getInstance(): CameraForegroundService? = instance
        
        fun start(context: Context) {
            val intent = Intent(context, CameraForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, CameraForegroundService::class.java)
            context.stopService(intent)
        }
        
        fun getWakeLockState(): Boolean {
            return instance?.isWakeLockEnabled ?: false
        }
        
        fun toggleWakeLock() {
            instance?.toggleWakeLock()
        }
    }
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null
    var isWakeLockEnabled = true
        private set
    
    // Camera and processing components
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var faceLandmarkerHelper: FaceLandmarkerHelper? = null
    
    // Eye tracking components
    private var eyeTracker: EyeTracker? = null
    private var trackingCalculator: TrackingCalculator? = null
    private var eyeBlinkDetector: EyeBlinkDetector? = null
    private var settingsManager: SettingsManager? = null
    
    // Thread executor for background processing
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    
    // Display metrics
    private var displayMetrics: DisplayMetrics? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Get display metrics
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayMetrics = DisplayMetrics().apply {
            windowManager.defaultDisplay.getMetrics(this)
        }
        
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        
        // Acquire wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "iris::CameraForegroundWakeLock"
        ).apply {
            setReferenceCounted(false)
            try {
                acquire()
                isWakeLockEnabled = true
                Log.d(TAG, "Wake lock acquired successfully")
                LogcatManager.addLog("Wake lock acquired - Background service starting", "Service")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire wake lock: ${e.message}", e)
                isWakeLockEnabled = false
            }
        }
        
        // Initialize components
        settingsManager = SettingsManager(this)
        eyeTracker = EyeTracker(displayMetrics!!, settingsManager!!.useOneEyeDetection)
        trackingCalculator = TrackingCalculator(settingsManager!!, displayMetrics!!)
        eyeBlinkDetector = EyeBlinkDetector(settingsManager!!.blinkThreshold)
        
        // Set SettingsManager in MouseControlService for cursor update configuration
        MouseControlService.getInstance()?.setSettingsManager(settingsManager!!)
        
        // Start pointer overlay service
        try {
            val pointerIntent = Intent(this, PointerOverlayService::class.java)
            startService(pointerIntent)
            LogcatManager.addLog("Pointer overlay service started from background service", "Service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pointer service: ${e.message}", e)
        }
        
        // Initialize FaceLandmarkerHelper
        backgroundExecutor.execute {
            try {
                faceLandmarkerHelper = FaceLandmarkerHelper(
                    context = this@CameraForegroundService,
                    runningMode = RunningMode.LIVE_STREAM,
                    minFaceDetectionConfidence = FaceLandmarkerHelper.DEFAULT_FACE_DETECTION_CONFIDENCE,
                    minFaceTrackingConfidence = FaceLandmarkerHelper.DEFAULT_FACE_TRACKING_CONFIDENCE,
                    minFacePresenceConfidence = FaceLandmarkerHelper.DEFAULT_FACE_PRESENCE_CONFIDENCE,
                    maxNumFaces = FaceLandmarkerHelper.DEFAULT_NUM_FACES,
                    currentDelegate = FaceLandmarkerHelper.DELEGATE_GPU,
                    faceLandmarkerHelperListener = this@CameraForegroundService
                )
                LogcatManager.addLog("FaceLandmarkerHelper initialized in service", "Service")
                
                // Initialize camera after MediaPipe is ready
                initializeCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize FaceLandmarkerHelper: ${e.message}", e)
                LogcatManager.addLog("Failed to initialize MediaPipe: ${e.message}", "Service")
            }
        }
        
        // Start as foreground service
        try {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "Foreground service started with notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            stopSelf()
        }
    }
    
    private fun initializeCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // Build image analysis use case
                // Must use RGBA_8888 format to match MediaPipe FaceLandmarkerHelper requirements
                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                
                // Set analyzer
                imageAnalysis?.setAnalyzer(
                    backgroundExecutor,
                    ImageAnalysis.Analyzer { imageProxy: ImageProxy ->
                        try {
                            // Log periodically to confirm frames are being processed
                            val now = System.currentTimeMillis()
                            if (now % 5000 < 100) { // Log every 5 seconds
                                LogcatManager.addLog("Service: Processing camera frame - MediaPipe active", "Service")
                                Log.d(TAG, "Processing camera frame in background service")
                            }
                            
                            // Update settings dynamically
                            eyeTracker?.setUseOneEye(settingsManager?.useOneEyeDetection ?: false)
                            eyeBlinkDetector?.setBlinkThreshold(settingsManager?.blinkThreshold ?: 0.3f)
                            
                            // Process frame
                            faceLandmarkerHelper?.detectLiveStream(imageProxy, isFrontCamera = true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing frame: ${e.message}", e)
                            LogcatManager.addLog("Service: Frame processing error: ${e.message}", "Service")
                            try {
                                imageProxy.close()
                            } catch (closeEx: Exception) {
                                // Ignore close errors
                            }
                        }
                    }
                )
                
                // DON'T bind camera in service - let the fragment handle it when visible
                // The fragment will bind Preview + ImageAnalysis when active
                // Service only needs MediaPipe initialized, fragment handles camera binding
                // This avoids conflicts and ensures preview is shown
                LogcatManager.addLog("Service: MediaPipe initialized, fragment will handle camera binding", "Service")
                Log.d(TAG, "Service initialized MediaPipe, fragment will bind camera")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera: ${e.message}", e)
                LogcatManager.addLog("Failed to initialize camera: ${e.message}", "Service")
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle wake lock toggle
        if (intent?.action == ACTION_TOGGLE_WAKELOCK) {
            toggleWakeLock()
        }
        
        // Ensure we're still in foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground in onStartCommand: ${e.message}", e)
            }
        }
        
        // Renew wake lock
        if (isWakeLockEnabled) {
            wakeLock?.let {
                if (!it.isHeld) {
                    try {
                        it.acquire()
                        Log.d(TAG, "Wake lock renewed in onStartCommand")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to renew wake lock: ${e.message}", e)
                    }
                }
            }
        }
        
        return START_STICKY // Restart if killed
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    // FaceLandmarkerHelper.LandmarkerListener implementation
    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        try {
            val faceLandmarksList = resultBundle.result.faceLandmarks()
            if (faceLandmarksList.isEmpty()) {
                // No face detected - hide pointer
                PointerOverlayService.getInstance()?.hidePointer()
                return
            }
            
            val landmarks = faceLandmarksList.firstOrNull()
            if (landmarks == null) {
                PointerOverlayService.getInstance()?.hidePointer()
                return
            }
            
            // Update eye tracker settings
            eyeTracker?.setUseOneEye(settingsManager?.useOneEyeDetection ?: false)
            
            // Track eyes
            val trackingResult = eyeTracker?.trackEyes(landmarks) ?: return
            
            // Update blink detector threshold
            eyeBlinkDetector?.setBlinkThreshold(settingsManager?.blinkThreshold ?: 0.3f)
            
            // Detect blink for click
            val blinkDetected = eyeBlinkDetector?.processEyeArea(trackingResult.eyeArea) ?: false
            if (blinkDetected) {
                // Trigger click
                MouseControlService.performClick()
                PointerOverlayService.indicateClick()
                LogcatManager.addLog("Blink click detected", "Service")
            }
            
            // Calculate adjusted position
            val (adjustedX, adjustedY) = trackingCalculator?.calculateAdjustedPosition(trackingResult)
                ?: Pair(trackingResult.screenX, trackingResult.screenY)
            
            // Update pointer and mouse cursor (only if cursor movement is enabled)
            if (CameraFragment.isCursorMovementEnabled()) {
                PointerOverlayService.updatePointerPosition(adjustedX, adjustedY)
                MouseControlService.moveCursor(adjustedX, adjustedY)
                
                // Log periodically to confirm cursor updates (every 3 seconds)
                val now = System.currentTimeMillis()
                if (now % 3000 < 100) {
                    LogcatManager.addLog("Service: Cursor updated to (${adjustedX.toInt()}, ${adjustedY.toInt()})", "Service")
                }
            } else {
                PointerOverlayService.getInstance()?.hidePointer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing results: ${e.message}", e)
        }
    }
    
    override fun onError(error: String, errorCode: Int) {
        Log.e(TAG, "FaceLandmarkerHelper error: $error (code: $errorCode)")
        LogcatManager.addLog("MediaPipe error: $error", "Service")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "iPoint Background Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Keeps eye tracking active in background"
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val toggleIntent = Intent(this, CameraForegroundService::class.java).apply {
            action = ACTION_TOGGLE_WAKELOCK
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val wakeLockStatus = if (isWakeLockEnabled) "ON" else "OFF"
        val toggleText = if (isWakeLockEnabled) "Turn OFF" else "Turn ON"
        val statusIcon = if (isWakeLockEnabled) {
            android.R.drawable.ic_lock_lock
        } else {
            android.R.drawable.ic_lock_power_off
        }
        
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("?? iris - Wake Lock: $wakeLockStatus")
            .setContentText(if (isWakeLockEnabled) "Wake lock ON ? Camera active ? MediaPipe running" else "Wake lock OFF ? Camera may pause")
            .setSmallIcon(android.R.drawable.ic_lock_power_off)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setAutoCancel(false)
        
        val action = NotificationCompat.Action(
            statusIcon,
            toggleText,
            togglePendingIntent
        )
        notificationBuilder.addAction(action)
        
        return notificationBuilder
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(if (isWakeLockEnabled) {
                    "Wake lock is ACTIVE to keep the camera running.\nMediaPipe landmark detection is active.\nCamera is running for continuous cursor control.\n\nTap \"$toggleText\" button above to disable."
                } else {
                    "Wake lock is DISABLED.\nCamera may pause when device sleeps.\n\nTap \"$toggleText\" button above to enable."
                }))
            .build()
    }
    
    private fun toggleWakeLock() {
        isWakeLockEnabled = !isWakeLockEnabled
        
        if (isWakeLockEnabled) {
            wakeLock?.let {
                if (!it.isHeld) {
                    try {
                        it.acquire()
                        Log.d(TAG, "Wake lock toggled ON")
                        LogcatManager.addLog("Wake lock enabled - MediaPipe will continue processing", "Service")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to acquire wake lock: ${e.message}", e)
                        isWakeLockEnabled = false
                    }
                }
            } ?: run {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "iris::CameraForegroundWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    try {
                        acquire()
                        Log.d(TAG, "Wake lock created and acquired")
                        LogcatManager.addLog("Wake lock enabled - MediaPipe will continue processing", "Service")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to acquire wake lock: ${e.message}", e)
                        isWakeLockEnabled = false
                    }
                }
            }
        } else {
            wakeLock?.let {
                if (it.isHeld) {
                    try {
                        it.release()
                        Log.d(TAG, "Wake lock toggled OFF")
                        LogcatManager.addLog("Wake lock disabled - MediaPipe may pause when device sleeps", "Service")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to release wake lock: ${e.message}", e)
                    }
                }
            }
        }
        
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification: ${e.message}", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        
        // Release camera
        cameraProvider?.unbindAll()
        camera = null
        imageAnalysis = null
        
        // Cleanup MediaPipe
        faceLandmarkerHelper?.clearFaceLandmarker()
        faceLandmarkerHelper = null
        
        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
        
        // Shutdown executor
        backgroundExecutor.shutdown()
        
        LogcatManager.addLog("Background service stopped", "Service")
    }
}
