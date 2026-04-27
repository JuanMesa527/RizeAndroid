package com.rize.rizeandroid

import com.rize.rizeandroid.signal.LandmarkSmoother

class Algorithms {

    private val curlBiomechanics = CurlBiomechanicsAlgorithm()
    private val squatBiomechanics = SquatBiomechanicsAlgorithm()
    private val benchPressBiomechanics = BenchPressBiomechanicsAlgorithm()

    private var activeAlgorithm: BiomechanicsAlgorithm = curlBiomechanics

    /**
     * Suavizador temporal (1€) aplicado a los landmarks antes de que lleguen
     * al algoritmo biomecanico. Esto reduce el jitter inter-frame de MediaPipe
     * (~1-3 px) que antes se amplificaba al derivar angulos — especialmente
     * nocivo en press banca por el angulo frontal de camara.
     *
     * Se resetea en cada selectAlgorithm para evitar arrastrar historia entre
     * ejercicios o tras un switch de camara.
     */
    private val landmarkSmoother = LandmarkSmoother()

    /**
     * Suavizador AGRESIVAMENTE OPTIMIZADO para SENTADILLA.
     * MÁXIMA responsividad para seguir movimientos rápidos:
     *   minCutoff = 2.0 Hz   — mucho más agresivo, responde al movimiento rápido
     *   beta      = 0.08     — ALTO, se adapta bien a aceleraciones
     *
     * Esto es necesario para reps rápidas. Los landmarks siguen casi sin delay
     * la velocidad real del movimiento.
     */
    private val landmarkSmootherForSquat = LandmarkSmoother(minCutoff = 2.0, beta = 0.08)

    /**
     * Suavizador para PRESS DE BANCA. Vista frontal-cabeza con persona en
     * decubito supino: el eje del press coincide con el eje optico de la
     * camara, asi que Z es la coordenada con mayor varianza de BlazePose.
     * Necesitamos un cutoff base mas alto que el default para matar el
     * jitter sin sacrificar la respuesta a la velocidad real del movimiento.
     *   minCutoff = 1.2 Hz   — mas agresivo que default (1.0) para vista frontal
     *   beta      = 0.015    — ligero margen sobre default para reps rapidas
     */
    private val landmarkSmootherForBench = LandmarkSmoother(minCutoff = 1.2, beta = 0.015)

    var currentResult: AlgorithmResult? = null
        private set

    // @JvmName renombra el getter/setter en el bytecode a "getResultCallback"
    // y "setResultCallback", eliminando cualquier colisión con nombres generados.
    // Desde Java: algorithms.setResultCallback(result -> { ... });
    @get:JvmName("getResultCallback")
    @set:JvmName("setResultCallback")
    var onResult: ((AlgorithmResult) -> Unit)? = null

    /** Versión tipada — preferida, inmune al idioma. */
    fun selectAlgorithm(type: ExerciseType) {
        activeAlgorithm = when (type) {
            ExerciseType.SQUAT       -> squatBiomechanics
            ExerciseType.BENCH_PRESS -> benchPressBiomechanics
            ExerciseType.CURL        -> curlBiomechanics
            ExerciseType.UNKNOWN     -> curlBiomechanics
        }
        activeAlgorithm.reset()
        landmarkSmoother.reset()
        landmarkSmootherForSquat.reset()
        landmarkSmootherForBench.reset()
    }

    /** Versión legacy por string — mantenida para VideoAnalysisActivity. */
    fun selectAlgorithm(exerciseName: String) {
        activeAlgorithm = when {
            exerciseName.contains("curl", ignoreCase = true) ||
            exerciseName.contains("mancuerna", ignoreCase = true) -> curlBiomechanics
            exerciseName.contains("squat", ignoreCase = true) ||
            exerciseName.contains("sentadilla", ignoreCase = true) -> squatBiomechanics
            exerciseName.contains("bench", ignoreCase = true) ||
            exerciseName.contains("banca", ignoreCase = true) -> benchPressBiomechanics
            else -> curlBiomechanics
        }
        activeAlgorithm.reset()
        landmarkSmoother.reset()
        landmarkSmootherForSquat.reset()
        landmarkSmootherForBench.reset()
    }

    private fun getCurrentSmoother(): LandmarkSmoother {
        return when {
            activeAlgorithm === squatBiomechanics -> landmarkSmootherForSquat
            activeAlgorithm === benchPressBiomechanics -> landmarkSmootherForBench
            else -> landmarkSmoother
        }
    }

    fun onPoseData(landmarkFlatList: List<Double>) {
        val smoother = getCurrentSmoother()
        val smoothed = smoother.filter(landmarkFlatList, System.currentTimeMillis())
        val result = activeAlgorithm.process(smoothed)
        currentResult = result
        onResult?.invoke(result)
    }

    /**
     * Variante explicita con timestamp del frame. La preferida desde el
     * pipeline de video (VideoAnalysisActivity) o cuando el productor ya
     * maneja la linea temporal de captura.
     */
    fun onPoseData(landmarkFlatList: List<Double>, timestampMs: Long) {
        val smoother = getCurrentSmoother()
        val smoothed = smoother.filter(landmarkFlatList, timestampMs)
        val result = activeAlgorithm.process(smoothed)
        currentResult = result
        onResult?.invoke(result)
    }

    fun reset() {
        activeAlgorithm.reset()
        landmarkSmoother.reset()
        landmarkSmootherForSquat.reset()
        landmarkSmootherForBench.reset()
        currentResult = null
    }
}

