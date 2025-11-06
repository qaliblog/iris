/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.qali.iris.fragment

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.AdapterView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_DRAGGING
import androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE
import androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_SETTLING
import androidx.viewpager2.widget.ViewPager2.ScrollState
import com.qali.iris.CameraForegroundService
import com.qali.iris.EyeBlinkDetector
import com.qali.iris.EyeTracker
import com.qali.iris.FaceLandmarkerHelper
import com.qali.iris.LogcatManager
import com.qali.iris.MainViewModel
import com.qali.iris.MouseControlService
import com.qali.iris.PointerOverlayService
import com.qali.iris.R
import com.qali.iris.SettingsManager
import com.qali.iris.TrackingCalculator
import com.qali.iris.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.jvm.optionals.toList
import kotlin.math.roundToInt

class CameraFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Face Landmarker"
        private var cursorMovementEnabledGlobal = true
        
        fun setCursorMovementEnabled(enabled: Boolean) {
            cursorMovementEnabledGlobal = enabled
        }
        
        fun isCursorMovementEnabled(): Boolean {
            return cursorMovementEnabledGlobal
        }
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private val faceBlendshapesResultAdapter by lazy {
        FaceBlendshapesResultAdapter()
    }

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT // Force front camera

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService
    
    /** Eye tracking */
    private lateinit var eyeTracker: EyeTracker
    private lateinit var settingsManager: SettingsManager
    private lateinit var trackingCalculator: TrackingCalculator
    private lateinit var eyeBlinkDetector: EyeBlinkDetector
    private var isMouseControlEnabled = false
    private var hasCheckedAccessibilityOnResume = false
    private var isSettingsOpening = false

    override fun onResume() {
        super.onResume()
        
        // Check if settings fragment is visible - if so, don't enable cursor movement
        val settingsFragment = childFragmentManager.findFragmentByTag("SettingsFragment") 
            ?: parentFragmentManager.findFragmentByTag("SettingsFragment")
        
        // Only re-enable cursor movement if settings fragment is NOT visible
        if (settingsFragment == null || !settingsFragment.isVisible || !settingsFragment.isResumed) {
            setCursorMovementEnabled(true)
        } else {
            // Settings is open - keep cursor disabled
            setCursorMovementEnabled(false)
            LogcatManager.addLog("Settings is open - keeping cursor disabled", "Camera")
        }
        
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
            return
        }

        // Re-check accessibility service status (but don't show prompt if already checked)
        if (!hasCheckedAccessibilityOnResume) {
            checkAccessibilityPermission(showPrompt = false) // Don't auto-open settings on resume
            hasCheckedAccessibilityOnResume = true
        }
        
        // Ensure pointer service is running if overlay permission is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(requireContext())) {
                try {
                    startPointerService()
                } catch (e: Exception) {
                    LogcatManager.addLog("Failed to start pointer service on resume: ${e.message}", "Camera")
                }
            }
        }
        
        // Start the FaceLandmarkerHelper again when users come back
        // to the foreground (only if it was closed)
        if (this::faceLandmarkerHelper.isInitialized) {
            backgroundExecutor.execute {
                if (faceLandmarkerHelper.isClose()) {
                    faceLandmarkerHelper.setupFaceLandmarker()
                    LogcatManager.addLog("FaceLandmarkerHelper restarted", "Camera")
                }
            }
        }
        
        // Re-initialize camera if not already bound (for preview display)
        // Service handles background processing, fragment handles preview when visible
        // Wait for view to be fully attached before binding camera
        if (_fragmentCameraBinding != null && fragmentCameraBinding.viewFinder != null) {
            if (camera == null && cameraProvider != null) {
                bindCameraUseCases()
            } else if (cameraProvider == null) {
                setUpCamera()
            } else {
                // Camera provider exists but no camera bound - rebind
                bindCameraUseCases()
            }
        } else {
            // View not ready yet, set up camera provider first
            if (cameraProvider == null) {
                setUpCamera()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        hasCheckedAccessibilityOnResume = false
        isSettingsOpening = false
        // Keep camera running in background for continuous pointer updates
        // Camera is bound to ProcessLifecycleOwner, so it continues even when activity pauses
        // Don't stop the face landmarker - let it continue processing in background
        // MediaPipe will continue processing frames as long as wake lock is active
        if(this::faceLandmarkerHelper.isInitialized) {
            viewModel.setMaxFaces(faceLandmarkerHelper.maxNumFaces)
            viewModel.setMinFaceDetectionConfidence(faceLandmarkerHelper.minFaceDetectionConfidence)
            viewModel.setMinFaceTrackingConfidence(faceLandmarkerHelper.minFaceTrackingConfidence)
            viewModel.setMinFacePresenceConfidence(faceLandmarkerHelper.minFacePresenceConfidence)
            viewModel.setDelegate(faceLandmarkerHelper.currentDelegate)
            
            LogcatManager.addLog("App paused but keeping camera active for background tracking", "Camera")
        }
        
        // Release camera binding to let service take over for background processing
        // Service will continue processing frames and updating pointer even when app is closed
        if (camera != null || cameraProvider != null) {
            LogcatManager.addLog("Releasing camera binding - service will take over for background processing", "Camera")
            // Unbind camera use cases - service will rebind its own
            cameraProvider?.unbindAll()
            camera = null
            imageAnalyzer = null
            preview = null
            
            // Service will rebind camera for background processing
            val foregroundService = CameraForegroundService.getInstance()
            foregroundService?.rebindCameraIfNeeded()
        }
        
        // Verify wake lock is active to keep service running
        val foregroundService = CameraForegroundService.getInstance()
        if (foregroundService == null) {
            LogcatManager.addLog("WARNING: Camera foreground service not running - restarting", "Camera")
            try {
                CameraForegroundService.start(requireContext())
            } catch (e: Exception) {
                LogcatManager.addLog("Failed to restart foreground service: ${e.message}", "Camera")
            }
        } else {
            LogcatManager.addLog("Camera foreground service is running - will handle background processing", "Camera")
        }
        
        // Ensure pointer service continues updating from background service
        LogcatManager.addLog("Pointer overlay will continue updating from background service", "Camera")
    }

    override fun onDestroyView() {
        // Release camera when view is destroyed - service will continue in background
        cameraProvider?.unbindAll()
        camera = null
        imageAnalyzer = null
        preview = null
        
        // Service will continue processing frames and updating pointer
        val foregroundService = CameraForegroundService.getInstance()
        foregroundService?.rebindCameraIfNeeded()
        
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Don't stop foreground service here - let it continue running
        // The service will be stopped when the activity is destroyed

        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize EyeTracker
        val displayMetrics = resources.displayMetrics
        eyeTracker = EyeTracker(displayMetrics)
        
        // Initialize Settings and Calculator
        settingsManager = SettingsManager(requireContext())
        trackingCalculator = TrackingCalculator(settingsManager, displayMetrics)
        
        // Set SettingsManager in MouseControlService for cursor update configuration
        MouseControlService.getInstance()?.setSettingsManager(settingsManager)
        
        // Initialize blink detector for click functionality with thresholds from settings
        eyeBlinkDetector = EyeBlinkDetector(
            initialBlinkThreshold = settingsManager.blinkThreshold,
            initialHalfBlinkAccelThreshold = settingsManager.halfBlinkAccelThreshold,
            initialClickDelayThreshold = settingsManager.clickDelayThreshold
        )
        
        // Set EyeTracker in OverlayView
        fragmentCameraBinding.overlay.setEyeTracker(eyeTracker)
        
        // Apply cursor colors from settings
        fragmentCameraBinding.overlay.setCursorColor(settingsManager.cursorColor)
        fragmentCameraBinding.overlay.setClickColor(settingsManager.clickColor)

        // Setup settings button - use FragmentManager directly instead of Navigation Component
        // Set up immediately without delay to ensure it works
        fragmentCameraBinding.settingsButton.setOnClickListener {
            // Prevent multiple rapid clicks
            if (isSettingsOpening) {
                LogcatManager.addLog("Settings opening already in progress, ignoring click", "Camera")
                return@setOnClickListener
            }
            
            // Ensure fragment is still attached
            if (!isAdded || !isResumed) {
                LogcatManager.addLog("Fragment not ready, ignoring settings click", "Camera")
                return@setOnClickListener
            }
            
            Log.e(TAG, "=== SETTINGS BUTTON CLICKED ===")
            LogcatManager.addLog("=== Settings button clicked ===", "Camera")
            
            try {
                // Disable cursor when opening settings
                CameraFragment.setCursorMovementEnabled(false)
                
                val activity = requireActivity()
                val fragmentManager = activity.supportFragmentManager
                
                // Check if settings fragment is already showing or in backstack
                val existingFragment = fragmentManager.findFragmentByTag("SettingsFragment")
                if (existingFragment != null && existingFragment.isVisible) {
                    LogcatManager.addLog("Settings already visible, closing...", "Camera")
                    fragmentManager.popBackStack("SettingsFragment", androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    CameraFragment.setCursorMovementEnabled(true)
                    return@setOnClickListener
                }
                
                isSettingsOpening = true
                LogcatManager.addLog("Opening SettingsFragment using FragmentTransaction...", "Camera")
                
                // Create and show SettingsFragment directly
                val settingsFragment = com.qali.iris.fragment.SettingsFragment()
                val transaction = fragmentManager.beginTransaction()
                
                // Add to the fragment_container (which contains the NavHostFragment)
                // We'll add it on top, not replace
                transaction.add(R.id.fragment_container, settingsFragment, "SettingsFragment")
                transaction.addToBackStack("SettingsFragment")
                transaction.commitAllowingStateLoss() // Use commitAllowingStateLoss to prevent IllegalStateException
                
                LogcatManager.addLog("SettingsFragment transaction committed successfully!", "Camera")
                Log.e(TAG, "SettingsFragment transaction committed")
                
                // Reset flag after a short delay
                fragmentCameraBinding.settingsButton.postDelayed({
                    isSettingsOpening = false
                }, 500)
                
            } catch (e: Exception) {
                isSettingsOpening = false
                CameraFragment.setCursorMovementEnabled(true)
                LogcatManager.addLog("Failed to open settings: ${e.message}", "Camera")
                Log.e(TAG, "Error opening settings", e)
                e.printStackTrace()
                Toast.makeText(requireContext(), "Failed to open settings: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        LogcatManager.addLog("Settings button click listener set up", "Camera")
        Log.e(TAG, "Settings button click listener set up")
        
        // Check accessibility permission (show prompt on initial load)
        checkAccessibilityPermission(showPrompt = true)
        
        // Request overlay permission and start pointer service
        requestOverlayPermission()
        
        // Request notification permission if needed (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
                LogcatManager.addLog("Requesting notification permission", "Camera")
            }
        }
        
        // Start camera foreground service to keep camera active
        try {
            CameraForegroundService.start(requireContext())
            LogcatManager.addLog("Camera foreground service started", "Camera")
        } catch (e: Exception) {
            LogcatManager.addLog("Failed to start camera foreground service: ${e.message}", "Camera")
            Log.e(TAG, "Failed to start camera foreground service", e)
            // If service fails, log the error
            e.printStackTrace()
        }
        
        // Initialize logging
        LogcatManager.addLog("CameraFragment initialized", "Camera")
        
        // Initialize background executor for any fragment-specific tasks
        backgroundExecutor = Executors.newSingleThreadExecutor()
        
        // Initialize FaceLandmarkerHelper for MediaPipe
        faceLandmarkerHelper = FaceLandmarkerHelper(
            context = requireContext(),
            runningMode = RunningMode.LIVE_STREAM,
            minFaceDetectionConfidence = viewModel.currentMinFaceDetectionConfidence,
            minFaceTrackingConfidence = viewModel.currentMinFaceTrackingConfidence,
            minFacePresenceConfidence = viewModel.currentMinFacePresenceConfidence,
            maxNumFaces = viewModel.currentMaxFaces,
            currentDelegate = viewModel.currentDelegate,
            faceLandmarkerHelperListener = this
        )
        
        // Wait for view to be fully laid out before initializing camera
        // This ensures viewFinder is attached to window and display is available
        fragmentCameraBinding.viewFinder.post {
            if (isAdded && !isDetached) {
                // Initialize camera for preview (service handles processing, but we need preview for UI)
                setUpCamera()
            }
        }
    }
    
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(requireContext())) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${requireContext().packageName}")
                )
                startActivity(intent)
                LogcatManager.addLog("Overlay permission requested", "Camera")
            } else {
                startPointerService()
            }
        } else {
            startPointerService()
        }
    }
    
    private fun startPointerService() {
        val intent = Intent(requireContext(), PointerOverlayService::class.java)
        // Use regular startService - the service will call startForeground itself
        requireContext().startService(intent)
        LogcatManager.addLog("Pointer overlay service started", "Camera")
    }
    
    private fun checkAccessibilityPermission(showPrompt: Boolean = true) {
        // First check if service instance is available (most reliable check)
        val serviceInstance = MouseControlService.getInstance()
        if (serviceInstance != null) {
            LogcatManager.addLog("MouseControlService instance found - service is running", "Camera")
            isMouseControlEnabled = true
            return
        }
        
        // Fallback to checking enabled services list
        val isEnabled = isAccessibilityServiceEnabled()
        if (!isEnabled) {
            LogcatManager.addLog("Accessibility service not enabled", "Camera")
            isMouseControlEnabled = false
            
            // Only show prompt if explicitly requested (not on resume)
            if (showPrompt && isResumed && isAdded) {
                Toast.makeText(requireContext(), "Please enable accessibility service for mouse control", Toast.LENGTH_LONG).show()
                
                // Open accessibility settings
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                LogcatManager.addLog("Opened accessibility settings", "Camera")
            }
        } else {
            LogcatManager.addLog("Accessibility service is enabled and ready", "Camera")
            isMouseControlEnabled = true
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = ContextCompat.getSystemService(requireContext(), AccessibilityManager::class.java) as? AccessibilityManager
            ?: return false
        
        if (!accessibilityManager.isEnabled) {
            LogcatManager.addLog("Accessibility manager not enabled", "Camera")
            return false
        }
        
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val serviceComponent = ComponentName(requireContext(), MouseControlService::class.java)
        val servicePackage = serviceComponent.packageName
        val serviceClass = serviceComponent.className
        
        // More robust check: compare both package and class name, handling both short and fully qualified names
        val isEnabled = enabledServices.any { 
            val info = it.resolveInfo.serviceInfo
            val enabledComponent = ComponentName(info.packageName, info.name)
            
            // Direct component comparison
            enabledComponent == serviceComponent || 
            // Also check if package matches and class name matches (handles fully qualified names)
            (info.packageName == servicePackage && 
             (info.name == serviceClass || info.name.endsWith(serviceClass)))
        }
        
        if (!isEnabled) {
            LogcatManager.addLog("MouseControlService not in enabled list", "Camera")
            LogcatManager.addLog("Looking for: $servicePackage/$serviceClass", "Camera")
            LogcatManager.addLog("Enabled services count: ${enabledServices.size}", "Camera")
            enabledServices.forEach { service ->
                val info = service.resolveInfo.serviceInfo
                LogcatManager.addLog("  - ${info.packageName}/${info.name}", "Camera")
            }
        } else {
            LogcatManager.addLog("MouseControlService is enabled and ready", "Camera")
        }
        
        return isEnabled
    }

    // Removed bottom sheet controls - not needed for full screen app

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                try {
                    // CameraProvider
                    cameraProvider = cameraProviderFuture.get()

                    // Only bind if view is ready
                    if (_fragmentCameraBinding != null && fragmentCameraBinding.viewFinder != null) {
                        // Build and bind the camera use cases
                        bindCameraUseCases()
                    } else {
                        LogcatManager.addLog("Camera provider ready but view not ready yet - will bind when view is ready", "Camera")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up camera: ${e.message}", e)
                    LogcatManager.addLog("Error setting up camera: ${e.message}", "Camera")
                }
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Get rotation safely - handle null display
        val rotation = try {
            fragmentCameraBinding.viewFinder.display?.rotation 
                ?: requireContext().resources.configuration.orientation.let {
                    when (it) {
                        Configuration.ORIENTATION_LANDSCAPE -> 90
                        Configuration.ORIENTATION_PORTRAIT -> 0
                        else -> 0
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting display rotation: ${e.message}", e)
            0 // Default to portrait
        }

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        detectFace(image)
                    }
                }

        // Unbind all to ensure clean state
        // The fragment needs Preview + ImageAnalysis for display + processing
        // Service will rebind its own ImageAnalysis when fragment releases
        cameraProvider.unbindAll()
        
        // Notify service that fragment is taking camera control
        CameraForegroundService.getInstance()?.let { service ->
            // Service will release its binding if needed
            LogcatManager.addLog("Fragment taking camera control - service will release", "Camera")
        }

        try {
            // Bind camera to ProcessLifecycleOwner to keep it running even when activity pauses
            // ProcessLifecycleOwner represents the entire application lifecycle, not just one activity
            // This ensures camera continues in background - critical for continuous cursor control
            // Wake lock ensures CPU stays awake for MediaPipe processing
            val lifecycleOwner = androidx.lifecycle.ProcessLifecycleOwner.get()
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, imageAnalyzer
            )
            
            LogcatManager.addLog("Camera bound to ProcessLifecycleOwner - will continue in background", "Camera")

            // Attach the viewfinder's surface provider to preview use case
            // Ensure viewFinder is properly initialized before setting surface provider
            if (_fragmentCameraBinding != null && fragmentCameraBinding.viewFinder != null) {
                preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
                LogcatManager.addLog("Camera preview surface provider attached", "Camera")
            } else {
                Log.e(TAG, "Warning: viewFinder not available when binding camera")
                LogcatManager.addLog("Warning: viewFinder not available when binding camera", "Camera")
            }
            LogcatManager.addLog("Camera bound successfully", "Camera")
            
            // Verify wake lock is active
            val foregroundService = CameraForegroundService.getInstance()
            if (foregroundService == null) {
                LogcatManager.addLog("WARNING: Camera foreground service not running - restarting", "Camera")
                try {
                    CameraForegroundService.start(requireContext())
                } catch (e: Exception) {
                    LogcatManager.addLog("Failed to restart foreground service: ${e.message}", "Camera")
                }
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            LogcatManager.addLog("Camera binding failed: ${exc.message}", "Camera")
        }
    }

    private fun detectFace(imageProxy: ImageProxy) {
        // Always process frames, even in background - this is critical for continuous cursor updates
        // The image analyzer runs on background thread and is bound to activity lifecycle
        // Wake lock ensures CPU stays awake so MediaPipe can process frames
        var imageClosed = false
        try {
            // Log periodically to confirm we're still getting frames (especially in background)
            val now = System.currentTimeMillis()
            val isBackground = !isResumed || activity?.isFinishing == true
            if (now % 5000 < 100) { // Log every 5 seconds to track if frames are coming
                val wakeLockActive = CameraForegroundService.getInstance() != null
                LogcatManager.addLog("Processing frame - Background: $isBackground | WakeLock: $wakeLockActive | MediaPipe: ${this::faceLandmarkerHelper.isInitialized}", "Camera")
                Log.d(TAG, "Camera frame processing - Background: $isBackground, WakeLock: $wakeLockActive")
            }
            
            // Ensure MediaPipe helper is initialized before processing
            if (!this::faceLandmarkerHelper.isInitialized) {
                imageProxy.close()
                imageClosed = true
                return
            }
            
            faceLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
            )
            // Don't close imageProxy here - MediaPipe will close it in its callback
            imageClosed = true // Mark as handled by MediaPipe
        } catch (e: Exception) {
            // Log but don't crash - MediaPipe might have issues
            if (System.currentTimeMillis() % 2000 < 50) { // Log every 2 seconds
                Log.e(TAG, "Failed to detect face: ${e.message}", e)
                LogcatManager.addLog("Error detecting face: ${e.message}", "Camera")
            }
            // Only close if we haven't already passed it to MediaPipe
            if (!imageClosed) {
                try {
                    imageProxy.close() // Important: close the image proxy if processing fails
                    imageClosed = true
                } catch (closeEx: Exception) {
                    // Ignore close errors
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            val rotation = fragmentCameraBinding.viewFinder.display?.rotation 
                ?: newConfig.orientation.let {
                    when (it) {
                        Configuration.ORIENTATION_LANDSCAPE -> 90
                        Configuration.ORIENTATION_PORTRAIT -> 0
                        else -> 0
                    }
                }
            imageAnalyzer?.targetRotation = rotation
        } catch (e: Exception) {
            Log.e(TAG, "Error updating rotation on configuration change: ${e.message}", e)
        }
    }

    // Update UI after face have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView
    override fun onResults(
        resultBundle: FaceLandmarkerHelper.ResultBundle
    ) {
        // Process even when app is in background - this ensures continuous updates
        // This method runs on background thread from MediaPipe
        val faceLandmarksList = resultBundle.result.faceLandmarks()
        
        if (faceLandmarksList.isNotEmpty()) {
            // Track eyes and control mouse
            val landmarks = faceLandmarksList[0] // Use first face
            
            // Update eye tracker settings
            eyeTracker.setUseOneEye(settingsManager.useOneEyeDetection)
            
            val trackingResult = eyeTracker.trackEyes(landmarks)
            
            // Apply all adjustments from settings first (needed for click position)
            val (adjustedX, adjustedY) = trackingCalculator.calculateAdjustedPosition(trackingResult)
            
            // Update blink detector thresholds if settings changed
            eyeBlinkDetector.setBlinkThreshold(settingsManager.blinkThreshold)
            eyeBlinkDetector.setHalfBlinkAccelThreshold(settingsManager.halfBlinkAccelThreshold)
            eyeBlinkDetector.setClickDelayThreshold(settingsManager.clickDelayThreshold)
            
            // Detect blink using eyelid landmarks (preferred) or fallback to eye area
            val blinkDetected = if (trackingResult.leftEyelidLandmarks != null || trackingResult.rightEyelidLandmarks != null) {
                // Use eyelid landmarks for more accurate detection
                val combinedEyelid = if (settingsManager.useOneEyeDetection) {
                    trackingResult.rightEyelidLandmarks ?: trackingResult.leftEyelidLandmarks
                } else {
                    // Average both eyes
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
                
                if (combinedEyelid != null) {
                    // Pass click position for relative calculations
                    val clickPosition = android.graphics.PointF(adjustedX, adjustedY)
                    eyeBlinkDetector.processEyelidLandmarks(
                        upperLidY = combinedEyelid.upperLidY,
                        lowerLidY = combinedEyelid.lowerLidY,
                        clickPosition = clickPosition
                    )
                } else {
                    false
                }
            } else {
                // Fallback to eye area method
                eyeBlinkDetector.processEyeArea(trackingResult.eyeArea)
            }
            if (blinkDetected) {
                // Trigger click
                try {
                    MouseControlService.performClick()
                    PointerOverlayService.indicateClick()
                    // Draw click dot at landmark position and indicate click in overlay
                    if (isResumed && _fragmentCameraBinding != null) {
                        activity?.runOnUiThread {
                            if (_fragmentCameraBinding != null) {
                                fragmentCameraBinding.overlay.setClickPosition(adjustedX, adjustedY)
                                fragmentCameraBinding.overlay.indicateClick()
                            }
                        }
                    }
                    LogcatManager.addLog("Click detected via blink", "Tracking")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to perform click: ${e.message}", e)
                }
            }
            
            // Check global flag to see if cursor movement should be enabled (disabled when settings are open)
            val cursorEnabled = CameraFragment.isCursorMovementEnabled()
            
            // Always process tracking even in background - this ensures MediaPipe continues working
            // The activity lifecycle binding keeps camera active even when app is in background
            
            // Only update pointer overlay and mouse control if cursor movement is enabled
            // When settings are open, completely disable cursor to prevent interference with typing
            if (cursorEnabled) {
                // Update system-wide pointer overlay (works even in background)
                // Update immediately without delays to ensure timely transmission
                try {
                    PointerOverlayService.updatePointerPosition(adjustedX, adjustedY)
                    // Log periodically to confirm updates are happening (only when paused/background)
                    val now = System.currentTimeMillis()
                    val isBackground = !isResumed || activity?.isFinishing == true
                    if (now % 3000 < 100 && isBackground) { // Log every 3 seconds when in background
                        LogcatManager.addLog("Background: Pointer updated (${adjustedX.toInt()}, ${adjustedY.toInt()}) | WakeLock: ${CameraForegroundService.getInstance() != null}", "Tracking")
                        Log.d(TAG, "Background pointer update: ($adjustedX, $adjustedY)")
                    }
                } catch (e: Exception) {
                    // Log but don't crash - service might not be available
                    if (System.currentTimeMillis() % 2000 < 50) { // Log every 2 seconds
                        Log.e(TAG, "Failed to update pointer overlay: ${e.message}", e)
                        LogcatManager.addLog("Failed to update pointer: ${e.message}", "Tracking")
                    }
                }
                
                // Control mouse if accessibility is enabled AND cursor movement is enabled
                // Update immediately to ensure timely cursor movement
                if (isMouseControlEnabled) {
                    try {
                        MouseControlService.moveCursor(adjustedX, adjustedY)
                    } catch (e: Exception) {
                        // Log but don't crash
                        if (System.currentTimeMillis() % 2000 < 50) {
                            Log.e(TAG, "Failed to move cursor: ${e.message}", e)
                        }
                    }
                }
            } else {
                // Cursor movement is disabled (settings open or user typing)
                // Hide pointer overlay completely when disabled to prevent interference with input
                try {
                    PointerOverlayService.updatePointerPosition(-1f, -1f)
                    // Ensure pointer overlay view is hidden and not touchable
                    val pointerService = PointerOverlayService.getInstance()
                    pointerService?.let {
                        try {
                            // Hide the overlay view completely
                            it.hidePointer()
                        } catch (e: Exception) {
                            // Ignore - service might not expose this
                        }
                    }
                } catch (e: Exception) {
                    // Silent fail - service might not be available
                }
                // Don't log this too often - only occasionally
                if (System.currentTimeMillis() % 5000 < 100) {
                    LogcatManager.addLog("Cursor movement disabled (settings open or user typing)", "Tracking")
                }
            }
            
            // Update UI only if fragment is still active and visible
            if (isResumed && _fragmentCameraBinding != null) {
                activity?.runOnUiThread {
                    if (_fragmentCameraBinding != null) {
                        // Update pointer position on overlay
                        fragmentCameraBinding.overlay.setPointerPosition(adjustedX, adjustedY)

                        // Pass necessary information to OverlayView for drawing on the canvas
                        fragmentCameraBinding.overlay.setResults(
                            resultBundle.result,
                            resultBundle.inputImageHeight,
                            resultBundle.inputImageWidth,
                            RunningMode.LIVE_STREAM
                        )
                        // Force a redraw
                        fragmentCameraBinding.overlay.invalidate()
                    }
                }
            }
            
            // Log periodically (not every frame to avoid spam)
            if (System.currentTimeMillis() % 1000 < 50) { // Log roughly every 1000ms
                LogcatManager.addLog("Eye: (${adjustedX.toInt()}, ${adjustedY.toInt()}) | Area: ${String.format(Locale.US, "%.4f", trackingResult.eyeArea)} | Pos: (${String.format(Locale.US, "%.2f", trackingResult.eyePositionX)}, ${String.format(Locale.US, "%.2f", trackingResult.eyePositionY)})", "Tracking")
            }
        } else {
            // No face detected - hide pointer (only if cursor movement is enabled)
            // Always hide pointer overlay even if cursor movement disabled
            if (CameraFragment.isCursorMovementEnabled()) {
                PointerOverlayService.updatePointerPosition(-1f, -1f)
            }
            
            if (isResumed && _fragmentCameraBinding != null) {
                activity?.runOnUiThread {
                    if (_fragmentCameraBinding != null) {
                        fragmentCameraBinding.overlay.setPointerPosition(-1f, -1f)
                    }
                }
            }
        }
    }

    override fun onEmpty() {
        // Hide pointer overlay (only if cursor movement is enabled)
        if (CameraFragment.isCursorMovementEnabled()) {
            PointerOverlayService.updatePointerPosition(-1f, -1f)
        }
        
        // Update UI only if fragment is still active and visible
        if (isResumed && _fragmentCameraBinding != null) {
            activity?.runOnUiThread {
                if (_fragmentCameraBinding != null) {
                    fragmentCameraBinding.overlay.setPointerPosition(-1f, -1f)
                    fragmentCameraBinding.overlay.clear()
                }
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            faceBlendshapesResultAdapter.updateResults(null)
            faceBlendshapesResultAdapter.notifyDataSetChanged()

            LogcatManager.addLog("Error: $error", "Error")
            if (errorCode == FaceLandmarkerHelper.GPU_ERROR) {
                LogcatManager.addLog("GPU error, but continuing with GPU", "Error")
            }
        }
    }
}
