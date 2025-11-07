package com.qali.iris.fragment

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.qali.iris.*
import com.qali.iris.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.Executors

class CameraFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "CameraFragment"
        private var cursorMovementEnabledGlobal = true

        fun setCursorMovementEnabled(enabled: Boolean) {
            cursorMovementEnabledGlobal = enabled
        }

        fun isCursorMovementEnabled(): Boolean = cursorMovementEnabledGlobal
    }

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private lateinit var backgroundExecutor: java.util.concurrent.ExecutorService

    // Eye tracking
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

        val settingsFragment = childFragmentManager.findFragmentByTag("SettingsFragment")
            ?: parentFragmentManager.findFragmentByTag("SettingsFragment")

        if (settingsFragment == null || !settingsFragment.isVisible || !settingsFragment.isResumed) {
            setCursorMovementEnabled(true)
        } else {
            setCursorMovementEnabled(false)
            LogcatManager.addLog("Settings open - cursor disabled", "Camera")
        }

        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_camera_to_permissions)
            return
        }

        if (!hasCheckedAccessibilityOnResume) {
            checkAccessibilityPermission(showPrompt = false)
            hasCheckedAccessibilityOnResume = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(requireContext())) {
            try { startPointerService() } catch (e: Exception) {
                LogcatManager.addLog("Failed to start pointer: ${e.message}", "Camera")
            }
        }

        if (this::faceLandmarkerHelper.isInitialized && faceLandmarkerHelper.isClose()) {
            backgroundExecutor.execute {
                faceLandmarkerHelper.setupFaceLandmarker()
                LogcatManager.addLog("FaceLandmarkerHelper restarted", "Camera")
            }
        }

        if (_binding != null && binding.viewFinder != null) {
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

        if (this::faceLandmarkerHelper.isInitialized) {
            viewModel.setMaxFaces(faceLandmarkerHelper.maxNumFaces)
            viewModel.setMinFaceDetectionConfidence(faceLandmarkerHelper.minFaceDetectionConfidence)
            viewModel.setMinFaceTrackingConfidence(faceLandmarkerHelper.minFaceTrackingConfidence)
            viewModel.setMinFacePresenceConfidence(faceLandmarkerHelper.minFacePresenceConfidence)
            viewModel.setDelegate(faceLandmarkerHelper.currentDelegate)
        }

        if (camera != null || cameraProvider != null) {
            LogcatManager.addLog("Releasing camera - service takes over", "Camera")
            cameraProvider?.unbindAll()
            camera = null
            imageAnalyzer = null
            preview = null
            CameraForegroundService.getInstance()?.rebindCameraIfNeeded()
        }

        if (CameraForegroundService.getInstance() == null) {
            try { CameraForegroundService.start(requireContext()) } catch (e: Exception) {
                LogcatManager.addLog("Failed to restart service: ${e.message}", "Camera")
            }
        }
    }

    override fun onDestroyView() {
        cameraProvider?.unbindAll()
        camera = null
        imageAnalyzer = null
        preview = null
        CameraForegroundService.getInstance()?.rebindCameraIfNeeded()

        _binding = null
        super.onDestroyView()

        backgroundExecutor.shutdown()
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
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val displayMetrics = resources.displayMetrics
        eyeTracker = EyeTracker(displayMetrics)
        settingsManager = SettingsManager(requireContext())
        trackingCalculator = TrackingCalculator(settingsManager, displayMetrics)

        MouseControlService.getInstance()?.setSettingsManager(settingsManager)
        mouseServiceOnConnected = { service ->
            service.setSettingsManager(settingsManager)
            MouseControlService.getPendingCursorPosition()?.let { p ->
                PointerOverlayService.updatePointerPosition(p.x, p.y)
            }
        }
        mouseServiceOnConnected?.let { MouseControlService.registerOnServiceConnected(it) }

        eyeBlinkDetector = EyeBlinkDetector(
            initialBlinkThreshold = settingsManager.blinkThreshold,
            initialHalfBlinkAccelThreshold = settingsManager.halfBlinkAccelThreshold,
            initialClickDelayThreshold = settingsManager.clickDelayThreshold
        )

        // === CONNECT EYE BLINK DETECTOR CALLBACKS ===
        eyeBlinkDetector.onTap = { pos ->
            MouseControlService.performClick()
            PointerOverlayService.indicateClick()
            activity?.runOnUiThread {
                binding.overlay.setClickPosition(pos.x, pos.y)
                binding.overlay.indicateClick()
            }
        }

        eyeBlinkDetector.onDragStart = { pos ->
            MouseControlService.startDrag()
            PointerOverlayService.indicateDragStart()
        }

        eyeBlinkDetector.onDragEnd = {
            MouseControlService.endDrag()
            PointerOverlayService.indicateDragEnd()
        }

        binding.overlay.setEyeTracker(eyeTracker)
        binding.overlay.setCursorColor(settingsManager.cursorColor)
        binding.overlay.setClickColor(settingsManager.clickColor)

        // Settings button
        binding.settingsButton.setOnClickListener {
            if (isSettingsOpening || !isAdded || !isResumed) return@setOnClickListener

            Log.e(TAG, "=== SETTINGS CLICKED ===")
            try {
                CameraFragment.setCursorMovementEnabled(false)
                val fm = requireActivity().supportFragmentManager
                val existing = fm.findFragmentByTag("SettingsFragment")
                if (existing != null && existing.isVisible) {
                    fm.popBackStack("SettingsFragment", androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    CameraFragment.setCursorMovementEnabled(true)
                    return@setOnClickListener
                }

                isSettingsOpening = true
                val settingsFragment = com.qali.iris.fragment.SettingsFragment()
                fm.beginTransaction()
                    .add(R.id.fragment_container, settingsFragment, "SettingsFragment")
                    .addToBackStack("SettingsFragment")
                    .commitAllowingStateLoss()

                binding.settingsButton.postDelayed({ isSettingsOpening = false }, 500)
            } catch (e: Exception) {
                isSettingsOpening = false
                CameraFragment.setCursorMovementEnabled(true)
                LogcatManager.addLog("Settings error: ${e.message}", "Camera")
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        checkAccessibilityPermission(showPrompt = true)
        requestOverlayPermission()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        try {
            CameraForegroundService.start(requireContext())
        } catch (e: Exception) {
            LogcatManager.addLog("Service start failed: ${e.message}", "Camera")
        }

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

        binding.viewFinder.post {
            if (isAdded && !isDetached) setUpCamera()
        }
    }

    /* --------------------------------------------------------------------- */
    /* --------------------------- PERMISSIONS ----------------------------- */
    /* --------------------------------------------------------------------- */

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(requireContext())) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
            startActivity(intent)
        } else {
            startPointerService()
        }
    }

    private fun startPointerService() {
        val intent = Intent(requireContext(), PointerOverlayService::class.java)
        requireContext().startService(intent)
    }

    private fun checkAccessibilityPermission(showPrompt: Boolean = true) {
        if (MouseControlService.getInstance() != null) {
            isMouseControlEnabled = true
            return
        }

        val enabled = isAccessibilityServiceEnabled()
        if (!enabled && showPrompt && isResumed && isAdded) {
            Toast.makeText(requireContext(), "Enable accessibility for mouse control", Toast.LENGTH_LONG).show()

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val action = Settings::class.java.getField("ACTION_ACCESSIBILITY_DETAILS_SETTINGS").get(null) as String
                    val intent = Intent(action).apply {
                        data = Uri.parse("package:${requireContext().packageName}/${MouseControlService::class.java.name}")
                    }
                    if (intent.resolveActivity(requireContext().packageManager) != null) {
                        startActivity(intent)
                        return
                    }
                }
            } catch (_: Exception) { }

            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        isMouseControlEnabled = enabled
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = ContextCompat.getSystemService(requireContext(), AccessibilityManager::class.java) ?: return false
        if (!am.isEnabled) return false

        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val target = ComponentName(requireContext(), MouseControlService::class.java)

        return enabledServices.any { info ->
            val svc = info.resolveInfo.serviceInfo
            ComponentName(svc.packageName, svc.name) == target
        }
    }

    /* --------------------------------------------------------------------- */
    /* --------------------------- CAMERA SETUP ----------------------------- */
    /* --------------------------------------------------------------------- */

    private fun setUpCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            try {
                cameraProvider = future.get()
                if (_binding != null && binding.viewFinder != null) bindCameraUseCases()
            } catch (e: Exception) {
                LogcatManager.addLog("Camera setup error: ${e.message}", "Camera")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cp = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        val rotation = binding.viewFinder.display?.rotation ?: 0

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

        try {
            camera = cp.bindToLifecycle(this, selector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (e: Exception) {
            LogcatManager.addLog("Camera bind failed: ${e.message}", "Camera")
        }
    }

    /* --------------------------------------------------------------------- */
    /* --------------------------- FACE DETECTION -------------------------- */
    /* --------------------------------------------------------------------- */

    private fun detectFace(imageProxy: ImageProxy) {
        if (!this::faceLandmarkerHelper.isInitialized) {
            imageProxy.close()
            return
        }

        faceLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = binding.viewFinder.display?.rotation ?: 0
    }

    /* --------------------------------------------------------------------- */
    /* --------------------------- RESULT HANDLING -------------------------- */
    /* --------------------------------------------------------------------- */

    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        val landmarks = resultBundle.result.faceLandmarks().firstOrNull() ?: return

        eyeTracker.setUseOneEye(settingsManager.useOneEyeDetection)
        val trackingResult = eyeTracker.trackEyes(landmarks)
        val (adjustedX, adjustedY) = trackingCalculator.calculateAdjustedPosition(trackingResult)

        // === BLINK DETECTION ===
        if (trackingResult.leftEyelidLandmarks != null || trackingResult.rightEyelidLandmarks != null) {
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
                    else -> trackingResult.leftEyelidLandmarks ?: trackingResult.rightEyelidLandmarks
                }
            }

            combined?.let {
                eyeBlinkDetector.processEyelidLandmarks(
                    upperLidY = it.upperLidY,
                    lowerLidY = it.lowerLidY,
                    clickPosition = PointF(adjustedX, adjustedY)
                )
            }
        } else {
            eyeBlinkDetector.processEyeArea(trackingResult.eyeArea)
        }

        // === POINTER UPDATE ===
        if (CameraFragment.isCursorMovementEnabled()) {
            PointerOverlayService.updatePointerPosition(adjustedX, adjustedY)
            if (isMouseControlEnabled) {
                MouseControlService.moveCursor(adjustedX, adjustedY)
            }
        } else {
            PointerOverlayService.updatePointerPosition(-1f, -1f)
        }

        // === UI UPDATE ===
        if (isResumed && _binding != null) {
            activity?.runOnUiThread {
                binding.overlay.setPointerPosition(adjustedX, adjustedY)
                binding.overlay.setResults(
                    resultBundle.result,
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )
                binding.overlay.invalidate()
            }
        }
    }

    override fun onEmpty() {
        if (CameraFragment.isCursorMovementEnabled()) {
            PointerOverlayService.updatePointerPosition(-1f, -1f)
        }
        if (isResumed && _binding != null) {
            activity?.runOnUiThread {
                binding.overlay.setPointerPosition(-1f, -1f)
                binding.overlay.clear()
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }
}