package com.rize.rizeandroid

class Algorithms {

    private val curlBiomechanics = CurlBiomechanicsAlgorithm()
    private val squatBiomechanics = SquatBiomechanicsAlgorithm()
    private val benchPressBiomechanics = BenchPressBiomechanicsAlgorithm()

    private var activeAlgorithm: BiomechanicsAlgorithm = curlBiomechanics

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
    }

    fun onPoseData(landmarkFlatList: List<Double>) {
        val result = activeAlgorithm.process(landmarkFlatList)
        currentResult = result
        onResult?.invoke(result)
    }

    fun reset() {
        activeAlgorithm.reset()
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
    val extensionIncomplete: Boolean      = false
)

enum class ErrorLevel { NONE, MILD, MODERATE, SEVERE }