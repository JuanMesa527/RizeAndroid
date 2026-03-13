package com.rize.rizeandroid

import android.content.Context
import android.util.Log
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.Executors

class CameraView(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) : PoseLandmarkerHelper.LandmarkerListener {

    private val frameLayout: FrameLayout = FrameLayout(context)
    private val previewView: PreviewView = PreviewView(context)
    private val overlayView: OverlayView = OverlayView(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var preview: Preview? = null

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    init {
        frameLayout.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        previewView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_START

        overlayView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        frameLayout.addView(previewView)
        frameLayout.addView(overlayView)

        setupMediaPipe()
    }

    fun getView(): FrameLayout = frameLayout

    fun startCamera() {
        previewView.post {
            setupCamera()
        }
    }

    private fun setupMediaPipe() {
        poseLandmarkerHelper = PoseLandmarkerHelper(
            context = context,
            poseLandmarkerHelperListener = this
        )
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        bindCameraUseCases()
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        preview?.setSurfaceProvider(null)
        cameraProvider.unbindAll()

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(previewView.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image ->
                    poseLandmarkerHelper.detectLiveStream(
                        imageProxy = image,
                        isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT
                    )
                }
            }

        try {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            preview?.setSurfaceProvider(previewView.surfaceProvider)
        } catch (exc: Exception) {
            Log.e("CameraView", "Use case binding failed", exc)
        }
    }

    fun release() {
        backgroundExecutor.shutdown()
        poseLandmarkerHelper.clearPoseLandmarker()
        cameraProvider?.unbindAll()
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e("CameraView", "MediaPipe Error: $error")
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        ContextCompat.getMainExecutor(context).execute {
            overlayView.setResults(
                resultBundle.results.first(),
                resultBundle.inputImageHeight,
                resultBundle.inputImageWidth,
                RunningMode.LIVE_STREAM
            )
        }

        val results = resultBundle.results
        if (results.isNotEmpty()) {
            val firstResult = results[0]
            val landmarks = firstResult.landmarks()

            if (landmarks.isNotEmpty()) {
                val personLandmarks = landmarks[0]
                val flatList = ArrayList<Double>()
                for (landmark in personLandmarks) {
                    flatList.add(landmark.x().toDouble())
                    flatList.add(landmark.y().toDouble())
                    flatList.add(landmark.z().toDouble())
                    flatList.add(landmark.visibility().orElse(0.0f).toDouble())
                }
                PoseDataManager.sendPoseData(flatList)
            }
        }
    }
}
