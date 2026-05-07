package com.rize.rizeandroid.biomechanics

import com.rize.rizeandroid.signal.LandmarkSmoother

class Algorithms {

    private val curlBiomechanics = CurlBiomechanicsAlgorithm()
    private val squatBiomechanics = SquatBiomechanicsAlgorithm()
    private val benchPressBiomechanics = BenchPressBiomechanicsAlgorithm()

    private var activeAlgorithm: BiomechanicsAlgorithm = curlBiomechanics


    private val landmarkSmoother = LandmarkSmoother()

    private val landmarkSmootherForSquat = LandmarkSmoother(minCutoff = 2.0, beta = 0.08)


    private val landmarkSmootherForBench = LandmarkSmoother(minCutoff = 1.2, beta = 0.015)

    var currentResult: AlgorithmResult? = null
        private set


    @get:JvmName("getResultCallback")
    @set:JvmName("setResultCallback")
    var onResult: ((AlgorithmResult) -> Unit)? = null


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
    val attemptedRepCount: Int       = 0,
    val depthInsufficient: Boolean   = false,
    val trunkLeanRisk: Boolean       = false,
    val squatDepthCategory: SquatDepthCategory? = null,
    val squatTrunkCategory: SquatTrunkCategory? = null,

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

    val currentRepMinElbowAngleDeg: Double? = null,
    val currentRepMaxElbowAngleDeg: Double? = null,
    val elbowBelowTorsoLive: Boolean?       = null,
    val extensionCompleteLive: Boolean?     = null,

    val readinessReady: Boolean             = false,


    val currentRepPeakFlexionDeg: Double?   = null,

    val currentRepValleyExtensionDeg: Double? = null,

    val currentRepRomDeg: Double?           = null,

    val lastRepPeakFlexionDeg: Double?      = null,
    val lastRepRomDeg: Double?              = null,

    val shoulderCompensationDeg: Double?    = null,

    val wristAboveShoulderRatio: Double?    = null,


    val lastRepConcentricPeakVelocityDegS: Double? = null,
    val lastRepFormQuality: ErrorLevel?            = null,
    val lastRepMinKneeAngleDeg: Double?            = null,
    val lastRepMinHipAngleDeg: Double?             = null,
    val lastRepDepthInsufficient: Boolean          = false,
    val lastRepTrunkLeanRisk: Boolean              = false,
    val lastRepSquatDepthCategory: SquatDepthCategory? = null,
    val lastRepSquatTrunkCategory: SquatTrunkCategory? = null,
    val lastRepSquatRomDeg: Double?                = null,
    val lastRepEccentricPeakVelocityDegS: Double?  = null,

    val lastRepShoulderCompensationDeg: Double?    = null,
  
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

enum class SquatDepthCategory {
    PARTIAL,
    MEDIUM,
    DEEP
}

enum class SquatTrunkCategory {
    TOO_INCLINED,
    OPTIMAL,
    TOO_UPRIGHT
}
