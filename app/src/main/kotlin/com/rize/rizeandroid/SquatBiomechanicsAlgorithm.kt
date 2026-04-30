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

        private const val MIN_VISIBILITY = 0.6f
        // Aumentado levemente de 5.5 a 6.5 deg/s para compensar ruido del smoother
        // menos suave. Ahora los landmarks siguen de cerca el movimiento.
        private const val VELOCITY_HYSTERESIS = 6.5
        private const val REP_END_VELOCITY_EPS = 6.5
        private const val REP_END_LOW_VELOCITY_FRAMES = 1

        private const val START_DESCENT_ANGLE = 165.0
        private const val TOP_POSITION_ANGLE = 148.0
        private const val MIN_ASCENT_RECOVERY_DEG = 20.0

        private const val DEPTH_ERROR_THRESHOLD = 80.0
        private const val DEPTH_MEDIUM_UPPER_BOUND = 80.0
        private const val DEPTH_MEDIUM_LOWER_BOUND = 70.0
        private const val TRUNK_OPTIMAL_MIN = 50.0
        private const val TRUNK_OPTIMAL_MAX = 80.0

        // Suavizado exponencial de la velocidad angular: estable sin bloquear
        // los cambios de signo que ocurren al pasar de descenso a ascenso.
        private const val OMEGA_EMA_ALPHA = 0.4

        private const val CONCENTRIC_FATIGUE_THRESHOLD = 20.0
        private const val VELOCITY_STABLE_THRESHOLD = 10.0
        private const val MIN_VALID_ROM_DEG = 18.0
        private const val MAX_VALID_BOTTOM_ANGLE_DEG = 130.0
        private const val MIN_VALID_BOTTOM_ANGLE_DEG = 25.0
        private const val CVT_MODERATE_THRESHOLD = 5.0
        private const val CVT_INSTABILITY_THRESHOLD = 10.0
        private const val CVT_WINDOW_REPS = 6

        private const val MAX_X_VARIANCE_LATERAL = 0.15
        private const val MIN_X_VARIANCE_FRONTAL = 0.30

        // Requiere algunos frames continuos con pose valida antes de habilitar
        // deteccion de repeticiones; reduce falsos positivos al encuadrar.
        private const val READY_VISIBLE_FRAMES = 6
    }

    private enum class RepPhase { IDLE, DESCENT, ASCENT }

    // ELIMINADO: VelocitySmoothing no es necesario.
    // Los landmarks ya llegan estabilizados por LandmarkSmoother (filtro 1€),
    // por lo que la velocidad derivada es relativamente limpia.
    // Mantener doble suavizado añade ~2-3 frames de latencia innecesarios
    // que retrasan la detección de cambios de fase (DESCENT→ASCENT).
    // private val velocitySmoothing = VelocitySmoothing(windowSize = 3, outlierThreshold = 70.0)

    private var prevRawKneeAngleDeg: Double? = null
    private var prevKneeAngleDeg: Double? = null
    private var prevAngularVelocityDegS: Double? = null
    private var smoothedAngularVelocityDegS: Double? = null

    private var phase: RepPhase = RepPhase.IDLE

    private var currentMinKneeAngleDeg = Double.MAX_VALUE
    private var currentMinHipAngleDeg = Double.MAX_VALUE
    private var currentMinRawHipAngleDeg = Double.MAX_VALUE
    private var currentPeakConcentricVelocityDegS = 0.0
    private var currentPeakEccentricVelocityDegS = 0.0
    private var currentRepStartKneeAngleDeg = Double.MAX_VALUE
    private var lowVelocityFramesInAscent = 0
    private val currentRepHipAnglesDeg = mutableListOf<Double>()

    private var lastDepthInsufficient = false
    private var lastTrunkLeanRisk = false
    private var lastSquatDepthCategory: SquatDepthCategory? = null
    private var lastSquatTrunkCategory: SquatTrunkCategory? = null
    private var lastVelocityLossPercent: Double? = null
    private var lastCvtPercent: Double? = null
    private var lastTechnicalError: ErrorLevel = ErrorLevel.NONE
    private var lastErrorMagnitude: Double? = null
    private var repCount = 0
    private var attemptCount = 0

    private val concentricVelocityByRep = mutableListOf<Double>()
    private val validBottomKneeAnglesByRep = mutableListOf<Double>()

    private var visiblePoseFrames = 0

    // Snapshots de la ultima rep cerrada — exportados para persistencia per-rep.
    private var snapLastRepMinKnee: Double? = null
    private var snapLastRepMinHip: Double? = null
    private var snapLastRepRom: Double? = null
    private var snapLastRepConcVel: Double? = null
    private var snapLastRepEccVel: Double? = null
    private var snapLastRepDepthInsufficient = false
    private var snapLastRepTrunkLeanRisk = false
    private var snapLastRepSquatDepthCategory: SquatDepthCategory? = null
    private var snapLastRepSquatTrunkCategory: SquatTrunkCategory? = null
    private var snapLastRepFormQuality: ErrorLevel? = null

    override fun process(landmarkFlatList: List<Double>): AlgorithmResult {
        if (landmarkFlatList.size < 132) return emptyResult()

        val leg = selectLeg(landmarkFlatList) ?: run {
            visiblePoseFrames = 0
            resetTransientRepState()
            return emptyResult()
        }
        visiblePoseFrames += 1
        val readinessReady = visiblePoseFrames >= READY_VISIBLE_FRAMES

        val rawKneeAngleDeg = computeAngle(leg.hip.vec, leg.knee.vec, leg.ankle.vec)
        val rawHipAngleDeg = computeAngle(leg.shoulder.vec, leg.hip.vec, leg.knee.vec)

        // La derivada para repeticiones/fatiga se calcula sobre el ángulo crudo,
        // mientras que el valor filtrado se usa para mostrar la métrica estable.
        val rawAngularVelocityDegS = prevRawKneeAngleDeg?.let { (rawKneeAngleDeg - it) / DT }

        // No aplicamos un segundo filtro de angulo aqui para evitar sobre-suavizado
        // que puede retrasar/cancelar cierres de rep. Se usa el angulo directo del
        // landmark ya filtrado temporalmente en el pipeline comun.
        val kneeAngleDeg = rawKneeAngleDeg
        val hipAngleDeg = rawHipAngleDeg

        // La velocidad angular ya está relativamente limpia porque los landmarks
        // vienen filtrados por LandmarkSmoother. Usar directamente sin suavizado adicional.
        val angularVelocityDegS = rawAngularVelocityDegS?.let { raw ->
            smoothedAngularVelocityDegS = raw
            raw
        }

        val angularAccelerationDegS2 = if (angularVelocityDegS != null && prevAngularVelocityDegS != null) {
            (angularVelocityDegS - prevAngularVelocityDegS!!) / DT
        } else {
            null
        }

        if (angularVelocityDegS != null && readinessReady) {
            updateRepState(kneeAngleDeg, hipAngleDeg, rawHipAngleDeg, angularVelocityDegS)
        } else if (!readinessReady) {
            resetTransientRepState()
        }

        prevRawKneeAngleDeg = rawKneeAngleDeg
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
            attemptedRepCount = attemptCount,
            depthInsufficient = lastDepthInsufficient,
            trunkLeanRisk = lastTrunkLeanRisk,
            squatDepthCategory = lastSquatDepthCategory,
            squatTrunkCategory = lastSquatTrunkCategory,
            readinessReady = readinessReady,
            // Snapshots per-rep para persistencia.
            lastRepMinKneeAngleDeg = snapLastRepMinKnee,
            lastRepMinHipAngleDeg = snapLastRepMinHip,
            lastRepSquatRomDeg = snapLastRepRom,
            lastRepConcentricPeakVelocityDegS = snapLastRepConcVel,
            lastRepEccentricPeakVelocityDegS = snapLastRepEccVel,
            lastRepDepthInsufficient = snapLastRepDepthInsufficient,
            lastRepTrunkLeanRisk = snapLastRepTrunkLeanRisk,
            lastRepSquatDepthCategory = snapLastRepSquatDepthCategory,
            lastRepSquatTrunkCategory = snapLastRepSquatTrunkCategory,
            lastRepFormQuality = snapLastRepFormQuality
        )
    }

    override fun reset() {

        prevRawKneeAngleDeg = null
        prevKneeAngleDeg = null
        prevAngularVelocityDegS = null
        smoothedAngularVelocityDegS = null
        visiblePoseFrames = 0
        resetTransientRepState()

        currentMinKneeAngleDeg = Double.MAX_VALUE
        currentMinHipAngleDeg = Double.MAX_VALUE
        currentMinRawHipAngleDeg = Double.MAX_VALUE
        currentPeakConcentricVelocityDegS = 0.0
        currentPeakEccentricVelocityDegS = 0.0
        currentRepStartKneeAngleDeg = Double.MAX_VALUE
        lowVelocityFramesInAscent = 0
        currentRepHipAnglesDeg.clear()

        lastDepthInsufficient = false
        lastTrunkLeanRisk = false
        lastSquatDepthCategory = null
        lastSquatTrunkCategory = null
        lastVelocityLossPercent = null
        lastCvtPercent = null
        lastTechnicalError = ErrorLevel.NONE
        lastErrorMagnitude = null

        repCount = 0
        attemptCount = 0
        concentricVelocityByRep.clear()
        validBottomKneeAnglesByRep.clear()

        snapLastRepMinKnee = null
        snapLastRepMinHip = null
        snapLastRepRom = null
        snapLastRepConcVel = null
        snapLastRepEccVel = null
        snapLastRepDepthInsufficient = false
        snapLastRepTrunkLeanRisk = false
        snapLastRepSquatDepthCategory = null
        snapLastRepSquatTrunkCategory = null
        snapLastRepFormQuality = null
    }

    private fun resetTransientRepState() {
        phase = RepPhase.IDLE
        currentMinKneeAngleDeg = Double.MAX_VALUE
        currentMinHipAngleDeg = Double.MAX_VALUE
        currentMinRawHipAngleDeg = Double.MAX_VALUE
        currentPeakConcentricVelocityDegS = 0.0
        currentPeakEccentricVelocityDegS = 0.0
        currentRepStartKneeAngleDeg = Double.MAX_VALUE
        lowVelocityFramesInAscent = 0
        currentRepHipAnglesDeg.clear()
    }

    private fun updateRepState(kneeAngleDeg: Double, hipAngleDeg: Double, rawHipAngleDeg: Double, angularVelocityDegS: Double) {
        currentRepHipAnglesDeg.add(hipAngleDeg)
        when (phase) {
            RepPhase.IDLE -> {
                if (kneeAngleDeg < START_DESCENT_ANGLE && angularVelocityDegS < -VELOCITY_HYSTERESIS) {
                    phase = RepPhase.DESCENT
                    attemptCount++
                    startNewRepTracking(kneeAngleDeg, hipAngleDeg, rawHipAngleDeg)
                    currentPeakEccentricVelocityDegS = maxOf(currentPeakEccentricVelocityDegS, -angularVelocityDegS)
                }
            }

            RepPhase.DESCENT -> {
                currentMinKneeAngleDeg = minOf(currentMinKneeAngleDeg, kneeAngleDeg)
                currentMinHipAngleDeg = minOf(currentMinHipAngleDeg, hipAngleDeg)
                currentMinRawHipAngleDeg = minOf(currentMinRawHipAngleDeg, rawHipAngleDeg)

                if (angularVelocityDegS < 0.0) {
                    currentPeakEccentricVelocityDegS = maxOf(currentPeakEccentricVelocityDegS, -angularVelocityDegS)
                }

                // Cambio de fase alrededor del mínimo local de rodilla.
                if (angularVelocityDegS > VELOCITY_HYSTERESIS) {
                    phase = RepPhase.ASCENT
                    lowVelocityFramesInAscent = 0
                    currentPeakConcentricVelocityDegS = maxOf(currentPeakConcentricVelocityDegS, angularVelocityDegS)
                }
            }

            RepPhase.ASCENT -> {
                currentMinKneeAngleDeg = minOf(currentMinKneeAngleDeg, kneeAngleDeg)
                currentMinHipAngleDeg = minOf(currentMinHipAngleDeg, hipAngleDeg)
                currentMinRawHipAngleDeg = minOf(currentMinRawHipAngleDeg, rawHipAngleDeg)

                if (angularVelocityDegS > 0.0) {
                    currentPeakConcentricVelocityDegS = maxOf(currentPeakConcentricVelocityDegS, angularVelocityDegS)
                }

                val ascentRecoveredDeg = kneeAngleDeg - currentMinKneeAngleDeg
                val reachedTopPosition = kneeAngleDeg >= TOP_POSITION_ANGLE && angularVelocityDegS > -VELOCITY_HYSTERESIS
                if (kotlin.math.abs(angularVelocityDegS) <= REP_END_VELOCITY_EPS) {
                    lowVelocityFramesInAscent += 1
                } else {
                    lowVelocityFramesInAscent = 0
                }

                // Fallback para cámara real: si ya recuperó ROM suficiente y la
                // velocidad se estabiliza cerca de 0, cerramos la repetición aun
                // sin llegar al lockout ideal.
                val reachedAscentPlateau = ascentRecoveredDeg >= MIN_ASCENT_RECOVERY_DEG
                        && lowVelocityFramesInAscent >= REP_END_LOW_VELOCITY_FRAMES

                // Si ya recuperó ROM y comienza un nuevo descenso, cerramos la
                // repetición anterior para no perder conteo por falta de lockout.
                val startedNextDescent = ascentRecoveredDeg >= MIN_ASCENT_RECOVERY_DEG
                        && angularVelocityDegS < -VELOCITY_HYSTERESIS

                if (reachedTopPosition || reachedAscentPlateau || startedNextDescent) {
                    completeRep()
                    phase = RepPhase.IDLE
                    lowVelocityFramesInAscent = 0
                }
            }
        }
    }

    private fun startNewRepTracking(kneeAngleDeg: Double, hipAngleDeg: Double, rawHipAngleDeg: Double) {
        currentRepStartKneeAngleDeg = kneeAngleDeg
        currentMinKneeAngleDeg = kneeAngleDeg
        currentMinHipAngleDeg = hipAngleDeg
        currentMinRawHipAngleDeg = rawHipAngleDeg
        currentPeakConcentricVelocityDegS = 0.0
        currentPeakEccentricVelocityDegS = 0.0
        lowVelocityFramesInAscent = 0
        currentRepHipAnglesDeg.clear()
        currentRepHipAnglesDeg.add(hipAngleDeg)
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

        // Usamos el mínimo real de la cadera como referencia principal para no
        // ocultar una inclinación marcada cuando el fondo de la rep es corto.
        val bottomHipAngleDeg = minOf(currentMinRawHipAngleDeg, currentMinHipAngleDeg)
        lastDepthInsufficient = currentMinKneeAngleDeg > DEPTH_ERROR_THRESHOLD
        lastSquatDepthCategory = classifyDepth(currentMinKneeAngleDeg)
        lastSquatTrunkCategory = classifyTrunk(bottomHipAngleDeg)
        lastTrunkLeanRisk = lastSquatTrunkCategory != SquatTrunkCategory.OPTIMAL

        val cvt = lastCvtPercent ?: 0.0
        val hasInstability = cvt > CVT_INSTABILITY_THRESHOLD

        lastTechnicalError = when {
            hasInstability -> ErrorLevel.SEVERE
            lastDepthInsufficient || lastTrunkLeanRisk || cvt >= CVT_MODERATE_THRESHOLD -> ErrorLevel.MODERATE
            else -> ErrorLevel.NONE
        }

        val depthMagnitude = if (lastDepthInsufficient) currentMinKneeAngleDeg - DEPTH_ERROR_THRESHOLD else 0.0
        val trunkMagnitude = when (lastSquatTrunkCategory) {
            SquatTrunkCategory.TOO_INCLINED -> TRUNK_OPTIMAL_MIN - bottomHipAngleDeg
            SquatTrunkCategory.TOO_UPRIGHT -> bottomHipAngleDeg - TRUNK_OPTIMAL_MAX
            else -> 0.0
        }
        val instabilityMagnitude = if (hasInstability) cvt - CVT_INSTABILITY_THRESHOLD else 0.0
        lastErrorMagnitude = listOf(depthMagnitude, trunkMagnitude, instabilityMagnitude).maxOrNull()?.takeIf { it > 0.0 }

        // Snapshots de la rep recien cerrada — usados por CameraActivity para
        // crear el registro per-rep en la DB.
        snapLastRepMinKnee = currentMinKneeAngleDeg
        snapLastRepMinHip = bottomHipAngleDeg
        snapLastRepRom = repRomDeg
        snapLastRepConcVel = currentPeakConcentricVelocityDegS.takeIf { it > 0.0 }
        snapLastRepEccVel = currentPeakEccentricVelocityDegS.takeIf { it > 0.0 }
        snapLastRepDepthInsufficient = lastDepthInsufficient
        snapLastRepTrunkLeanRisk = lastTrunkLeanRisk
        snapLastRepSquatDepthCategory = lastSquatDepthCategory
        snapLastRepSquatTrunkCategory = lastSquatTrunkCategory
        snapLastRepFormQuality = lastTechnicalError
    }

    private fun computeBottomHipAngle(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sampleSize = minOf(3, values.size)
        return values.sorted().take(sampleSize).average()
    }

    private fun buildFatigueReason(): String? {
        val loss = lastVelocityLossPercent ?: return null
        return when {
            loss < VELOCITY_STABLE_THRESHOLD -> "Velocidad estable"
            loss < CONCENTRIC_FATIGUE_THRESHOLD -> "Inicio de fatiga (${format(loss)}% de perdida)"
            else -> "Fatiga tecnica significativa (${format(loss)}% de perdida)"
        }
    }

    private fun classifyDepth(bottomKneeAngleDeg: Double): SquatDepthCategory {
        return when {
            bottomKneeAngleDeg >= DEPTH_MEDIUM_UPPER_BOUND -> SquatDepthCategory.PARTIAL
            bottomKneeAngleDeg >= DEPTH_MEDIUM_LOWER_BOUND -> SquatDepthCategory.MEDIUM
            else -> SquatDepthCategory.DEEP
        }
    }

    private fun classifyTrunk(bottomHipAngleDeg: Double): SquatTrunkCategory {
        return when {
            bottomHipAngleDeg < TRUNK_OPTIMAL_MIN -> SquatTrunkCategory.TOO_INCLINED
            bottomHipAngleDeg > TRUNK_OPTIMAL_MAX -> SquatTrunkCategory.TOO_UPRIGHT
            else -> SquatTrunkCategory.OPTIMAL
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

        // Optimización: calcular promedios solo si ambas piernas son válidas
        return when {
            rightOk && leftOk -> {
                val rightAvg = rightVisibility.average()
                val leftAvg = leftVisibility.average()
                if (rightAvg >= leftAvg) right else left
            }
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


