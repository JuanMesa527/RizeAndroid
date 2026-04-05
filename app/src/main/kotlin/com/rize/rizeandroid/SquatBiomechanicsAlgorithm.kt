package com.rize.rizeandroid

import kotlin.math.acos
import kotlin.math.pow
import kotlin.math.sqrt

class SquatBiomechanicsAlgorithm : BiomechanicsAlgorithm {

    companion object {
        private const val IDX_SHOULDER_R = 12
        private const val IDX_HIP_R = 24
        private const val IDX_KNEE_R = 26
        private const val IDX_ANKLE_R = 28

        private const val IDX_SHOULDER_L = 11
        private const val IDX_HIP_L = 23
        private const val IDX_KNEE_L = 25
        private const val IDX_ANKLE_L = 27

        private const val SAMPLE_RATE_HZ = 30.0
        private const val DT = 1.0 / SAMPLE_RATE_HZ

        private const val MIN_VISIBILITY = 0.5f
        private const val VELOCITY_HYSTERESIS = 8.0

        private const val START_DESCENT_ANGLE = 150.0
        private const val TOP_POSITION_ANGLE = 155.0

        private const val DEPTH_ERROR_THRESHOLD = 45.0
        private const val TRUNK_RISK_THRESHOLD = 80.0

        private const val CONCENTRIC_FATIGUE_THRESHOLD = 20.0
        private const val MIN_VALID_ROM_DEG = 25.0
        private const val MAX_VALID_BOTTOM_ANGLE_DEG = 130.0
        private const val MIN_VALID_BOTTOM_ANGLE_DEG = 25.0
        private const val CVT_WINDOW_REPS = 6

        // ── Validación de posición (lateral vs frontal) ────────────────────────────
        // En vista lateral correcta, la coordenada X (izq-der) debe variar poco.
        // En vista frontal, la X varía mucho. Detectamos esta diferencia.
        private const val MAX_X_VARIANCE_LATERAL = 0.15  // Vista lateral: coordenadas X concentradas
        private const val MIN_X_VARIANCE_FRONTAL = 0.30  // Vista frontal: mucha dispersión en X
    }

    private enum class RepPhase { IDLE, DESCENT, ASCENT }

    private var prevKneeAngleDeg: Double? = null
    private var prevAngularVelocityDegS: Double? = null

    private var phase: RepPhase = RepPhase.IDLE

    private var currentMinKneeAngleDeg = Double.MAX_VALUE
    private var currentMinHipAngleDeg = Double.MAX_VALUE
    private var currentPeakConcentricVelocityDegS = 0.0
    private var currentPeakEccentricVelocityDegS = 0.0
    private var currentRepStartKneeAngleDeg = Double.MAX_VALUE

    private var lastDepthInsufficient = false
    private var lastTrunkLeanRisk = false
    private var lastVelocityLossPercent: Double? = null
    private var lastCvtPercent: Double? = null
    private var lastTechnicalError: ErrorLevel = ErrorLevel.NONE
    private var lastErrorMagnitude: Double? = null
    private var repCount = 0

    private val concentricVelocityByRep = mutableListOf<Double>()
    private val validBottomKneeAnglesByRep = mutableListOf<Double>()

    override fun process(landmarkFlatList: List<Double>): AlgorithmResult {
        if (landmarkFlatList.size < 132) return emptyResult()

        val leg = selectLeg(landmarkFlatList) ?: return emptyResult()

        val kneeAngleDeg = computeAngle(leg.hip.vec, leg.knee.vec, leg.ankle.vec)
        val hipAngleDeg = computeAngle(leg.shoulder.vec, leg.hip.vec, leg.knee.vec)

        val angularVelocityDegS = prevKneeAngleDeg?.let { (kneeAngleDeg - it) / DT }
        val angularAccelerationDegS2 = if (angularVelocityDegS != null && prevAngularVelocityDegS != null) {
            (angularVelocityDegS - prevAngularVelocityDegS!!) / DT
        } else {
            null
        }

        if (angularVelocityDegS != null) {
            updateRepState(kneeAngleDeg, hipAngleDeg, angularVelocityDegS)
        }

        prevKneeAngleDeg = kneeAngleDeg
        prevAngularVelocityDegS = angularVelocityDegS

        val fatigueDetected = (lastVelocityLossPercent ?: 0.0) >= CONCENTRIC_FATIGUE_THRESHOLD
        val alert = fatigueDetected || lastTechnicalError != ErrorLevel.NONE

        return AlgorithmResult(
            angleDeg = kneeAngleDeg,
            angularVelocity = angularVelocityDegS,
            angularAcceleration = angularAccelerationDegS2,
            fatigueDetected = fatigueDetected,
            fatigueReason = buildFatigueReason(),
            technicalError = lastTechnicalError,
            errorMagnitude = lastErrorMagnitude,
            alert = alert,
            algorithmName = "SquatBiomechanics",
            kneeAngleDeg = kneeAngleDeg,
            hipAngleDeg = hipAngleDeg,
            kneeAngularVelocityDegS = angularVelocityDegS,
            concentricPeakVelocityDegS = if (currentPeakConcentricVelocityDegS > 0.0) currentPeakConcentricVelocityDegS else null,
            eccentricPeakVelocityDegS = if (currentPeakEccentricVelocityDegS > 0.0) currentPeakEccentricVelocityDegS else null,
            velocityLossPercent = lastVelocityLossPercent,
            cvtPercent = lastCvtPercent,
            repCount = repCount,
            depthInsufficient = lastDepthInsufficient,
            trunkLeanRisk = lastTrunkLeanRisk
        )
    }

