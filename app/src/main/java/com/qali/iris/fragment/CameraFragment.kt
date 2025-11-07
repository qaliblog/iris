package com.qali.iris.fragment

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private val faceBlendshapesResultAdapter by lazy { FaceBlendshapesResultAdapter() }

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
    private var mouseServiceOnConnected: ((MouseControlService) -> Unit)? = null

    /* --------------------------------------------------------------------- */
    /* --------------------------- LIFECYCLE ------------------------------- */
    /* --------------------------------------------------------------------- */

    override fun onResume() {
        super.onResume()

        // -----------------------------------------------------------------
        // Cursor movement handling when Settings fragment is visible
        // -----------------------------------------------------------------
        val settingsFragment = childFragmentManager.findFragmentByTag("SettingsFragment")
            ?: parentFragmentManager.findFragmentByTag("SettingsFragment")

        if (settingsFragment == null || !settingsFragment.isVisible || !settingsFragment.isResumed) {
            setCursorMovementEnabled(true)
        } else {
            setCursorMovementEnabled(false)
            LogcatManager.addLog("Settings is open - keeping cursor disabled", "Camera")
        }

        // -----------------------------------------------------------------
        // Permissions
        // -----------------------------------------------------------------
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_camera_to_permissions)
            return
        }

        // -----------------------------------------------------------------
        // Accessibility check (only once per resume)
        // -----------------------------------------------------------------
        if (!hasCheckedAccessibilityOnResume) {
            checkAccessibilityPermission(showPrompt = false)
            hasCheckedAccessibilityOnResume = true
        }

        // -----------------------------------------------------------------
        // Overlay / pointer service
        // -----------------------------------------------------------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(requireContext())) {
            try { startPointerService() } catch (e: Exception) {
                LogcatManager.addLog("Failed to start pointer service on resume: ${e.message}", "Camera")
            }
        }

        // -----------------------------------------------------------------
        // Restart MediaPipe if it was closed
        // -----------------------------------------------------------------
        if (this::faceLandmarkerHelper.isInitialized) {
            backgroundExecutor.execute {
                if (faceLandmarkerHelper.isClose()) {
                    faceLandmarkerHelper.setupFaceLandmarker()
                    LogcatManager.addLog("FaceLandmarkerHelper restarted", "Camera")
                }
            }
        }

        // -----------------------------------------------------------------
        // Camera binding (preview only – processing is done by the service)
        // -----------------------------------------------------------------
        if (_fragmentCameraBinding != null && fragmentCameraBinding.viewFinder != null) {
            if (camera == null && cameraProvider != null) {
                bindCameraUseCases()
            } else if (cameraProvider == null) {
                setUpCamera()
            } else {
                bindCameraUseCases()
            }
        } else {
            if (cameraProvider == null) setUpCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        hasCheckedAccessibilityOnResume = false
        isSettingsOpening = false

        // -----------------------------------------------------------------
        // Keep MediaPipe running in background (wake-lock handled by service)
        // -----------------------------------------------------------------
        if (this::faceLandmarkerHelper.isInitialized) {
            viewModel.setMaxFaces(faceLandmarkerHelper.maxNumFaces)
            viewModel.setMinFaceDetectionConfidence(faceLandmarkerHelper.minFaceDetectionConfidence)
            viewModel.setMinFaceTrackingConfidence(faceLandmarkerHelper.minFaceTrackingConfidence)
            viewModel.setMinFacePresenceConfidence(faceLandmarkerHelper.minFacePresenceConfidence)
            viewModel.setDelegate(faceLandmarkerHelper.currentDelegate)

            LogcatManager.addLog("App paused but keeping camera active for background tracking", "Camera")
        }

        // -----------------------------------------------------------------
        // Release fragment camera binding – service will take over
        // -----------------------------------------------------------------
        if (camera != null || cameraProvider != null) {
            LogcatManager.addLog("Releasing camera binding - service will take over for background processing", "Camera")
            cameraProvider?.unbindAll()
            camera = null
            imageAnalyzer = null
            preview = null

            CameraForegroundService.getInstance()?.rebindCameraIfNeeded()
        }

        // -----------------------------------------------------------------
        // Ensure foreground service is alive
        // -----------------------------------------------------------------
        if (CameraForegroundService.getInstance() == null) {
            LogcatManager.addLog("WARNING: Camera foreground service not running - restarting", "Camera")
            try { CameraForegroundService.start(requireContext()) } catch (e: Exception) {
                LogcatManager.addLog("Failed to restart foreground service: ${e.message}", "Camera")
            }
        } else {
            LogcatManager.addLog("Camera foreground service is running - will handle background processing", "Camera")
        }

        LogcatManager.addLog("Pointer overlay will continue updating from background service", "Camera")
    }

    override fun onDestroyView() {
        cameraProvider?.unbindAll()
        camera = null
        imageAnalyzer = null
        preview = null

        CameraForegroundService.getInstance()?.rebindCameraIfNeeded()

        _fragmentCameraBinding = null
        super.onDestroyView()

        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)

        mouseServiceOnConnected?.let { MouseControlService.unregisterOnServiceConnected(it) }
        mouseServiceOnConnected = null
    }

    /* --------------------------------------------------------------------- */
    /* --------------------------- VIEW CREATION --------------------------- */
    /* --------------------------------------------------------------------- */

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // -----------------------------------------------------------------
        // Eye-tracking components
        // -----------------------------------------------------------------
        val displayMetrics = resources.displayMetrics
        eyeTracker = EyeTracker(displayMetrics)
        settingsManager = SettingsManager(requireContext())
        trackingCalculator = TrackingCalculator(settingsManager, displayMetrics)

        MouseControlService.getInstance()?.setSettingsManager(settingsManager)
        mouseServiceOnConnected = { service ->
            service.setSettingsManager(settingsManager)
            MouseControlService.getPendingCursorPosition()?.let { pointer ->
                PointerOverlayService.updatePointerPosition(pointer.x, pointer.y)
            }
        }
        mouseServiceOnConnected?.let { MouseControlService.registerOnServiceConnected(it) }

        eyeBlinkDetector = EyeBlinkDetector(
            initialBlinkThreshold = settingsManager.blinkThreshold,
            initialHalfBlinkAccelThreshold = settingsManager.halfBlinkAccelThreshold,
            initialClickDelayThreshold = settingsManager.clickDelayThreshold
        )

        fragmentCameraBinding.overlay.setEyeTracker(eyeTracker)
        fragmentCameraBinding.overlay.setCursorColor(settingsManager.cursorColor)
        fragmentCameraBinding.overlay.setClickColor(settingsManager.clickColor)

        // -----------------------------------------------------------------
        // Settings button
        // -----------------------------------------------------------------
        fragmentCameraBinding.settingsButton.setOnClickListener {
            if (isSettingsOpening) {
                LogcatManager.addLog("Settings opening already in progress, ignoring click", "Camera")
                return@setOnClickListener
            }
            if (!isAdded || !isResumed) {
                LogcatManager.addLog("Fragment not ready, ignoring settings click", "Camera")
                return@setOnClickListener
            }

            Log.e(TAG, "=== SETTINGS BUTTON CLICKED ===")
            LogcatManager.addLog("=== Settings button clicked ===", "Camera")

            try {
                CameraFragment.setCursorMovementEnabled(false)

                val activity = requireActivity()
                val fragmentManager = activity.supportFragmentManager
                val existing = fragmentManager.findFragmentByTag("SettingsFragment")
                if (existing != null && existing.isVisible) {
                    LogcatManager.addLog("Settings already visible, closing...", "Camera")
                    fragmentManager.popBackStack("SettingsFragment", androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    CameraFragment.setCursorMovementEnabled(true)
                    return@setOnClickListener
                }

                isSettingsOpening = true
                LogcatManager.addLog("Opening SettingsFragment using FragmentTransaction...", "Camera")

                val settingsFragment = com.qali.iris.fragment.SettingsFragment()
                val transaction = fragmentManager.beginTransaction()
                transaction.add(R.id.fragment_container, settingsFragment, "SettingsFragment")
                transaction.addToBackStack("SettingsFragment")
                transaction.commitAllowingStateLoss()

                LogcatManager.addLog("SettingsFragment transaction committed successfully!", "Camera")
                Log.e(TAG, "SettingsFragment transaction committed")

                fragmentCameraBinding.settingsButton.postDelayed({ isSettingsOpening = false }, 500)
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

        // -----------------------------------------------------------------
        // Permissions / services
        // -----------------------------------------------------------------
        checkAccessibilityPermission(showPrompt = true)
        requestOverlayPermission()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
                LogcatManager.addLog("Requesting notification permission", "Camera")
            }
        }

        try {
            CameraForegroundService.start(requireContext())
            LogcatManager.addLog("Camera foreground service started", "Camera")
        } catch (e: Exception) {
            LogcatManager.addLog("Failed to start camera foreground service: ${e.message}", "Camera")
            Log.e(TAG, "Failed to start camera foreground service", e)
            e.printStackTrace()
        }

        LogcatManager.addLog("CameraFragment initialized", "Camera")

        // -----------------------------------------------------------------
        // Executors & MediaPipe
        // -----------------------------------------------------------------
        backgroundExecutor = Executors.newSingleThreadExecutor()

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

        // -----------------------------------------------------------------
        // Camera (preview only)
        // -----------------------------------------------------------------
        fragmentCameraBinding.viewFinder.post {
            if (isAdded && !isDetached) setUpCamera()
        }
    }

    /* --------------------------------------------------------------------- */
    /* --------------------------- PERMISSIONS ----------------------------- */
    /* --------------------------------------------------------------------- */

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(requireContext())) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")
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
        requireContext().startService(intent)
        LogcatManager.addLog("Pointer overlay service started", "Camera")
    }

    /** --------------------------------------------------------------- */
    /**  Accessibility permission handling (fixed compile errors)      */
    /** --------------------------------------------------------------- */
    private fun checkAccessibilityPermission(showPrompt: Boolean = true) {
        // Fast-path: service already running
        if (MouseControlService.getInstance() != null) {
            LogcatManager.addLog("MouseControlService instance found - service is running", "Camera")
            isMouseControlEnabled = true
            return
        }

        val enabled = isAccessibilityServiceEnabled()
        if (!enabled) {
            LogcatManager.addLog("Accessibility service not enabled", "Camera")
            isMouseControlEnabled = false
    
            if (showPrompt && isResumed && isAdded) {
                Toast.makeText(
                    requireContext(),
                    "Please enable accessibility service for mouse control",
                    Toast.LENGTH_LONG
                ).show()
    
                // === FIXED: Use correct constant and guard 'data' ===
                val component = ComponentName(requireContext(), MouseControlService::class.java)
                val detailsIntent = Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // API 30+
                        data = Uri.parse("package:${component.packageName}/${component.className}")
                    }
                }
    
                val resolved = detailsIntent.resolveActivity(requireContext().packageManager)
                if (resolved != null) {
                    startActivity(detailsIntent)
                    LogcatManager.addLog("Opened accessibility details for MouseControlService", "Camera")
                } else {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    LogcatManager.addLog("Opened accessibility settings (fallback)", "Camera")
                }
            }
        } else {
            LogcatManager.addLog("Accessibility service is enabled and ready", "Camera")
            isMouseControlEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val secureEnabled = MouseControlService.isAccessibilityServiceEnabled(requireContext())
        if (!secureEnabled) LogcatManager.addLog("Secure settings missing MouseControlService entry", "Camera")

        val am = ContextCompat.getSystemService(requireContext(), AccessibilityManager::class.java) as? AccessibilityManager
            ?: return false

        if (!am.isEnabled) {
            LogcatManager.addLog("Accessibility manager not enabled", "Camera")
            return false
        }

        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val target = ComponentName(requireContext(), MouseControlService::class.java)

        val found = enabledServices.any { info ->
            val svc = info.resolveInfo.serviceInfo
            val enabledComponent = ComponentName(svc.packageName, svc.name)
            enabledComponent == target ||
                    (svc.packageName == target.packageName &&
                            (svc.name == target.className || svc.name.endsWith(target.className)))
        }

        if (!found) {
            LogcatManager.addLog("MouseControlService not in enabled list", "Camera")
            LogcatManager.addLog("Looking for: ${target.packageName}/${target.className}", "Camera")
            LogcatManager.addLog("Enabled services count: ${enabledServices.size}", "Camera")
            enabledServices.forEach {
                val i = it.resolveInfo.serviceInfo
                LogcatManager.addLog("  - ${i.packageName}/${i.name}", "Camera")
            }
        } else {
            LogcatManager.addLog("MouseControlService is enabled and ready", "Camera")
        }

        return secureEnabled && found
    }

    /* --------------------------------------------------------------------- */
    /* --------------------------- CAMERA SETUP ----------------------------- */
    /* --------------------------------------------------------------------- */

    private fun setUpCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            try {
                cameraProvider = future.get()
                if (_fragmentCameraBinding != null && fragmentCameraBinding.viewFinder != null) {
                    bindCameraUseCases()
                } else {
                    LogcatManager.addLog("Camera provider ready but view not ready yet - will bind when view is ready", "Camera")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up camera: ${e.message}", e)
                LogcatManager.addLog("Error setting up camera: ${e.message}", "Camera")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cp = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val selector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        val rotation = try {
            fragmentCameraBinding.viewFinder.display?.rotation
                ?: resources.configuration.orientation.let {
                    when (it) {
                        Configuration.ORIENTATION_LANDSCAPE -> 90
                        Configuration.ORIENTATION_PORTRAIT -> 0
                        else -> 0
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting display rotation: ${e.message}", e)
            0
        }

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> detectFace(image) } }

        cp.unbindAll()

        CameraForegroundService.getInstance()?.let {
            LogcatManager.addLog("Fragment taking camera control - service will release", "Camera")
        }

        try {
            camera = cp.bindToLifecycle(this, selector, preview, imageAnalyzer)
            LogcatManager.addLog("Camera bound to Fragment lifecycle (releases on pause)", "Camera")

            if (_fragmentCameraBinding != null && fragmentCameraBinding.viewFinder != null) {
                preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
                LogcatManager.addLog("Camera preview surface provider attached", "Camera")
            } else {
                Log.e(TAG, "Warning: viewFinder not available when binding camera")
                LogcatManager.addLog("Warning: viewFinder not available when binding camera", "Camera")
            }
            LogcatManager.addLog("Camera bound successfully", "Camera")

            // Ensure foreground service is alive
            if (CameraForegroundService.getInstance() == null) {
                LogcatManager.addLog("WARNING: Camera foreground service not running - restarting", "Camera")
                try { CameraForegroundService.start(requireContext()) } catch (e: Exception) {
                    LogcatManager.addLog("Failed to restart foreground service: ${e.message}", "Camera")
                }
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            LogcatManager.addLog("Camera binding failed: ${exc.message}", "Camera")
        }
    }

    /* --------------------------------------------------------------------- */
    /* --------------------------- FACE DETECTION -------------------------- */
    /* --------------------------------------------------------------------- */

    private fun detectFace(imageProxy: ImageProxy) {
        var closed = false
        try {
            val now = System.currentTimeMillis()
            val bg = !isResumed || activity?.isFinishing == true
            if (now % 5000 < 100) {
                val wake = CameraForegroundService.getInstance() != null
                LogcatManager.addLog(
                    "Processing frame - Background: $bg | WakeLock: $wake | MediaPipe: ${this::faceLandmarkerHelper.isInitialized}",
                    "Camera"
                )
                Log.d(TAG, "Camera frame processing - Background: $bg, WakeLock: $wake")
            }

            if (!this::faceLandmarkerHelper.isInitialized) {
                imageProxy.close()
                closed = true
                return
            }

            faceLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
            )
            closed = true
        } catch (e: Exception) {
            if (System.currentTimeMillis() % 2000 < 50) {
                Log.e(TAG, "Failed to detect face: ${e.message}", e)
                LogcatManager.addLog("Error detecting face: ${e.message}", "Camera")
            }
            if (!closed) {
                try { imageProxy.close() } catch (_: Exception) {}
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            val rot = fragmentCameraBinding.viewFinder.display?.rotation
                ?: newConfig.orientation.let {
                    when (it) {
                        Configuration.ORIENTATION_LANDSCAPE -> 90
                        Configuration.ORIENTATION_PORTRAIT -> 0
                        else -> 0
                    }
                }
            imageAnalyzer?.targetRotation = rot
        } catch (e: Exception) {
            Log.e(TAG, "Error updating rotation on configuration change: ${e.message}", e)
        }
    }

    /* --------------------------------------------------------------------- */
    /* --------------------------- RESULT HANDLING -------------------------- */
    /* --------------------------------------------------------------------- */

    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        val faceLandmarksList = resultBundle.result.faceLandmarks()
        if (faceLandmarksList.isNotEmpty()) {
            val landmarks = faceLandmarksList[0]

            eyeTracker.setUseOneEye(settingsManager.useOneEyeDetection)
            val trackingResult = eyeTracker.trackEyes(landmarks)

            val (adjustedX, adjustedY) = trackingCalculator.calculateAdjustedPosition(trackingResult)

            eyeBlinkDetector.setBlinkThreshold(settingsManager.blinkThreshold)
            eyeBlinkDetector.setHalfBlinkAccelThreshold(settingsManager.halfBlinkAccelThreshold)
            eyeBlinkDetector.setClickDelayThreshold(settingsManager.clickDelayThreshold)

            val blinkDetected = if (trackingResult.leftEyelidLandmarks != null || trackingResult.rightEyelidLandmarks != null) {
                val combined = if (settingsManager.useOneEyeDetection) {
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

                combined?.let {
                    eyeBlinkDetector.processEyelidLandmarks(
                        upperLidY = it.upperLidY,
                        lowerLidY = it.lowerLidY,
                        clickPosition = android.graphics.PointF(adjustedX, adjustedY)
                    )
                } ?: false
            } else {
                eyeBlinkDetector.processEyeArea(trackingResult.eyeArea)
            }

            if (blinkDetected) {
                try {
                    MouseControlService.performClick()
                    PointerOverlayService.indicateClick()
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

            val cursorEnabled = CameraFragment.isCursorMovementEnabled()
            if (cursorEnabled) {
                try {
                    PointerOverlayService.updatePointerPosition(adjustedX, adjustedY)
                    val now = System.currentTimeMillis()
                    val bg = !isResumed || activity?.isFinishing == true
                    if (now % 3000 < 100 && bg) {
                        LogcatManager.addLog(
                            "Background: Pointer updated (${adjustedX.toInt()}, ${adjustedY.toInt()}) | WakeLock: ${CameraForegroundService.getInstance() != null}",
                            "Tracking"
                        )
                        Log.d(TAG, "Background pointer update: ($adjustedX, $adjustedY)")
                    }
                } catch (e: Exception) {
                    if (System.currentTimeMillis() % 2000 < 50) {
                        Log.e(TAG, "Failed to update pointer overlay: ${e.message}", e)
                        LogcatManager.addLog("Failed to update pointer: ${e.message}", "Tracking")
                    }
                }

                if (isMouseControlEnabled) {
                    try { MouseControlService.moveCursor(adjustedX, adjustedY) } catch (e: Exception) {
                        if (System.currentTimeMillis() % 2000 < 50) Log.e(TAG, "Failed to move cursor: ${e.message}", e)
                    }
                }
            } else {
                try {
                    PointerOverlayService.updatePointerPosition(-1f, -1f)
                    PointerOverlayService.getInstance()?.hidePointer()
                } catch (_: Exception) {}
                if (System.currentTimeMillis() % 5000 < 100) {
                    LogcatManager.addLog("Cursor movement disabled (settings open or user typing)", "Tracking")
                }
            }

            if (isResumed && _fragmentCameraBinding != null) {
                activity?.runOnUiThread {
                    if (_fragmentCameraBinding != null) {
                        fragmentCameraBinding.overlay.setPointerPosition(adjustedX, adjustedY)
                        fragmentCameraBinding.overlay.setResults(
                            resultBundle.result,
                            resultBundle.inputImageHeight,
                            resultBundle.inputImageWidth,
                            RunningMode.LIVE_STREAM
                        )
                        fragmentCameraBinding.overlay.invalidate()
                    }
                }
            }

            if (System.currentTimeMillis() % 1000 < 50) {
                LogcatManager.addLog(
                    "Eye: (${adjustedX.toInt()}, ${adjustedY.toInt()}) | Area: ${String.format(Locale.US, "%.4f", trackingResult.eyeArea)} | Pos: (${String.format(Locale.US, "%.2f", trackingResult.eyePositionX)}, ${String.format(Locale.US, "%.2f", trackingResult.eyePositionY)})",
                    "Tracking"
                )
            }
        } else {
            if (CameraFragment.isCursorMovementEnabled()) {
                PointerOverlayService.updatePointerPosition(-1f, -1f)
            }
            if (isResumed && _fragmentCameraBinding != null) {
                activity?.runOnUiThread {
                    if (_fragmentCameraBinding != null) fragmentCameraBinding.overlay.setPointerPosition(-1f, -1f)
                }
            }
        }
    }

    override fun onEmpty() {
        if (CameraFragment.isCursorMovementEnabled()) {
            PointerOverlayService.updatePointerPosition(-1f, -1f)
        }
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