interface BiomechanicsAlgorithm {
    fun process(landmarkFlatList: List<Double>): AlgorithmResult
    fun reset()
}

data class AlgorithmResult(
    val angleDeg: Double?            = null,
    val angularVelocity: Double?     = null,
    val angularAcceleration: Double? = null,
    val fatigueDetected: Boolean     = false,
    val fatigueReason: String?       = null,
    val technicalError: ErrorLevel   = ErrorLevel.NONE,
    val errorMagnitude: Double?      = null,
    val alert: Boolean               = false,
    val algorithmName: String        = "",
    val timestampMs: Long            = System.currentTimeMillis(),
    val kneeAngleDeg: Double?        = null,
    val hipAngleDeg: Double?         = null,
    val kneeAngularVelocityDegS: Double? = null,
    val concentricPeakVelocityDegS: Double? = null,
    val eccentricPeakVelocityDegS: Double? = null,
    val velocityLossPercent: Double? = null,
    val cvtPercent: Double?          = null,
    val repCount: Int                = 0,
    val depthInsufficient: Boolean   = false,
    val trunkLeanRisk: Boolean       = false,
    // Bench press specific
    val elbowAngleDeg: Double?            = null,
    val leftElbowAngleDeg: Double?        = null,
    val rightElbowAngleDeg: Double?       = null,
    val shoulderAbductionDeg: Double?     = null,
    val gripWidthRatio: Double?           = null,
    val bilateralAsymmetryDeg: Double?    = null,
    val extensionIncompleteDeg: Double?   = null,
    val stickingPeriodDetected: Boolean   = false,
    val gripTooWide: Boolean              = false,
    val shoulderAbductionRisk: Boolean    = false,
    val bilateralAsymmetry: Boolean       = false,
    val depthInsufficientBench: Boolean   = false,
    val extensionIncomplete: Boolean      = false,
    // Live (rep en curso) — util para mostrar feedback en tiempo real de
    // las reglas 4 (profundidad) y 5 (extension) sin esperar a completeRep.
    val currentRepMinElbowAngleDeg: Double? = null,
    val currentRepMaxElbowAngleDeg: Double? = null,
    val elbowBelowTorsoLive: Boolean?       = null,
    // Readiness expuesto para que la UI muestre un indicador independiente
    // del torrente de alertas.
    val readinessReady: Boolean             = false,

    // ── Curl-specific live telemetry ─────────────────────────────────────────
    // Pico de flexion de la rep en curso (= angulo MINIMO observado durante la
    // fase concentrica activa). Serbest 2022, Fig 5: ~30-50 deg en MediaPipe.
    val currentRepPeakFlexionDeg: Double?   = null,
    // Valle de extension (= angulo MAXIMO observado en la fase excentrica).
    // Morrey 1981 / Soldado 2019: ~170-180 deg al final de la extension.
    val currentRepValleyExtensionDeg: Double? = null,
    // ROM = valle - pico de la rep (en curso o ultima cerrada). Pinto 2012 /
    // Goto 2019: full ROM curl >= 110 deg.
    val currentRepRomDeg: Double?           = null,
    // Pico/ROM de la ULTIMA rep cerrada — se mantiene visible entre reps para
    // que el panel no parpadee a "--" en cada extension.
    val lastRepPeakFlexionDeg: Double?      = null,
    val lastRepRomDeg: Double?              = null,
    // Compensacion del hombro en GRADOS respecto al reposo establecido en los
    // primeros 0.5 s (Liu et al. 2024 arXiv:2402.11421). Util como senal
    // continua de tecnica, NO solo como flag de error severo.
    val shoulderCompensationDeg: Double?    = null,

    // ── Snapshot de la ultima rep cerrada (para persistencia per-rep) ─────────
    // Cada algoritmo rellena los campos pertinentes en el process() inmediato
    // posterior al cierre de la rep. CameraActivity detecta el incremento de
    // repCount y captura estos campos.
    val lastRepConcentricPeakVelocityDegS: Double? = null,
    val lastRepFormQuality: ErrorLevel?            = null,
    // Squat
    val lastRepMinKneeAngleDeg: Double?            = null,
    val lastRepMinHipAngleDeg: Double?             = null,
    val lastRepDepthInsufficient: Boolean          = false,
    val lastRepTrunkLeanRisk: Boolean              = false,
    val lastRepSquatRomDeg: Double?                = null,
    val lastRepEccentricPeakVelocityDegS: Double?  = null,
    // Curl extra
    val lastRepShoulderCompensationDeg: Double?    = null,
    // Bench
    val lastRepMinElbowAngleDeg: Double?           = null,
    val lastRepBenchRomDeg: Double?                = null,
    val lastRepBilateralAsymmetryDeg: Double?      = null,
    val lastRepShoulderAbductionDeg: Double?       = null,
    val lastRepGripWidthRatio: Double?             = null,
    val lastRepExtensionIncompleteDeg: Double?     = null,
    val lastRepStickingPeriodDetected: Boolean     = false,
    val lastRepGripTooWide: Boolean                = false,
    val lastRepBilateralAsymmetry: Boolean         = false,
    val lastRepDepthInsufficientBench: Boolean     = false,
    val lastRepExtensionIncomplete: Boolean        = false
)

enum class ErrorLevel { NONE, MILD, MODERATE, SEVERE }