    override fun reset() {
        prevKneeAngleDeg = null
        prevAngularVelocityDegS = null
        phase = RepPhase.IDLE

        currentMinKneeAngleDeg = Double.MAX_VALUE
        currentMinHipAngleDeg = Double.MAX_VALUE
        currentPeakConcentricVelocityDegS = 0.0
        currentPeakEccentricVelocityDegS = 0.0
        currentRepStartKneeAngleDeg = Double.MAX_VALUE

        lastDepthInsufficient = false
        lastTrunkLeanRisk = false
        lastVelocityLossPercent = null
        lastCvtPercent = null
        lastTechnicalError = ErrorLevel.NONE
        lastErrorMagnitude = null

        repCount = 0
        concentricVelocityByRep.clear()
        validBottomKneeAnglesByRep.clear()
    }

    private fun updateRepState(kneeAngleDeg: Double, hipAngleDeg: Double, angularVelocityDegS: Double) {
        when (phase) {
            RepPhase.IDLE -> {
                if (kneeAngleDeg < START_DESCENT_ANGLE && angularVelocityDegS < -VELOCITY_HYSTERESIS) {
                    phase = RepPhase.DESCENT
                    startNewRepTracking(kneeAngleDeg, hipAngleDeg)
                    currentPeakEccentricVelocityDegS = maxOf(currentPeakEccentricVelocityDegS, -angularVelocityDegS)
                }
            }

            RepPhase.DESCENT -> {
                currentMinKneeAngleDeg = minOf(currentMinKneeAngleDeg, kneeAngleDeg)
                currentMinHipAngleDeg = minOf(currentMinHipAngleDeg, hipAngleDeg)

                if (angularVelocityDegS < 0.0) {
                    currentPeakEccentricVelocityDegS = maxOf(currentPeakEccentricVelocityDegS, -angularVelocityDegS)
                }

                // Cambio de fase alrededor del mínimo local de rodilla.
                if (angularVelocityDegS > VELOCITY_HYSTERESIS) {
                    phase = RepPhase.ASCENT
                    currentPeakConcentricVelocityDegS = maxOf(currentPeakConcentricVelocityDegS, angularVelocityDegS)
                }
            }

            RepPhase.ASCENT -> {
                currentMinKneeAngleDeg = minOf(currentMinKneeAngleDeg, kneeAngleDeg)
                currentMinHipAngleDeg = minOf(currentMinHipAngleDeg, hipAngleDeg)

                if (angularVelocityDegS > 0.0) {
                    currentPeakConcentricVelocityDegS = maxOf(currentPeakConcentricVelocityDegS, angularVelocityDegS)
                }

                if (kneeAngleDeg >= TOP_POSITION_ANGLE && angularVelocityDegS > -VELOCITY_HYSTERESIS) {
                    completeRep()
                    phase = RepPhase.IDLE
                }
            }
        }
    }

    private fun startNewRepTracking(kneeAngleDeg: Double, hipAngleDeg: Double) {
        currentRepStartKneeAngleDeg = kneeAngleDeg
        currentMinKneeAngleDeg = kneeAngleDeg
        currentMinHipAngleDeg = hipAngleDeg
        currentPeakConcentricVelocityDegS = 0.0
        currentPeakEccentricVelocityDegS = 0.0
    }

