package com.rize.rizeandroid.biomechanics.calibration

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.tan

/**
 * Umbrales calibrados para el analisis de el press de banca, derivados del perfil
 * capturado en el setup. Se usan para adaptar la deteccion de eventos y errores
 * a la perspectiva y morfologia del usuario.
 */
data class BenchPressCalibratedThresholds(
    val gripPerspectiveCorrection: Double,
    val stickingVelocityThresholdDegS: Double,
    val stickingDurationMs: Long,
    val minValidBottomAngleDeg: Double,
    val maxValidBottomAngleDeg: Double,
    val fullExtensionMinDeg: Double,
    val topPositionAngleDeg: Double,
    val startDescentAngleDeg: Double,
    val abductionMinOkDeg: Double,
    val abductionMaxOkDeg: Double,
    val abductionDeepElbowDeg: Double,
    val abductionDeepMaxOkDeg: Double,
    val abductionCriticalDeg: Double,
    val abductionEvaluationElbowDeg: Double
) {

    fun toDebugMap(): Map<String, Double> = linkedMapOf(
        "gripPerspectiveCorrection" to gripPerspectiveCorrection,
        "stickingVelocityThresholdDegS" to stickingVelocityThresholdDegS,
        "stickingDurationMs" to stickingDurationMs.toDouble(),
        "minValidBottomAngleDeg" to minValidBottomAngleDeg,
        "maxValidBottomAngleDeg" to maxValidBottomAngleDeg,
        "fullExtensionMinDeg" to fullExtensionMinDeg,
        "topPositionAngleDeg" to topPositionAngleDeg,
        "startDescentAngleDeg" to startDescentAngleDeg,
        "abductionMinOkDeg" to abductionMinOkDeg,
        "abductionMaxOkDeg" to abductionMaxOkDeg,
        "abductionDeepElbowDeg" to abductionDeepElbowDeg,
        "abductionDeepMaxOkDeg" to abductionDeepMaxOkDeg,
        "abductionCriticalDeg" to abductionCriticalDeg,
        "abductionEvaluationElbowDeg" to abductionEvaluationElbowDeg
    )

    companion object {
        // Valores canonicos = los hardcoded historicos. Sirven como referencia
        // "ideal" (banco plano, vista frontal a altura de cabeza). Se preservan
        // exactamente para garantizar cero cambio de comportamiento cuando
        // theta ~ 0 (setup de pruebas en casa).
        private const val CANONICAL_GRIP_PERSPECTIVE = 0.6
        private const val CANONICAL_STICKING_VEL = 10.0
        private const val CANONICAL_STICKING_DURATION_MS = 870L
        private const val CANONICAL_MIN_BOTTOM = 60.0
        private const val CANONICAL_MAX_BOTTOM = 85.0
        private const val CANONICAL_TOP = 160.0
        private const val CANONICAL_START_DESCENT = 155.0
        private const val CANONICAL_ABD_MIN_OK = 45.0
        private const val CANONICAL_ABD_MAX_OK = 85.0
        private const val CANONICAL_ABD_DEEP_ELBOW = 80.0
        private const val CANONICAL_ABD_DEEP_MAX_OK = 55.0
        private const val CANONICAL_ABD_CRITICAL = 90.0
        private const val CANONICAL_ABD_EVAL_ELBOW = 90.0

        private const val STICKING_VEL_FLOOR_DEG_S = 6.0
        private const val GRIP_CORRECTION_MIN = 0.45
        private const val GRIP_CORRECTION_MAX = 0.95

        // Sanity gating: si el perfil capturado cae fuera de estos rangos, el
        // calibrador lo rechaza y permanece en canonical().
        const val SANITY_MAX_TORSO_TILT_DEG = 60.0
        // Landmarks de MediaPipe normalizados a [0,1]. Un sujeto bien encuadrado
        // tiene ancho biacromial >= 0.04 (~10% del ancho de imagen). Por debajo
        // de eso, esta demasiado lejos para una calibracion fiable.
        const val SANITY_MIN_SHOULDER_WIDTH = 0.04
        // En banco inclinado los brazos apuntan hacia la camara y el torso se
        // proyecta mas corto: el ratio en coords de imagen puede variar mucho.
        // Usamos bounds amplios para no rechazar poses reales.
        const val SANITY_MIN_ARM_TORSO_RATIO = 0.3
        const val SANITY_MAX_ARM_TORSO_RATIO = 3.0
        const val SANITY_MIN_GRIP_RATIO_2D = 0.8
        const val SANITY_MAX_GRIP_RATIO_2D = 4.0

        /**
         * Umbrales canonicos (banco plano frontal). Equivalentes a los valores
         * hardcoded historicos del algoritmo.
         */
        fun canonical(): BenchPressCalibratedThresholds = BenchPressCalibratedThresholds(
            gripPerspectiveCorrection = CANONICAL_GRIP_PERSPECTIVE,
            stickingVelocityThresholdDegS = CANONICAL_STICKING_VEL,
            stickingDurationMs = CANONICAL_STICKING_DURATION_MS,
            minValidBottomAngleDeg = CANONICAL_MIN_BOTTOM,
            maxValidBottomAngleDeg = CANONICAL_MAX_BOTTOM,
            fullExtensionMinDeg = CANONICAL_TOP,
            topPositionAngleDeg = CANONICAL_TOP,
            startDescentAngleDeg = CANONICAL_START_DESCENT,
            abductionMinOkDeg = CANONICAL_ABD_MIN_OK,
            abductionMaxOkDeg = CANONICAL_ABD_MAX_OK,
            abductionDeepElbowDeg = CANONICAL_ABD_DEEP_ELBOW,
            abductionDeepMaxOkDeg = CANONICAL_ABD_DEEP_MAX_OK,
            abductionCriticalDeg = CANONICAL_ABD_CRITICAL,
            abductionEvaluationElbowDeg = CANONICAL_ABD_EVAL_ELBOW
        )

         /**
         * Deriva umbrales calibrados a partir del perfil capturado.
         * 
         * @param profile Perfil capturado en el setup. Se asume que ya paso el sanity check.
         * @return Umbrales calibrados adaptados a la perspectiva y morfologia del usuario.
         */
        fun from(profile: BenchPressEnvironmentProfile): BenchPressCalibratedThresholds {
            val theta = kotlin.math.abs(profile.torsoTiltDegFromVertical)
            val cosT = cos(Math.toRadians(theta))

            val gripCorrection = (1.5 / profile.grip2DRatioAtRest)
                .coerceIn(GRIP_CORRECTION_MIN, GRIP_CORRECTION_MAX)

            val sticking = (CANONICAL_STICKING_VEL * cosT).coerceAtLeast(STICKING_VEL_FLOOR_DEG_S)

            val abdMinOk = CANONICAL_ABD_MIN_OK * cosT
            var abdMaxOk = CANONICAL_ABD_MAX_OK * (1.0 - 0.10 * (1 - cosT))            
            if (abdMaxOk < abdMinOk + 25.0) abdMaxOk = abdMinOk + 25.0
            val abdDeepMaxOk = CANONICAL_ABD_DEEP_MAX_OK * cosT
            val abdCritical = CANONICAL_ABD_CRITICAL * (1.0 - 0.10 * (1 - cosT))

            return BenchPressCalibratedThresholds(
                gripPerspectiveCorrection = gripCorrection,
                stickingVelocityThresholdDegS = sticking,
                stickingDurationMs = CANONICAL_STICKING_DURATION_MS,
                minValidBottomAngleDeg = project(CANONICAL_MIN_BOTTOM, theta),
                maxValidBottomAngleDeg = project(CANONICAL_MAX_BOTTOM, theta),
                fullExtensionMinDeg = project(CANONICAL_TOP, theta),
                topPositionAngleDeg = project(CANONICAL_TOP, theta),
                startDescentAngleDeg = project(CANONICAL_START_DESCENT, theta),
                abductionMinOkDeg = abdMinOk,
                abductionMaxOkDeg = abdMaxOk,
                abductionDeepElbowDeg = project(CANONICAL_ABD_DEEP_ELBOW, theta),
                abductionDeepMaxOkDeg = abdDeepMaxOk,
                abductionCriticalDeg = abdCritical,
                abductionEvaluationElbowDeg = project(CANONICAL_ABD_EVAL_ELBOW, theta)
            )
        }

       /**  
        * Proyecta un angulo canonico medido en el setup a su equivalente "real" en el espacio 3D, considerando la inclinacion del torso.
         * Para perfiles con torso muy inclinado (theta ~ 60), los angulos de flexion se ven reducidos hasta un ~20% respecto al canonico.
        */
        fun project(betaDeg: Double, thetaDeg: Double): Double {
            if (betaDeg >= 179.5) return betaDeg
            val cosT = cos(Math.toRadians(thetaDeg))
            val halfBetaRad = Math.toRadians(betaDeg / 2.0)
            return 2.0 * Math.toDegrees(atan(tan(halfBetaRad) * cosT))
        }

        /**
         * Sanity check del perfil capturado. Si devuelve false, el calibrador
         * descarta el perfil y mantiene los umbrales canonicos.
         */
        fun isProfileSane(profile: BenchPressEnvironmentProfile): Boolean {
            val theta = kotlin.math.abs(profile.torsoTiltDegFromVertical)
            if (theta > SANITY_MAX_TORSO_TILT_DEG) return false
            if (profile.shoulderWidthNormalized < SANITY_MIN_SHOULDER_WIDTH) return false
            if (profile.upperArmToTorsoLengthRatio < SANITY_MIN_ARM_TORSO_RATIO ||
                profile.upperArmToTorsoLengthRatio > SANITY_MAX_ARM_TORSO_RATIO
            ) return false
            if (profile.grip2DRatioAtRest < SANITY_MIN_GRIP_RATIO_2D ||
                profile.grip2DRatioAtRest > SANITY_MAX_GRIP_RATIO_2D
            ) return false
            return true
        }
    }
}
