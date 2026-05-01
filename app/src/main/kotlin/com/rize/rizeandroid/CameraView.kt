package com.rize.rizeandroid

import android.content.Context
import android.util.Log
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.rize.rizeandroid.signal.LandmarkSmoother
import java.util.concurrent.Executors
import android.util.Size
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

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

    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    /**
     * Suavizador 1€ paralelo dedicado al overlay. Aplica el mismo tipo de
     * filtrado que internamente hace [Algorithms] sobre los landmarks que
     * llegan al algoritmo biomecanico — pero en una instancia independiente
     * para no acoplar el pipeline visual con el de logica. El esqueleto
     * pintado coincide visualmente con los valores que la UI muestra y
     * elimina el jitter inter-frame de MediaPipe (~1-3 px equivalentes).
     *
     * Parametros default (minCutoff=1.0, beta=0.01) son adecuados para los
     * tres ejercicios; el algoritmo de bench tiene su propio smoother
     * interno mas agresivo, esto es solo para el render.
     */
    private val overlaySmoother = LandmarkSmoother()

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

        val previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

        preview = Preview.Builder()
            .setResolutionSelector(previewResolutionSelector)
            .setTargetRotation(previewView.display.rotation)
            .build()

        val analysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(720, 960),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolutionSelector)
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
        val results = resultBundle.results
        if (results.isEmpty()) return

        val firstResult = results[0]
        val landmarks = firstResult.landmarks()

        if (landmarks.isEmpty()) {
            // Sin pose: limpiamos el overlay para no dejar el ultimo
            // esqueleto pintado de forma engañosa.
            ContextCompat.getMainExecutor(context).execute { overlayView.clear() }
            return
        }

        val personLandmarks = landmarks[0]
        val flatList = ArrayList<Double>(personLandmarks.size * 4)
        for (landmark in personLandmarks) {
            flatList.add(landmark.x().toDouble())
            flatList.add(landmark.y().toDouble())
            flatList.add(landmark.z().toDouble())
            flatList.add(landmark.visibility().orElse(0.0f).toDouble())
        }

        // Pasamos la lista cruda al pipeline de logica — Algorithms aplica
        // su propio suavizador (con parametros por ejercicio) antes de
        // ejecutar el algoritmo biomecanico.
        PoseDataManager.sendPoseData(flatList)

        // Para el overlay: aplicamos un suavizado 1€ independiente para que
        // el esqueleto pintado se vea fluido, sin jitter, y coincida
        // visualmente con los valores que la UI muestra.
        val smoothedForOverlay = overlaySmoother.filter(flatList, System.currentTimeMillis())

        ContextCompat.getMainExecutor(context).execute {
            overlayView.setResults(
                smoothedForOverlay,
                resultBundle.inputImageHeight,
                resultBundle.inputImageWidth,
                RunningMode.LIVE_STREAM
            )
        }
    }
}