    private fun completeRep() {
        if (currentMinKneeAngleDeg == Double.MAX_VALUE || currentMinHipAngleDeg == Double.MAX_VALUE) return

        val repRomDeg = currentRepStartKneeAngleDeg - currentMinKneeAngleDeg
        val isValidRep = repRomDeg >= MIN_VALID_ROM_DEG
                && currentMinKneeAngleDeg <= MAX_VALID_BOTTOM_ANGLE_DEG
                && currentMinKneeAngleDeg >= MIN_VALID_BOTTOM_ANGLE_DEG

        if (!isValidRep) {
            return
        }

        repCount += 1
        validBottomKneeAnglesByRep.add(currentMinKneeAngleDeg)

        if (currentPeakConcentricVelocityDegS > 0.0) {
            concentricVelocityByRep.add(currentPeakConcentricVelocityDegS)
            val vRef = concentricVelocityByRep.firstOrNull() ?: currentPeakConcentricVelocityDegS
            if (vRef > 0.0) {
                val rawLoss = ((vRef - currentPeakConcentricVelocityDegS) / vRef) * 100.0
                lastVelocityLossPercent = maxOf(0.0, rawLoss)
            }
        }

        val cvtSample = validBottomKneeAnglesByRep.takeLast(CVT_WINDOW_REPS)
        lastCvtPercent = computeCvt(cvtSample)
        lastDepthInsufficient = currentMinKneeAngleDeg > DEPTH_ERROR_THRESHOLD
        lastTrunkLeanRisk = currentMinHipAngleDeg < TRUNK_RISK_THRESHOLD

        val cvt = lastCvtPercent ?: 0.0
        val hasInstability = cvt > 10.0

        lastTechnicalError = when {
            hasInstability || (lastDepthInsufficient && lastTrunkLeanRisk) -> ErrorLevel.SEVERE
            lastDepthInsufficient || lastTrunkLeanRisk || cvt >= 5.0 -> ErrorLevel.MODERATE
            else -> ErrorLevel.NONE
        }

        val depthMagnitude = if (lastDepthInsufficient) currentMinKneeAngleDeg - DEPTH_ERROR_THRESHOLD else 0.0
        val trunkMagnitude = if (lastTrunkLeanRisk) TRUNK_RISK_THRESHOLD - currentMinHipAngleDeg else 0.0
        val instabilityMagnitude = if (hasInstability) cvt - 10.0 else 0.0
        lastErrorMagnitude = listOf(depthMagnitude, trunkMagnitude, instabilityMagnitude).maxOrNull()?.takeIf { it > 0.0 }
    }

    private fun buildFatigueReason(): String? {
        val loss = lastVelocityLossPercent ?: return null
        return when {
            loss < 10.0 -> "Velocidad estable"
            loss < 20.0 -> "Inicio de fatiga (${format(loss)}% de perdida)"
            else -> "Fatiga tecnica significativa (${format(loss)}% de perdida)"
        }
    }

    private fun computeCvt(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        if (mean == 0.0) return null
        val variance = values.map { (it - mean).pow(2) }.average()
        val sigma = sqrt(variance)
        return (sigma / mean) * 100.0
    }

    private fun selectLeg(flat: List<Double>): LegLandmarks? {
        val right = LegLandmarks(
            shoulder = getLandmark(flat, IDX_SHOULDER_R),
            hip = getLandmark(flat, IDX_HIP_R),
            knee = getLandmark(flat, IDX_KNEE_R),
            ankle = getLandmark(flat, IDX_ANKLE_R)
        )
        val left = LegLandmarks(
            shoulder = getLandmark(flat, IDX_SHOULDER_L),
            hip = getLandmark(flat, IDX_HIP_L),
            knee = getLandmark(flat, IDX_KNEE_L),
            ankle = getLandmark(flat, IDX_ANKLE_L)
        )

        val rightVisibility = right.visibilityList()
        val leftVisibility = left.visibilityList()

        val rightOk = rightVisibility.all { it >= MIN_VISIBILITY }
        val leftOk = leftVisibility.all { it >= MIN_VISIBILITY }

        return when {
            rightOk && leftOk -> if (rightVisibility.average() >= leftVisibility.average()) right else left
            rightOk -> right
            leftOk -> left
            else -> null
        }
    }

    private fun computeAngle(a: Vec3, b: Vec3, c: Vec3): Double {
        val ba = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
        val bc = Vec3(c.x - b.x, c.y - b.y, c.z - b.z)

        val dot = ba.x * bc.x + ba.y * bc.y + ba.z * bc.z
        val magBa = sqrt(ba.x * ba.x + ba.y * ba.y + ba.z * ba.z)
        val magBc = sqrt(bc.x * bc.x + bc.y * bc.y + bc.z * bc.z)

        if (magBa < 1e-6 || magBc < 1e-6) return 0.0

        val cosValue = (dot / (magBa * magBc)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosValue))
    }

    private fun getLandmark(flat: List<Double>, index: Int): Landmark {
        val base = index * 4
        return Landmark(
            vec = Vec3(flat[base], flat[base + 1], flat[base + 2]),
            visibility = flat[base + 3].toFloat()
        )
    }

    private fun emptyResult() = AlgorithmResult(algorithmName = "SquatBiomechanics")

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    private data class Vec3(val x: Double, val y: Double, val z: Double)
    private data class Landmark(val vec: Vec3, val visibility: Float)

    private data class LegLandmarks(
        val shoulder: Landmark,
        val hip: Landmark,
        val knee: Landmark,
        val ankle: Landmark
    ) {
        fun visibilityList(): List<Float> = listOf(
            shoulder.visibility,
            hip.visibility,
            knee.visibility,
            ankle.visibility
        )
    }
}


