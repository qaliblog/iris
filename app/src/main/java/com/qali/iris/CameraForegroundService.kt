package com.qali.iris

import android.app.ActivityManager
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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
        
        /**
         * Check if the app is currently in the foreground
         * Required for Android 11+ camera access restrictions
         */
        fun isAppInForeground(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            
            val appProcesses = activityManager.runningAppProcesses ?: return false
            val packageName = context.packageName
            
            for (appProcess in appProcesses) {
                if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    appProcess.processName == packageName) {
                    return true
                }
            }
            return false
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
    
    // Custom lifecycle owner for service (required for camera binding in foreground service)
    private val serviceLifecycleOwner = object : LifecycleOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        override fun getLifecycle() = lifecycleRegistry
    }.apply {
        lifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED
    }
    
    // Retry handler for camera binding
    private val cameraRebindHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var rebindRunnable: Runnable? = null
    
    // Eye tracking components
    private var eyeTracker: EyeTracker? = null
    private var trackingCalculator: TrackingCalculator? = null
    private var eyeBlinkDetector: EyeBlinkDetector? = null
    private var settingsManager: SettingsManager? = null
    
    // Thread executor for background processing
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    
    // Display metrics
    private var displayMetrics: DisplayMetrics? = null
    private val mouseServiceReconnectListener: (MouseControlService) -> Unit = { service ->
        settingsManager?.let { service.setSettingsManager(it) }
        MouseControlService.getPendingCursorPosition()?.let { pointer ->
            PointerOverlayService.updatePointerPosition(pointer.x, pointer.y)
        }
    }
    
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
        eyeBlinkDetector = EyeBlinkDetector(
            initialBlinkThreshold = settingsManager!!.blinkThreshold,
            initialHalfBlinkAccelThreshold = settingsManager!!.halfBlinkAccelThreshold,
            initialClickDelayThreshold = settingsManager!!.clickDelayThreshold
        ).apply {
            onTap = { position ->
                MouseControlService.performClick()
                PointerOverlayService.indicateClick()
                LogcatManager.addLog(
                    "Service: Full blink detected at (${position.x.toInt()}, ${position.y.toInt()})",
                    "Service"
                )
            }
            onDragStart = {
                MouseControlService.startDrag()
                PointerOverlayService.indicateDragStart()
                LogcatManager.addLog("Service: Half-blink → drag start", "Service")
            }
            onDragEnd = {
                MouseControlService.endDrag()
                PointerOverlayService.indicateDragEnd()
                LogcatManager.addLog("Service: Half-blink → drag end", "Service")
            }
        }
        
        // Set SettingsManager in MouseControlService for cursor update configuration
        MouseControlService.registerOnServiceConnected(mouseServiceReconnectListener)
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
        
        // Start as foreground service with proper service type for Android 10+
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires service type to be specified in startForeground
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ supports foreground service types
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                // Android 7-9: regular foreground service
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground service started with notification (Android ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            LogcatManager.addLog("Failed to start foreground service: ${e.message}", "Service")
            // Don't stop self - try to continue without foreground (may work on older Android)
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback foreground start also failed: ${e2.message}", e2)
                stopSelf()
            }
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
                                LogcatManager.addLog("Service: Processing camera frame - MediaPipe active | Camera bound: ${camera != null}", "Service")
                                Log.d(TAG, "Processing camera frame in background service - Camera: ${camera != null}")
                            }
                            
                            // Update settings dynamically
                            eyeTracker?.setUseOneEye(settingsManager?.useOneEyeDetection ?: false)
                            eyeBlinkDetector?.setBlinkThreshold(settingsManager?.blinkThreshold ?: 0.3f)
                            eyeBlinkDetector?.setHalfBlinkAccelThreshold(settingsManager?.halfBlinkAccelThreshold ?: 0.15f)
                            eyeBlinkDetector?.setClickDelayThreshold(settingsManager?.clickDelayThreshold ?: 200L)
                            
                            // Process frame - this will trigger onResults callback which updates cursor
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
                
                // Bind camera in service for background processing
                // When fragment is active, it will bind Preview + ImageAnalysis
                // When fragment is paused/closed, service continues with ImageAnalysis only
                // This ensures pointer updates continue even when app is in background
                // Try to bind immediately, but it might fail if fragment has it (that's OK)
                bindCameraInService()
                
                // Also schedule a delayed rebind attempt in case fragment has the camera
                // This ensures we get the camera when fragment releases it
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (camera == null && cameraProvider != null && imageAnalysis != null) {
                        Log.d(TAG, "Attempting delayed camera rebind in service")
                        bindCameraInService()
                    }
                }, 1000)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera: ${e.message}", e)
                LogcatManager.addLog("Failed to initialize camera: ${e.message}", "Service")
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    /**
     * Bind camera in service for background processing
     * This ensures camera processing continues even when fragment is paused/closed
     * Handles Android 11+ camera restrictions properly
     */
    private fun bindCameraInService() {
        cameraProvider?.let { provider ->
            try {
                // Check Android 11+ camera restrictions
                val isForeground = isAppInForeground(this)
                val isAndroid11Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                
                if (isAndroid11Plus && !isForeground) {
                    // Android 11+: Camera access in background is restricted
                    // We can still try, but it may fail - that's expected behavior
                    Log.d(TAG, "Attempting camera bind in background (Android 11+) - may be restricted")
                    LogcatManager.addLog("Service: Attempting camera bind in background (Android 11+ restriction)", "Service")
                }
                
                // Ensure previous bindings are released so service can take over
                try {
                    provider.unbindAll()
                    // Small delay to ensure unbind completes
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        tryBindCamera(provider)
                    }, 100)
                } catch (e: Exception) {
                    Log.w(TAG, "Error during unbindAll: ${e.message}")
                    // Try binding anyway
                    tryBindCamera(provider)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in bindCameraInService: ${e.message}", e)
                LogcatManager.addLog("Service: Camera binding error: ${e.message}", "Service")
            }
        } ?: run {
            LogcatManager.addLog("Service: Camera provider not ready yet, will bind later", "Service")
        }
    }
    
    /**
     * Attempt to bind camera with proper lifecycle owner
     * Uses service lifecycle owner for foreground service binding
     */
    private fun tryBindCamera(provider: ProcessCameraProvider) {
        try {
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            
            // Use service lifecycle owner instead of ProcessLifecycleOwner
            // This is more reliable for foreground services
            camera = provider.bindToLifecycle(
                serviceLifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
            
            if (camera != null) {
                LogcatManager.addLog("Service: Camera bound successfully - Camera instance: ${camera != null}", "Service")
                Log.d(TAG, "Camera bound successfully in service - Camera: ${camera != null}")
            } else {
                Log.w(TAG, "Warning: Camera binding returned null")
                LogcatManager.addLog("Service: Warning - Camera binding returned null", "Service")
                
                // Try with ProcessLifecycleOwner as fallback
                try {
                    camera = provider.bindToLifecycle(
                        ProcessLifecycleOwner.get(),
                        cameraSelector,
                        imageAnalysis
                    )
                    if (camera != null) {
                        Log.d(TAG, "Camera bound successfully using ProcessLifecycleOwner fallback")
                        LogcatManager.addLog("Service: Camera bound using ProcessLifecycleOwner fallback", "Service")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "ProcessLifecycleOwner fallback also failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // Camera might be bound by fragment or restricted on Android 11+
            val isAndroid11Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val isForeground = isAppInForeground(this)
            
            if (isAndroid11Plus && !isForeground) {
                Log.d(TAG, "Camera binding failed - Android 11+ background restriction (expected): ${e.message}")
                LogcatManager.addLog("Service: Camera binding failed - Android 11+ background restriction", "Service")
            } else {
                Log.d(TAG, "Camera binding conflict or error: ${e.message}")
                LogcatManager.addLog("Service: Camera binding deferred: ${e.message}", "Service")
            }
        }
    }
    
    /**
     * Rebind camera in service - called when fragment releases camera
     */
    fun rebindCameraIfNeeded() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (camera == null && cameraProvider != null && imageAnalysis != null) {
                Log.d(TAG, "Rebinding camera in service after fragment release")
                LogcatManager.addLog("Service: Rebinding camera for background processing", "Service")
                try {
                    cameraProvider?.unbindAll()
                } catch (_: Exception) {}
                bindCameraInService()
            } else {
                Log.d(TAG, "Cannot rebind camera - camera: ${camera != null}, provider: ${cameraProvider != null}, analyzer: ${imageAnalysis != null}")
                LogcatManager.addLog("Service: Cannot rebind camera (camera=${camera != null}, provider=${cameraProvider != null})", "Service")
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle wake lock toggle
        if (intent?.action == ACTION_TOGGLE_WAKELOCK) {
            toggleWakeLock()
        }
        
        // Ensure we're still in foreground with proper service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ requires service type
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ supports service types
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground in onStartCommand: ${e.message}", e)
                // Try fallback
                try {
                    startForeground(NOTIFICATION_ID, createNotification())
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback foreground start also failed: ${e2.message}", e2)
                }
            }
        }
        
        // Try to rebind camera if needed (may have been released)
        if (camera == null && cameraProvider != null && imageAnalysis != null) {
            rebindCameraIfNeeded()
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
            
            // Calculate adjusted position first (needed for click position)
            val (adjustedX, adjustedY) = trackingCalculator?.calculateAdjustedPosition(trackingResult)
                ?: Pair(trackingResult.screenX, trackingResult.screenY)
            
            // Update blink detector thresholds
            eyeBlinkDetector?.setBlinkThreshold(settingsManager?.blinkThreshold ?: 0.3f)
            eyeBlinkDetector?.setHalfBlinkAccelThreshold(settingsManager?.halfBlinkAccelThreshold ?: 0.15f)
            eyeBlinkDetector?.setClickDelayThreshold(settingsManager?.clickDelayThreshold ?: 200L)
            
            // Detect blink using eyelid landmarks (preferred) or fallback to eye area
            if (trackingResult.leftEyelidLandmarks != null || trackingResult.rightEyelidLandmarks != null) {
                val combinedEyelid = if (settingsManager?.useOneEyeDetection == true) {
                    trackingResult.rightEyelidLandmarks ?: trackingResult.leftEyelidLandmarks
                } else {
                    when {
                        trackingResult.leftEyelidLandmarks != null && trackingResult.rightEyelidLandmarks != null -> {
                            EyeTracker.EyelidLandmarks(
                                upperLidY = (trackingResult.leftEyelidLandmarks!!.upperLidY + trackingResult.rightEyelidLandmarks!!.upperLidY) / 2f,
                                lowerLidY = (trackingResult.leftEyelidLandmarks!!.lowerLidY + trackingResult.rightEyelidLandmarks!!.lowerLidY) / 2f
                            )
                        }
                        trackingResult.leftEyelidLandmarks != null -> trackingResult.leftEyelidLandmarks
                        trackingResult.rightEyelidLandmarks != null -> trackingResult.rightEyelidLandmarks
                        else -> null
                    }
                }

                combinedEyelid?.let {
                    eyeBlinkDetector?.processEyelidLandmarks(
                        upperLidY = it.upperLidY,
                        lowerLidY = it.lowerLidY,
                        clickPosition = android.graphics.PointF(adjustedX, adjustedY)
                    )
                }
            } else {
                eyeBlinkDetector?.processEyeArea(trackingResult.eyeArea)
            }
            
            // Service always updates pointer in background (regardless of cursor movement flag)
            // The flag is only for fragment UI when settings are open
            // In background, we always want the pointer to update
            PointerOverlayService.updatePointerPosition(adjustedX, adjustedY)
            MouseControlService.moveCursor(adjustedX, adjustedY)
            
            // Log periodically to confirm cursor updates (every 3 seconds)
            val now = System.currentTimeMillis()
            if (now % 3000 < 100) {
                LogcatManager.addLog("Service: Cursor updated to (${adjustedX.toInt()}, ${adjustedY.toInt()}) | Camera: ${camera != null}", "Service")
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
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification: ${e.message}", e)
            // Try fallback
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback notification update also failed: ${e2.message}", e2)
            }
        }
    }
    
    override fun onDestroy() {
        MouseControlService.unregisterOnServiceConnected(mouseServiceReconnectListener)
        
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
        
        super.onDestroy()
        instance = null
    }
}
