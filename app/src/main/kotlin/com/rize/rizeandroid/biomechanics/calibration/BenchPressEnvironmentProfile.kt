package com.rize.rizeandroid.biomechanics.calibration

import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Snapshot de senales crudas del entorno capturadas al alcanzar READY al
 * principio de una sesion de press de banca. No contiene umbrales: solo las
 * observaciones que [BenchPressCalibratedThresholds.from] consume para
 * derivar los umbrales calibrados.
 *
 * Todas las senales se miden sobre los landmarks ya filtrados por
 * LandmarkSmoother (bench profile: minCutoff=1.2 Hz, beta=0.015).
 */
data class BenchPressEnvironmentProfile(
    val torsoTiltDegFromVertical: Double,
    // Ancho biacromial normalizado [0,1] (MediaPipe entrega landmarks normalizados
    // al tamano de la imagen, no pixeles). Proxy de distancia de camara: mas
    // pequeno = sujeto mas lejos.
    val shoulderWidthNormalized: Double,
    val grip2DRatioAtRest: Double,
    val upperArmToTorsoLengthRatio: Double,
    val cameraYawHintDeg: Double
) {
    companion object {
        // MediaPipe landmark indices (espejo de los del algoritmo, intencional:
        // mantiene este paquete de calibracion auto-contenido).
        private const val IDX_SHOULDER_L = 11
        private const val IDX_SHOULDER_R = 12
        private const val IDX_ELBOW_L = 13
        private const val IDX_ELBOW_R = 14
        private const val IDX_WRIST_L = 15
        private const val IDX_WRIST_R = 16
        private const val IDX_HIP_L = 23
        private const val IDX_HIP_R = 24
        private const val LANDMARK_STRIDE = 4
        private const val MIN_VISIBILITY = 0.5f
        private const val TAG = "BenchCalib"

        /**
         * Construye un perfil a partir del array flat de landmarks de MediaPipe.
         * Devuelve null solo si los landmarks de brazo (hombro/codo/muneca) no son
         * suficientemente visibles. Las caderas son OPCIONALES: si no son visibles
         * se usa torsoTilt = 0 (calibracion conservadora = canonical para angulos,
         * pero aun corrige grip perspective).
         */
        fun fromLandmarks(flat: List<Double>): BenchPressEnvironmentProfile? {
            if (flat.size < (IDX_HIP_R + 1) * LANDMARK_STRIDE) return null

            val shoulderL = getLandmark(flat, IDX_SHOULDER_L)
            val shoulderR = getLandmark(flat, IDX_SHOULDER_R)
            val elbowL = getLandmark(flat, IDX_ELBOW_L)
            val elbowR = getLandmark(flat, IDX_ELBOW_R)
            val wristL = getLandmark(flat, IDX_WRIST_L)
            val wristR = getLandmark(flat, IDX_WRIST_R)

            // Brazos son obligatorios: sin ellos no hay perfil util.
            if (shoulderL == null || shoulderR == null ||
                elbowL == null   || elbowR == null   ||
                wristL == null   || wristR == null) {
                Log.d(TAG, "fromLandmarks: arm landmarks not visible enough, skipping frame")
                return null
            }

            val shoulderWidth = abs(shoulderL.x - shoulderR.x)
            if (shoulderWidth < 1e-6) return null

            val wristWidth = abs(wristL.x - wristR.x)
            val grip2DRatio = wristWidth / shoulderWidth

            val sdy = shoulderL.y - shoulderR.y
            val sdx = shoulderL.x - shoulderR.x
            val yawHint = Math.toDegrees(atan2(abs(sdy), abs(sdx)))

            // Caderas opcionales: dan torso tilt y proporcion brazo/torso.
            // Si no son visibles, usamos tilt = 0 (conservador).
            val hipL = getLandmarkRelaxed(flat, IDX_HIP_L)
            val hipR = getLandmarkRelaxed(flat, IDX_HIP_R)

            val torsoTilt: Double
            val armToTorso: Double
            if (hipL != null && hipR != null) {
                val shoulderMidX = (shoulderL.x + shoulderR.x) / 2.0
                val shoulderMidY = (shoulderL.y + shoulderR.y) / 2.0
                val hipMidX = (hipL.x + hipR.x) / 2.0
                val hipMidY = (hipL.y + hipR.y) / 2.0
                val dx = hipMidX - shoulderMidX
                val dy = hipMidY - shoulderMidY
                torsoTilt = Math.toDegrees(atan2(abs(dx), abs(dy)))

                val upperArmAvg = (distance2D(shoulderL, elbowL) + distance2D(shoulderR, elbowR)) / 2.0
                val torsoLenAvg = (distance2D(shoulderL, hipL) + distance2D(shoulderR, hipR)) / 2.0
                armToTorso = if (torsoLenAvg > 1e-6) upperArmAvg / torsoLenAvg else 1.0
                Log.d(TAG, "fromLandmarks: hips visible, torsoTilt=%.1f armRatio=%.2f".format(torsoTilt, armToTorso))
            } else {
                // Sin caderas visibles: no podemos medir inclinacion del banco directamente.
                // Usamos tilt = 0 -> los umbrales de angulo quedan en canonical (conservador).
                // El grip correction SI se aplica porque depende solo de munecas y hombros.
                torsoTilt = 0.0
                armToTorso = 1.0
                Log.d(TAG, "fromLandmarks: hips not visible (vis<0.3), using torsoTilt=0 fallback")
            }

            return BenchPressEnvironmentProfile(
                torsoTiltDegFromVertical = torsoTilt,
                shoulderWidthNormalized = shoulderWidth,
                grip2DRatioAtRest = grip2DRatio,
                upperArmToTorsoLengthRatio = armToTorso,
                cameraYawHintDeg = yawHint
            )
        }

        /**
         * Mediana por componente de una lista de perfiles. Robusta a frames
         * atipicos: una sola muestra mala no contamina el perfil final.
         */
        fun medianOf(samples: List<BenchPressEnvironmentProfile>): BenchPressEnvironmentProfile {
            require(samples.isNotEmpty()) { "samples must not be empty" }
            return BenchPressEnvironmentProfile(
                torsoTiltDegFromVertical = median(samples.map { it.torsoTiltDegFromVertical }),
                shoulderWidthNormalized = median(samples.map { it.shoulderWidthNormalized }),
                grip2DRatioAtRest = median(samples.map { it.grip2DRatioAtRest }),
                upperArmToTorsoLengthRatio = median(samples.map { it.upperArmToTorsoLengthRatio }),
                cameraYawHintDeg = median(samples.map { it.cameraYawHintDeg })
            )
        }

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }

        private fun getLandmark(flat: List<Double>, index: Int): LandmarkXY? {
            val base = index * LANDMARK_STRIDE
            if (base + 3 >= flat.size) return null
            val visibility = flat[base + 3].toFloat()
            if (visibility < MIN_VISIBILITY) return null
            return LandmarkXY(flat[base], flat[base + 1])
        }

        // Umbral relajado para caderas: en banco inclinado con camara a altura
        // de cabeza, los landmarks de cadera suelen tener visibilidad ~0.25-0.45.
        // Con 0.3 los capturamos si MediaPipe los detecta aunque sea debilmente.
        private fun getLandmarkRelaxed(flat: List<Double>, index: Int): LandmarkXY? {
            val base = index * LANDMARK_STRIDE
            if (base + 3 >= flat.size) return null
            val visibility = flat[base + 3].toFloat()
            if (visibility < 0.3f) return null
            return LandmarkXY(flat[base], flat[base + 1])
        }

        private fun distance2D(a: LandmarkXY, b: LandmarkXY): Double {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return sqrt(dx * dx + dy * dy)
        }

        private data class LandmarkXY(val x: Double, val y: Double)
    }
}
