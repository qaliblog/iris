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
        
        /**
         * Check if overlay (pointer) is visible
         * Required for Android 15+ camera access in background
         */
        fun isOverlayVisible(): Boolean {
            return PointerOverlayService.getInstance()?.pointerView?.visibility == android.view.View.VISIBLE
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
    // Use ProcessLifecycleOwner as it's already available and works for foreground services
    private val serviceLifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get()
    
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
        
        // CRITICAL: Start foreground service FIRST, before any other initialization
        // This must happen in onCreate() to avoid permission errors
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        
        // Start foreground service immediately with notification
        try {
            val notification = createNotification()
            if (notification == null) {
                Log.e(TAG, "Notification is null - cannot start foreground service")
                LogcatManager.addLog("ERROR: Notification is null", "Service")
            } else {
                // Android 14+ (API 34+) requires service type in startForeground()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    try {
                        val method = Service::class.java.getMethod(
                            "startForeground",
                            Int::class.javaPrimitiveType,
                            Notification::class.java,
                            Int::class.javaPrimitiveType
                        )
                        method.invoke(
                            this,
                            NOTIFICATION_ID,
                            notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        )
                        Log.d(TAG, "Foreground service started with service type in onCreate() (Android ${Build.VERSION.SDK_INT})")
                        LogcatManager.addLog("Foreground service started successfully in onCreate()", "Service")
                    } catch (e: NoSuchMethodException) {
                        // Fallback to regular startForeground
                        startForeground(NOTIFICATION_ID, notification)
                        Log.d(TAG, "Using regular startForeground in onCreate()")
                        LogcatManager.addLog("Foreground service started (fallback) in onCreate()", "Service")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start foreground with service type in onCreate(): ${e.message}", e)
                        startForeground(NOTIFICATION_ID, notification)
                        LogcatManager.addLog("Foreground service started (fallback after error) in onCreate()", "Service")
                    }
                } else {
                    // Android 7-13: regular foreground service
                    startForeground(NOTIFICATION_ID, notification)
                    Log.d(TAG, "Foreground service started in onCreate() (Android ${Build.VERSION.SDK_INT})")
                    LogcatManager.addLog("Foreground service started successfully in onCreate()", "Service")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to start foreground service in onCreate(): ${e.message}", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            LogcatManager.addLog("CRITICAL: Failed to start foreground service in onCreate(): ${e.message}", "Service")
            // Try fallback notification
            try {
                val simpleNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("iris Background Service")
                    .setContentText("Eye tracking active")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                startForeground(NOTIFICATION_ID, simpleNotification)
                Log.w(TAG, "Foreground service started with fallback notification in onCreate()")
                LogcatManager.addLog("Foreground service started with fallback notification in onCreate()", "Service")
            } catch (e2: Exception) {
                Log.e(TAG, "All foreground start attempts failed in onCreate(): ${e2.message}", e2)
                LogcatManager.addLog("FATAL: Cannot start foreground service in onCreate()", "Service")
            }
        }
        
        // Get display metrics
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayMetrics = DisplayMetrics().apply {
            windowManager.defaultDisplay.getMetrics(this)
        }
        
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
        // CRITICAL: Must create notification BEFORE calling startForeground()
        try {
            val notification = createNotification()
            if (notification == null) {
                throw IllegalStateException("Notification is null - cannot start foreground service")
            }
            
            // Android 14+ (API 34+) requires service type in startForeground()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val method = Service::class.java.getMethod(
                        "startForeground",
                        Int::class.javaPrimitiveType,
                        Notification::class.java,
                        Int::class.javaPrimitiveType
                    )
                    method.invoke(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    )
                    Log.d(TAG, "Foreground service started with service type (Android ${Build.VERSION.SDK_INT})")
                    LogcatManager.addLog("Foreground service started successfully (Android ${Build.VERSION.SDK_INT})", "Service")
                } catch (e: NoSuchMethodException) {
                    // Fallback to regular startForeground for older Android 14 builds
                    startForeground(NOTIFICATION_ID, notification)
                    Log.d(TAG, "Using regular startForeground (service type method not available)")
                    LogcatManager.addLog("Foreground service started (fallback method)", "Service")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground with service type: ${e.message}", e)
                    // Try fallback
                    startForeground(NOTIFICATION_ID, notification)
                    LogcatManager.addLog("Foreground service started (fallback after error)", "Service")
                }
            } else {
                // Android 7-13: regular foreground service (service type in manifest is sufficient)
                startForeground(NOTIFICATION_ID, notification)
                Log.d(TAG, "Foreground service started (Android ${Build.VERSION.SDK_INT})")
                LogcatManager.addLog("Foreground service started successfully", "Service")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            LogcatManager.addLog("CRITICAL: Failed to start foreground service: ${e.message}", "Service")
            
            // Last resort: try simple startForeground without notification validation
            try {
                val simpleNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("iris Background Service")
                    .setContentText("Eye tracking active")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                startForeground(NOTIFICATION_ID, simpleNotification)
                Log.w(TAG, "Foreground service started with fallback notification")
                LogcatManager.addLog("Foreground service started with fallback notification", "Service")
            } catch (e2: Exception) {
                Log.e(TAG, "All foreground start attempts failed: ${e2.message}", e2)
                LogcatManager.addLog("FATAL: Cannot start foreground service - stopping", "Service")
                // Don't stop self - let it try to continue (may work on some devices)
                // stopSelf() would prevent any recovery
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
     * Handles Android 11+ and Android 15 camera restrictions properly
     */
    private fun bindCameraInService() {
        cameraProvider?.let { provider ->
            try {
                // Check Android version and restrictions
                val isForeground = isAppInForeground(this)
                val isAndroid11Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                val isAndroid15Plus = Build.VERSION.SDK_INT >= 35 // Android 15 (API 35)
                val isOverlayVisible = Companion.isOverlayVisible()
                
                // Android 15: Camera access in background requires overlay to be visible
                if (isAndroid15Plus && !isForeground && !isOverlayVisible) {
                    Log.d(TAG, "Android 15: Cannot bind camera - app in background and overlay not visible")
                    LogcatManager.addLog("Service: Android 15 restriction - overlay not visible", "Service")
                    scheduleRetryWhenForeground()
                    return
                }
                
                // Android 11-14: Camera access in background is restricted
                if (isAndroid11Plus && !isAndroid15Plus && !isForeground) {
                    Log.d(TAG, "Attempting camera bind in background (Android 11-14) - may be restricted")
                    LogcatManager.addLog("Service: Attempting camera bind in background (Android 11-14 restriction)", "Service")
                }
                
                // Ensure previous bindings are released so service can take over
                try {
                    provider.unbindAll()
                    // Small delay to ensure unbind completes (critical for proper handoff)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        tryBindCamera(provider)
                    }, 200) // Increased delay for more reliable handoff
                } catch (e: Exception) {
                    Log.w(TAG, "Error during unbindAll: ${e.message}")
                    // Try binding anyway after a short delay
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        tryBindCamera(provider)
                    }, 200)
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
     * Handles Android 11+ and Android 15 restrictions with retry logic
     */
    fun rebindCameraIfNeeded() {
        rebindRunnable?.let { cameraRebindHandler.removeCallbacks(it) }
        
        // Use a mutable variable that can be referenced from within the lambda
        var runnableRef: Runnable? = null
        runnableRef = Runnable {
            val isForeground = isAppInForeground(this@CameraForegroundService)
            val isAndroid15Plus = Build.VERSION.SDK_INT >= 35
            val isOverlayVisible = Companion.isOverlayVisible()
            
            // Check if we can bind camera
            val canBind = when {
                camera != null -> {
                    Log.d(TAG, "Camera already bound, skipping rebind")
                    false
                }
                cameraProvider == null -> {
                    Log.d(TAG, "Camera provider not ready, will retry")
                    LogcatManager.addLog("Service: Camera provider not ready, scheduling retry", "Service")
                    // Retry in 1 second
                    runnableRef?.let { cameraRebindHandler.postDelayed(it, 1000) }
                    false
                }
                imageAnalysis == null -> {
                    Log.d(TAG, "Image analyzer not ready, will retry")
                    LogcatManager.addLog("Service: Image analyzer not ready, scheduling retry", "Service")
                    runnableRef?.let { cameraRebindHandler.postDelayed(it, 1000) }
                    false
                }
                isAndroid15Plus && !isForeground && !isOverlayVisible -> {
                    Log.d(TAG, "Android 15: Cannot rebind - app in background and overlay not visible")
                    LogcatManager.addLog("Service: Android 15 restriction - scheduling retry when foreground", "Service")
                    scheduleRetryWhenForeground()
                    false
                }
                else -> true
            }
            
            if (canBind) {
                Log.d(TAG, "Rebinding camera in service after fragment release (foreground=$isForeground, overlay=$isOverlayVisible)")
                LogcatManager.addLog("Service: Rebinding camera for background processing", "Service")
                
                // Unbind all first to ensure clean state
                try {
                    cameraProvider?.unbindAll()
                } catch (e: Exception) {
                    Log.w(TAG, "Error during unbindAll in rebind: ${e.message}")
                }
                
                // Small delay to ensure fragment's unbind completes
                cameraRebindHandler.postDelayed({
                    bindCameraInService()
                }, 300) // Increased delay for more reliable handoff
            } else {
                val cameraStatus = camera != null
                val providerStatus = cameraProvider != null
                val analyzerStatus = imageAnalysis != null
                Log.d(TAG, "Cannot rebind camera now - camera: $cameraStatus, provider: $providerStatus, analyzer: $analyzerStatus, foreground: $isForeground, overlay: $isOverlayVisible")
            }
        }
        
        rebindRunnable = runnableRef
        runnableRef?.let { cameraRebindHandler.post(it) }
    }
    
    /**
     * Schedule camera rebind retry when app returns to foreground or overlay becomes visible
     * This handles Android 11+ and Android 15 restrictions gracefully
     */
    private fun scheduleRetryWhenForeground() {
        rebindRunnable?.let { cameraRebindHandler.removeCallbacks(it) }
        
        val retryRunnable = object : Runnable {
            override fun run() {
                if (camera == null && cameraProvider != null && imageAnalysis != null) {
                    val isForeground = isAppInForeground(this@CameraForegroundService)
                    val isAndroid15Plus = Build.VERSION.SDK_INT >= 35
                    val isOverlayVisible = Companion.isOverlayVisible()
                    
                    // Android 15: Need foreground OR overlay visible
                    // Android 11-14: Need foreground
                    val canBind = if (isAndroid15Plus) {
                        isForeground || isOverlayVisible
                    } else {
                        isForeground
                    }
                    
                    if (canBind) {
                        Log.d(TAG, "Conditions met for camera bind - retrying (foreground=$isForeground, overlay=$isOverlayVisible)")
                        LogcatManager.addLog("Service: Conditions met - retrying camera bind", "Service")
                        try {
                            cameraProvider?.unbindAll()
                        } catch (_: Exception) {}
                        bindCameraInService()
                    } else {
                        // Still restricted - check again in 2 seconds
                        cameraRebindHandler.postDelayed(this, 2000)
                    }
                } else {
                    // Camera already bound or components not ready - stop retrying
                    Log.d(TAG, "Stopping retry - camera: ${camera != null}, provider: ${cameraProvider != null}, analyzer: ${imageAnalysis != null}")
                }
            }
        }
        
        // Check every 2 seconds if conditions are met
        cameraRebindHandler.postDelayed(retryRunnable, 2000)
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
                    // Android 14+ requires service type to be specified in startForeground
                    try {
                        val method = Service::class.java.getMethod(
                            "startForeground",
                            Int::class.javaPrimitiveType,
                            Notification::class.java,
                            Int::class.javaPrimitiveType
                        )
                        method.invoke(
                            this,
                            NOTIFICATION_ID,
                            notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        )
                    } catch (e: NoSuchMethodException) {
                        // Fallback to regular startForeground
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } else {
                    // Android 7-13: regular foreground service
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
        
        return START_STICKY // Restart if killed - ensures service restarts automatically
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
                // Android 14+ requires service type to be specified in startForeground
                try {
                    val method = Service::class.java.getMethod(
                        "startForeground",
                        Int::class.javaPrimitiveType,
                        Notification::class.java,
                        Int::class.javaPrimitiveType
                    )
                    method.invoke(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    )
                } catch (e: NoSuchMethodException) {
                    // Fallback to regular startForeground
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                // Android 7-13: regular foreground service
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
        
        // Cancel any pending rebind operations
        rebindRunnable?.let { cameraRebindHandler.removeCallbacks(it) }
        rebindRunnable = null
        
        // Release camera
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding camera on destroy: ${e.message}")
        }
        camera = null
        imageAnalysis = null
        
        // Cleanup MediaPipe
        try {
            faceLandmarkerHelper?.clearFaceLandmarker()
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing FaceLandmarker: ${e.message}")
        }
        faceLandmarkerHelper = null
        
        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing wake lock: ${e.message}")
                }
            }
        }
        wakeLock = null
        
        // Shutdown executor
        try {
            backgroundExecutor.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down executor: ${e.message}")
        }
        
        LogcatManager.addLog("Background service stopped", "Service")
        
        super.onDestroy()
        instance = null
    }
}
