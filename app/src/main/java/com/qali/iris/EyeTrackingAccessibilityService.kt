package com.qali.iris

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.Executors

/**
 * AccessibilityService for true background eye-tracking on Android 15+
 * 
 * This service bypasses Android 15 restrictions by:
 * - Running CameraX + MediaPipe directly in AccessibilityService
 * - Using AccessibilityService exemption to access camera in background
 * - Allowing overlay updates when AccessibilityService is enabled
 * - No foreground service required - uses AccessibilityService privileges
 * 
 * Works on Android 7+ (API 24) to Android 15 (API 35)
 */
class EyeTrackingAccessibilityService : AccessibilityService(), FaceLandmarkerHelper.LandmarkerListener {
    
    companion object {
        private const val TAG = "EyeTrackingAccessibilityService"
        private var instance: EyeTrackingAccessibilityService? = null
        
        /**
         * Get the singleton instance of the service
         * Returns null if service is not running
         */
        fun getInstance(): EyeTrackingAccessibilityService? = instance
        
        /**
         * Check if the service is enabled and running
         */
        fun isEnabled(): Boolean {
            return instance != null && instance?.isServiceEnabled == true
        }
        
        /**
         * Get wake lock state
         */
        fun getWakeLockState(): Boolean {
            return instance?.isWakeLockEnabled ?: false
        }
        
        /**
         * Toggle wake lock
         */
        fun toggleWakeLock() {
            instance?.toggleWakeLock()
        }
        
        /**
         * Update wake lock based on screen off tracking setting
         */
        fun updateWakeLockFromSettings() {
            instance?.updateWakeLockFromSettings()
        }
        
        /**
         * Check if the app is currently in the foreground
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
         */
        fun isOverlayVisible(): Boolean {
            return PointerOverlayService.getInstance()?.pointerView?.visibility == android.view.View.VISIBLE
        }
    }
    
    private var isServiceEnabled = false
    
    // Camera and processing components
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var faceLandmarkerHelper: FaceLandmarkerHelper? = null
    
    // Custom lifecycle owner for service (required for camera binding in service)
    private val serviceLifecycleOwner = ProcessLifecycleOwner.get()
    
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
    
    // Wake lock to keep processing active
    private var wakeLock: PowerManager.WakeLock? = null
    private var isWakeLockEnabled = true
    
