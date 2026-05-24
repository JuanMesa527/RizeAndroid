package com.rize.rizeandroid.pose

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.max
import kotlin.math.min

/**
 * Vista personalizada para dibujar los landmarks del pose landmarker sobre la imagen de la cámara. Se alimenta con los resultados del pose landmarker y se encarga
 * de dibujar los puntos y las lineas de conexion entre ellos, escalando las coordenadas normalizadas a las dimensiones del view. Ademas, tiene una sobrecarga para recibir
 * la lista plana de landmarks ya pasada por el filtro 1€, lo que permite dibujar un esqueleto fluido que coincide con los valores numericos que se muestran en la UI.
 */
class OverlayView(context: Context?, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private var results: PoseLandmarkerResult? = null
    private var smoothedFlat: List<Double>? = null
    private var pointPaint = Paint()
    private var linePaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    init {
        initPaints()
    }

    fun clear() {
        results = null
        smoothedFlat = null
        pointPaint.reset()
        linePaint.reset()
        invalidate()
        initPaints()
    }

    /**
     * Configura los objetos Paint para dibujar los puntos y las lineas del esqueleto. Se llama al iniciar el view y tambien al limpiar el overlay, para resetear los 
     * estilos de dibujo.
     */
    private fun initPaints() {
        linePaint.color = Color.parseColor("#4CAF50")
        linePaint.strokeWidth = 12F
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = Color.parseColor("#dc965a")
        pointPaint.strokeWidth = 25F
        pointPaint.style = Paint.Style.FILL
        pointPaint.strokeCap = Paint.Cap.ROUND
    }

    /**
     * Dibuja los landmarks en el canvas.
     */
    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Si tenemos landmarks suavizados (lista plana), los preferimos
        // para dibujar — el esqueleto se ve fluido y coincide con los
        // valores numericos de la UI (que tambien se calculan sobre esta
        // misma lista filtrada).
        smoothedFlat?.let { flat ->
            if (flat.size >= 132) {
                drawSkeletonFromFlat(canvas, flat)
                return
            }
        }

        results?.let { poseLandmarkerResult ->
            for (landmark in poseLandmarkerResult.landmarks()) {
                PoseLandmarker.POSE_LANDMARKS.forEach {
                    canvas.drawLine(
                        poseLandmarkerResult.landmarks()[0][it!!.start()].x() * imageWidth * scaleFactor,
                        poseLandmarkerResult.landmarks()[0][it.start()].y() * imageHeight * scaleFactor,
                        poseLandmarkerResult.landmarks()[0][it.end()].x() * imageWidth * scaleFactor,
                        poseLandmarkerResult.landmarks()[0][it.end()].y() * imageHeight * scaleFactor,
                        linePaint
                    )
                }

                for (normalizedLandmark in landmark) {
                    canvas.drawPoint(
                        normalizedLandmark.x() * imageWidth * scaleFactor,
                        normalizedLandmark.y() * imageHeight * scaleFactor,
                        pointPaint
                    )
                }
            }
        }
    }

    /**
     * Dibuja el esqueleto a partir de una lista plana de landmarks.
     */
    private fun drawSkeletonFromFlat(canvas: Canvas, flat: List<Double>) {
        PoseLandmarker.POSE_LANDMARKS.forEach { conn ->
            if (conn == null) return@forEach
            val s = conn.start() * 4
            val e = conn.end() * 4
            if (s + 1 >= flat.size || e + 1 >= flat.size) return@forEach
            canvas.drawLine(
                flat[s].toFloat() * imageWidth * scaleFactor,
                flat[s + 1].toFloat() * imageHeight * scaleFactor,
                flat[e].toFloat() * imageWidth * scaleFactor,
                flat[e + 1].toFloat() * imageHeight * scaleFactor,
                linePaint
            )
        }
        var i = 0
        while (i < flat.size) {
            canvas.drawPoint(
                flat[i].toFloat() * imageWidth * scaleFactor,
                flat[i + 1].toFloat() * imageHeight * scaleFactor,
                pointPaint
            )
            i += 4
        }
    }

    /**
     * Establece los resultados del pose landmarker y actualiza la vista.
     */
    fun setResults(
        poseLandmarkerResults: PoseLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.LIVE_STREAM
    ) {
        results = poseLandmarkerResults
        smoothedFlat = null
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = computeScaleFactor(runningMode)
        invalidate()
    }

    /**
     * Sobrecarga para alimentar el overlay con landmarks ya pasados por el filtro 1€. Recibe la lista plana de 132 valores (x, y, z, visibility
     * por cada uno de los 33 landmarks) y dibuja el esqueleto coincidiendo con los valores que muestra la UI.
     */
    fun setResults(
        smoothedFlatLandmarks: List<Double>,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.LIVE_STREAM
    ) {
        smoothedFlat = smoothedFlatLandmarks
        results = null
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = computeScaleFactor(runningMode)
        invalidate()
    }

    private fun computeScaleFactor(runningMode: RunningMode): Float = when (runningMode) {
        RunningMode.IMAGE, RunningMode.VIDEO ->
            min(width * 1f / imageWidth, height * 1f / imageHeight)
        RunningMode.LIVE_STREAM ->
            max(width * 1f / imageWidth, height * 1f / imageHeight)
    }
}
