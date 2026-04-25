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

    var currentResult: AlgorithmResult? = null
        private set

    // @JvmName renombra el getter/setter en el bytecode a "getResultCallback"
    // y "setResultCallback", eliminando cualquier colisión con nombres generados.
    // Desde Java: algorithms.setResultCallback(result -> { ... });
    @get:JvmName("getResultCallback")
    @set:JvmName("setResultCallback")
    var onResult: ((AlgorithmResult) -> Unit)? = null

    fun selectAlgorithm(exerciseName: String) {
        activeAlgorithm = when {
            exerciseName.contains("curl", ignoreCase = true) -> curlBiomechanics
            exerciseName.contains("squat", ignoreCase = true) -> squatBiomechanics
            exerciseName.contains("bench", ignoreCase = true) -> benchPressBiomechanics
            else -> curlBiomechanics
        }
        activeAlgorithm.reset()
        landmarkSmoother.reset()
    }

    fun onPoseData(landmarkFlatList: List<Double>) {
        val smoothed = landmarkSmoother.filter(
            landmarkFlatList,
            System.currentTimeMillis()
        )
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
        val smoothed = landmarkSmoother.filter(landmarkFlatList, timestampMs)
        val result = activeAlgorithm.process(smoothed)
        currentResult = result
        onResult?.invoke(result)
    }

    fun reset() {
        activeAlgorithm.reset()
        landmarkSmoother.reset()
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
    val readinessReady: Boolean             = false
)

enum class ErrorLevel { NONE, MILD, MODERATE, SEVERE }