    // Mouse service reconnect listener
    private val mouseServiceReconnectListener: (MouseControlService) -> Unit = { service ->
        settingsManager?.let { service.setSettingsManager(it) }
        MouseControlService.getPendingCursorPosition()?.let { pointer ->
            PointerOverlayService.updatePointerPosition(pointer.x, pointer.y)
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceEnabled = true
        
        Log.d(TAG, "EyeTrackingAccessibilityService connected - Initializing camera and MediaPipe")
        LogcatManager.addLog("Accessibility service connected - Background tracking enabled", "Service")
        
        // Get display metrics
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayMetrics = DisplayMetrics().apply {
            windowManager.defaultDisplay.getMetrics(this)
        }
        
        // Initialize components
        settingsManager = SettingsManager(this)
        
        // Acquire PARTIAL_WAKE_LOCK to keep CPU awake when screen is off (if enabled)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "iris::EyeTrackingWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
        
        // Acquire wake lock if screen off tracking is enabled
        if (settingsManager!!.screenOffTracking) {
            try {
                wakeLock?.acquire()
                isWakeLockEnabled = true
                Log.d(TAG, "PARTIAL_WAKE_LOCK acquired - CPU will stay awake when screen is off")
                LogcatManager.addLog("Wake lock acquired - CPU will stay awake when screen is off", "Service")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire wake lock: ${e.message}", e)
                isWakeLockEnabled = false
            }
        } else {
            isWakeLockEnabled = false
            Log.d(TAG, "Screen off tracking disabled - wake lock not acquired")
            LogcatManager.addLog("Screen off tracking disabled - wake lock not acquired", "Service")
        }
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
            val pointerIntent = android.content.Intent(this, PointerOverlayService::class.java)
            startService(pointerIntent)
            LogcatManager.addLog("Pointer overlay service started from accessibility service", "Service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pointer service: ${e.message}", e)
        }
        
        // Initialize FaceLandmarkerHelper
        backgroundExecutor.execute {
            try {
                faceLandmarkerHelper = FaceLandmarkerHelper(
                    context = this@EyeTrackingAccessibilityService,
                    runningMode = RunningMode.LIVE_STREAM,
                    minFaceDetectionConfidence = FaceLandmarkerHelper.DEFAULT_FACE_DETECTION_CONFIDENCE,
                    minFaceTrackingConfidence = FaceLandmarkerHelper.DEFAULT_FACE_TRACKING_CONFIDENCE,
                    minFacePresenceConfidence = FaceLandmarkerHelper.DEFAULT_FACE_PRESENCE_CONFIDENCE,
                    maxNumFaces = FaceLandmarkerHelper.DEFAULT_NUM_FACES,
                    currentDelegate = FaceLandmarkerHelper.DELEGATE_GPU,
                    faceLandmarkerHelperListener = this@EyeTrackingAccessibilityService
                )
                LogcatManager.addLog("FaceLandmarkerHelper initialized in accessibility service", "Service")
                
                // Initialize camera after MediaPipe is ready
                initializeCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize FaceLandmarkerHelper: ${e.message}", e)
                LogcatManager.addLog("Failed to initialize MediaPipe: ${e.message}", "Service")
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
                            // Log every frame received
                            Log.d(TAG, "FRAME RECEIVED")
                            LogcatManager.addLog("Service: FRAME RECEIVED - Processing camera frame", "Service")
                            
                            // Update settings dynamically
                            eyeTracker?.setUseOneEye(settingsManager?.useOneEyeDetection ?: false)
                            eyeBlinkDetector?.setBlinkThreshold(settingsManager?.blinkThreshold ?: 0.3f)
                            eyeBlinkDetector?.setHalfBlinkAccelThreshold(settingsManager?.halfBlinkAccelThreshold ?: 0.15f)
                            eyeBlinkDetector?.setClickDelayThreshold(settingsManager?.clickDelayThreshold ?: 200L)
                            
                            // FORCE processing even if preview is hidden - this is critical for background tracking
                            // Run detectLiveStream() on a separate background thread to avoid blocking
                            val imageProxyRef = imageProxy
                            Thread {
                                try {
                                    Log.d(TAG, "FRAME RECEIVED: Starting face detection on background thread")
                                    faceLandmarkerHelper?.detectLiveStream(imageProxyRef, isFrontCamera = true)
                                    Log.d(TAG, "FRAME RECEIVED: Frame sent to MediaPipe for face detection")
                                } catch (e: Exception) {
                                    Log.e(TAG, "FRAME RECEIVED: Error in detectLiveStream: ${e.message}", e)
                                    try {
                                        imageProxyRef.close()
                                    } catch (closeEx: Exception) {
                                        // Ignore close errors
                                    }
                                }
                            }.start()
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
                // AccessibilityService exemption allows camera access in background
                bindCameraInService()
                
                // Also schedule a delayed rebind attempt in case fragment has the camera
                // This ensures we get the camera when fragment releases it
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (camera == null && cameraProvider != null && imageAnalysis != null) {
                        Log.d(TAG, "Attempting delayed camera rebind in accessibility service")
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
     * AccessibilityService exemption allows camera access in background on Android 15+
     */
    private fun bindCameraInService() {
        cameraProvider?.let { provider ->
            try {
                // Check Android version and restrictions
                val isForeground = isAppInForeground(this)
                val isAndroid11Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                val isAndroid15Plus = Build.VERSION.SDK_INT >= 35 // Android 15 (API 35)
                val isOverlayVisible = Companion.isOverlayVisible()
                
                // Android 15: Camera access in background requires overlay to be visible OR AccessibilityService
                // Since we're in AccessibilityService, we have exemption
                if (isAndroid15Plus && !isForeground && !isOverlayVisible) {
                    Log.d(TAG, "Android 15: AccessibilityService exemption allows camera access")
                    LogcatManager.addLog("Service: Android 15 - Using AccessibilityService exemption", "Service")
                }
                
                // Android 11-14: Camera access in background is restricted, but AccessibilityService may help
                if (isAndroid11Plus && !isAndroid15Plus && !isForeground) {
                    Log.d(TAG, "Attempting camera bind in background (Android 11-14) - AccessibilityService may help")
                    LogcatManager.addLog("Service: Attempting camera bind in background (Android 11-14)", "Service")
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
     * Uses service lifecycle owner for service binding
     */
    private fun tryBindCamera(provider: ProcessCameraProvider) {
        try {
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            
            // Use service lifecycle owner for service binding
            camera = provider.bindToLifecycle(
                serviceLifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
            
            if (camera != null) {
                LogcatManager.addLog("Service: Camera bound successfully in accessibility service - Camera instance: ${camera != null}", "Service")
                Log.d(TAG, "Camera bound successfully in accessibility service - Camera: ${camera != null}")
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
            // Camera might be bound by fragment or restricted
            val isAndroid11Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val isForeground = isAppInForeground(this)
            
            if (isAndroid11Plus && !isForeground) {
                Log.d(TAG, "Camera binding failed - Android 11+ background restriction (may still work with AccessibilityService): ${e.message}")
                LogcatManager.addLog("Service: Camera binding deferred: ${e.message}", "Service")
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
        rebindRunnable?.let { cameraRebindHandler.removeCallbacks(it) }
        
        // Use a mutable variable that can be referenced from within the lambda
        var runnableRef: Runnable? = null
        runnableRef = Runnable {
            val isForeground = isAppInForeground(this@EyeTrackingAccessibilityService)
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
                else -> true // AccessibilityService exemption allows binding
            }
            
            if (canBind) {
                Log.d(TAG, "Rebinding camera in accessibility service (foreground=$isForeground, overlay=$isOverlayVisible)")
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
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle accessibility events
        // This service is only used to bypass Android 15 restrictions
    }
    
    override fun onInterrupt() {
        // Service was interrupted - log but don't stop camera processing
        // User may have temporarily disabled accessibility
        Log.w(TAG, "Accessibility service interrupted")
        LogcatManager.addLog("Accessibility service interrupted", "Service")
    }
    
    // FaceLandmarkerHelper.LandmarkerListener implementation
    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        try {
            Log.d(TAG, "onResults() called - processing face detection results")
            val faceLandmarksList = resultBundle.result.faceLandmarks()
            Log.d(TAG, "onResults: faceLandmarksList size = ${faceLandmarksList.size}")
            
            if (faceLandmarksList.isEmpty()) {
                // No face detected - hide pointer
                Log.d(TAG, "NO FACE")
                LogcatManager.addLog("Service: NO FACE - hiding pointer", "Service")
                PointerOverlayService.getInstance()?.hidePointer()
                return
            }
            
            Log.d(TAG, "FACE DETECTED!")
            LogcatManager.addLog("Service: FACE DETECTED! Processing landmarks...", "Service")
            val landmarks = faceLandmarksList.firstOrNull()
            if (landmarks == null) {
                Log.w(TAG, "NO FACE - Landmarks list not empty but firstOrNull returned null")
                LogcatManager.addLog("Service: NO FACE - Landmarks list not empty but firstOrNull returned null", "Service")
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
            
            // Service always updates pointer in background (AccessibilityService exemption allows this)
            Log.d(TAG, "onResults: Updating pointer position to ($adjustedX, $adjustedY)")
            PointerOverlayService.updatePointerPosition(adjustedX, adjustedY)
            MouseControlService.moveCursor(adjustedX, adjustedY)
            
            // Log every update to track cursor movement
            Log.d(TAG, "onResults: Cursor updated to (${adjustedX.toInt()}, ${adjustedY.toInt()}) | Camera: ${camera != null}")
            LogcatManager.addLog("Service: Cursor updated to (${adjustedX.toInt()}, ${adjustedY.toInt()}) | Camera: ${camera != null}", "Service")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing results: ${e.message}", e)
        }
    }
    
    override fun onError(error: String, errorCode: Int) {
        Log.e(TAG, "FaceLandmarkerHelper error: $error (code: $errorCode)")
        LogcatManager.addLog("MediaPipe error: $error", "Service")
    }
    
    /**
     * Toggle wake lock on/off
     */
    private fun toggleWakeLock() {
        isWakeLockEnabled = !isWakeLockEnabled
        
        if (isWakeLockEnabled) {
            wakeLock?.let {
                if (!it.isHeld) {
                    try {
                        it.acquire()
                        Log.d(TAG, "PARTIAL_WAKE_LOCK acquired - CPU will stay awake when screen is off")
                        LogcatManager.addLog("Wake lock enabled - CPU will stay awake when screen is off", "Service")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to acquire wake lock: ${e.message}", e)
                        isWakeLockEnabled = false
                    }
                } else {
                    Log.d(TAG, "Wake lock already held")
                }
            } ?: run {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "iris::EyeTrackingWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    try {
                        acquire()
                        Log.d(TAG, "PARTIAL_WAKE_LOCK created and acquired - CPU will stay awake when screen is off")
                        LogcatManager.addLog("Wake lock enabled - CPU will stay awake when screen is off", "Service")
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
                        Log.d(TAG, "PARTIAL_WAKE_LOCK released - CPU may sleep when screen is off")
                        LogcatManager.addLog("Wake lock disabled - CPU may sleep when screen is off", "Service")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to release wake lock: ${e.message}", e)
                    }
                } else {
                    Log.d(TAG, "Wake lock was not held, skipping release")
                }
            }
        }
    }
    
    /**
     * Update wake lock based on screen off tracking setting
     */
    private fun updateWakeLockFromSettings() {
        val shouldBeEnabled = settingsManager?.screenOffTracking ?: true
        
        if (shouldBeEnabled && !isWakeLockEnabled) {
            // Need to acquire wake lock
            wakeLock?.let {
                if (!it.isHeld) {
                    try {
                        it.acquire()
                        isWakeLockEnabled = true
                        Log.d(TAG, "PARTIAL_WAKE_LOCK acquired from settings - CPU will stay awake when screen is off")
                        LogcatManager.addLog("Screen off tracking enabled - wake lock acquired", "Service")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to acquire wake lock from settings: ${e.message}", e)
                        isWakeLockEnabled = false
                    }
                }
            } ?: run {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "iris::EyeTrackingWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    try {
                        acquire()
                        isWakeLockEnabled = true
                        Log.d(TAG, "PARTIAL_WAKE_LOCK created and acquired from settings - CPU will stay awake when screen is off")
                        LogcatManager.addLog("Screen off tracking enabled - wake lock acquired", "Service")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to acquire wake lock from settings: ${e.message}", e)
                        isWakeLockEnabled = false
                    }
                }
            }
        } else if (!shouldBeEnabled && isWakeLockEnabled) {
            // Need to release wake lock
            wakeLock?.let {
                if (it.isHeld) {
                    try {
                        it.release()
                        isWakeLockEnabled = false
                        Log.d(TAG, "PARTIAL_WAKE_LOCK released from settings - CPU may sleep when screen is off")
                        LogcatManager.addLog("Screen off tracking disabled - wake lock released", "Service")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to release wake lock from settings: ${e.message}", e)
                    }
                } else {
                    isWakeLockEnabled = false
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
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
        
        // Safely release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                    Log.d(TAG, "PARTIAL_WAKE_LOCK released successfully")
                    LogcatManager.addLog("Wake lock released", "Service")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing wake lock: ${e.message}", e)
                    LogcatManager.addLog("Error releasing wake lock: ${e.message}", "Service")
                }
            } else {
                Log.d(TAG, "Wake lock was not held, skipping release")
            }
        }
        wakeLock = null
        
        // Shutdown executor
        try {
            backgroundExecutor.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down executor: ${e.message}")
        }
        
        Log.d(TAG, "EyeTrackingAccessibilityService destroyed")
        LogcatManager.addLog("Accessibility service destroyed", "Service")
        
        isServiceEnabled = false
        instance = null
    }
}
