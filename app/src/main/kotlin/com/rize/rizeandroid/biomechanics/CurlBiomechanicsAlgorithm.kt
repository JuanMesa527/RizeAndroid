package com.rize.rizeandroid.biomechanics

import kotlin.math.*


class CurlBiomechanicsAlgorithm : BiomechanicsAlgorithm {

    companion object {

        private const val IDX_SHOULDER_R = 12
        private const val IDX_ELBOW_R    = 14
        private const val IDX_WRIST_R    = 16
        private const val IDX_SHOULDER_L = 11
        private const val IDX_ELBOW_L    = 13
        private const val IDX_WRIST_L    = 15


        private const val SAMPLE_RATE_HZ = 30.0
        private const val DT = 1.0 / SAMPLE_RATE_HZ


        private const val VL_MILD   = 0.20
        private const val VL_SEVERE = 0.40


        private const val VARIANCE_WINDOW       = 60
        private const val VAR_RATIO_FATIGUE     = 1.5


        private const val MIN_REPS_FOR_REF = 3


        private const val DELTA_SIGMA_MILD   = 1.5
        private const val DELTA_SIGMA_SEVERE = 2.5
        private const val DELTA_MIN_DEG      = 10.0


        // ROM mínimo para advertencia de ROM parcial (Pinto 2012: objetivo ≥110°).
        // Se mantiene en 100° para advertencia (ligeramente por debajo del
        // objetivo académico) — la UI ya muestra el objetivo de 110° al usuario.
        private const val FULL_ROM_MIN_DEG = 100.0


        private const val SHOULDER_COMPENSATION_DEG = 15.0
        private const val REST_SAMPLES              = 15


        // Umbrales de conteo de reps.
        // FLEXION_PEAK_THRESHOLD: el brazo debe bajar POR DEBAJO de este ángulo
        // para iniciar la fase de flexión (90° ≈ ángulo recto — curl normal).
        // EXTENSION_VALLEY_THRESHOLD: el brazo debe subir POR ENCIMA de este ángulo
        // para que la rep se cuente. 105° permite reps continuas sin extensión
        // completa; el gap de 15° (90→105) evita oscilaciones por ruido (<450°/s).
        // Nota: FLEXION_PEAK debe ser < EXTENSION_VALLEY para estabilidad.
        private const val FLEXION_PEAK_THRESHOLD     = 85.0
        private const val EXTENSION_VALLEY_THRESHOLD = 100.0


        // Umbrales de intento (attemptedRepCount) — detecta cualquier intento
        // de flexión aunque no complete la rep. ATTEMPT_RESET_THRESHOLD rebaja
        // a 110° para que reps continuas (extensión parcial ~110°) re-armen el
        // contador en lugar de bloquearse después del primer intento.
        private const val ATTEMPT_FLEXION_THRESHOLD   = 100.0
        private const val ATTEMPT_EXTENSION_THRESHOLD = 105.0

        private const val ATTEMPT_RESET_THRESHOLD     = 110.0

        // Si se pierde el tracking del brazo bloqueado más de este tiempo mientras
        // inFlexion=true, se resetea la fase para evitar que el rep-counter quede
        // atascado (1 segundo = 30 frames a 30 Hz).
        private const val TRACKING_RESET_FRAMES = 30

        // Umbral de visibilidad MediaPipe: 0.65 rechaza landmarks inferidos/predichos
        // (que suelen rondar 0.5-0.6) y solo acepta landmarks con detección real.
        // Cada punto del brazo (hombro, codo, muñeca) debe superar este umbral
        // individualmente — el promedio no basta porque un solo punto predicho
        // puede contaminar el ángulo calculado.
        private const val MIN_VISIBILITY = 0.55f

        // Selección de brazo por RANGO (max - min) del ángulo de codo.
        // El rango es inmune al jitter de MediaPipe: un brazo en reposo tiene
        // rango ~4-6° (ruido de profundidad); el brazo ejercitado supera 30° en
        // la primera media-rep. SIDE_LOCK_MAX_FRAMES es el timeout de seguridad.
        private const val MOTION_LOCK_DEG      = 30.0
        private const val MOTION_MIN_FRAMES    = 5
        private const val SIDE_LOCK_MAX_FRAMES = 60
    }


    private var prevAngleDeg: Double? = null
    private var prevOmega: Double?    = null

    private val angleWindow = ArrayDeque<Double>(VARIANCE_WINDOW)


    private var inFlexion     = false
    private var currentPeak   = Double.POSITIVE_INFINITY
    private var currentValley = Double.NEGATIVE_INFINITY
    private var currentRepMaxOmega = 0.0
    // Protección contra rep fantasma: exige que el brazo haya estado en
    // extensión (≥ EXTENSION_VALLEY_THRESHOLD) al menos una vez antes de
    // permitir contar cualquier rep. Evita contar una rep en el primer frame
    // en que MediaPipe detecta el cuerpo con el codo ya flexionado.
    private var hasSeenExtension = false


    private val repPeakAngles   = mutableListOf<Double>()
    private val repValleyAngles = mutableListOf<Double>()
    private val repPeakOmegas   = mutableListOf<Double>()

    private var thetaRefPeak:   Double? = null
    private var thetaRefValley: Double? = null
    private var deltaSigma:     Double? = null
    private var omegaPeakRef:   Double? = null
    private var varianceBaseline: Double? = null


    private val shoulderRestBuffer = ArrayDeque<Double>(REST_SAMPLES)
    private var thetaShoulderRest: Double? = null
    private var restVarianceBuffer = ArrayDeque<Double>(VARIANCE_WINDOW)


    private var inAttemptFlexion    = false
    private var attemptReadyForNext = true
    private var attemptCount        = 0


    private enum class ArmSide { NONE, LEFT, RIGHT }
    private var lockedSide: ArmSide = ArmSide.NONE
    // Una vez que se escoge un lado, se mantiene durante toda la sesión para
    // evitar que el brazo inactivo (que puede cruzar la imagen o tener más
    // visibilidad puntual) "robe" el análisis. Solo se resetea en reset().
    private var sideDecisionMade: Boolean = false
    // Rango angular (max - min) por brazo para la selección robusta al ruido.
    private var minAngleR: Double = Double.MAX_VALUE
    private var maxAngleR: Double = -Double.MAX_VALUE
    private var minAngleL: Double = Double.MAX_VALUE
    private var maxAngleL: Double = -Double.MAX_VALUE
    private var sideFrameCount: Int = 0


    private var noDataFrames = 0

    private var lastSeenRepCount = 0
    private var snapLastRepConcVelDegS: Double? = null
    private var snapLastRepShoulderCompDeg: Double? = null
    private var snapLastRepFormQuality: ErrorLevel? = null



    override fun process(landmarkFlatList: List<Double>): AlgorithmResult {
        if (landmarkFlatList.size < 132) return emptyResult()

        val sample = selectArm(landmarkFlatList) ?: run {
            noDataFrames++
            // Si inFlexion se atasca 1 s sin datos, resetear para no bloquear el
            // contador en el siguiente ciclo (tracking perdido en brazo izquierdo).
            if (inFlexion && noDataFrames >= TRACKING_RESET_FRAMES) {
                inFlexion          = false
                currentPeak        = Double.POSITIVE_INFINITY
                currentRepMaxOmega = 0.0
            }
            return emptyResult()
        }
        noDataFrames = 0


        val angleDeg = computeElbowAngle(sample.shoulder, sample.elbow, sample.wrist)


        val shoulderDeg = computeUpperArmVerticalAngle(sample.shoulder, sample.elbow)


        val omega = prevAngleDeg?.let { Math.toRadians(angleDeg - it) / DT }


        val alpha = if (omega != null && prevOmega != null) {
            (omega - prevOmega!!) / DT
        } else null

        prevAngleDeg = angleDeg
        prevOmega    = omega


        if (angleWindow.size >= VARIANCE_WINDOW) angleWindow.removeFirst()
        angleWindow.addLast(angleDeg)


        if (thetaShoulderRest == null) {
            shoulderRestBuffer.addLast(shoulderDeg)
            if (shoulderRestBuffer.size >= REST_SAMPLES) {
                thetaShoulderRest = shoulderRestBuffer.average()
            }
        }


        if (varianceBaseline == null && angleWindow.size >= VARIANCE_WINDOW) {
            restVarianceBuffer.addLast(computeVariance(angleWindow))
            if (restVarianceBuffer.size >= VARIANCE_WINDOW) restVarianceBuffer.removeFirst()
        }


        if (omega != null) currentRepMaxOmega = max(currentRepMaxOmega, abs(omega))


        detectRepetition(angleDeg)


        val fatigue = detectFatigue()


        val errorResult = classifyError(angleDeg, shoulderDeg)


        val alert = fatigue.detected || errorResult.level != ErrorLevel.NONE

        val reasonCombined = listOfNotNull(fatigue.reason, errorResult.reason)
            .joinToString(" | ").ifEmpty { null }


        val livePeak  = currentPeak.takeIf  { it.isFinite() && it < FLEXION_PEAK_THRESHOLD }
        val liveValley = currentValley.takeIf { it.isFinite() }
        val liveRom = if (livePeak != null && liveValley != null) liveValley - livePeak else null


        val lastPeak   = repPeakAngles.lastOrNull()
        val lastValley = repValleyAngles.lastOrNull()
        val lastRom    = if (lastPeak != null && lastValley != null) lastValley - lastPeak else null


        val shoulderShiftDeg = thetaShoulderRest?.let { abs(shoulderDeg - it) }

        val wristAboveShoulder = (sample.shoulder.y - sample.wrist.y)
            .takeIf { it > 0.0 }

        val concentricPeakDegS: Double? = run {
            val liveDegS = if (currentRepMaxOmega > 0.0)
                Math.toDegrees(currentRepMaxOmega) else null
            val lastDegS = repPeakOmegas.lastOrNull()?.let { Math.toDegrees(it) }
            liveDegS ?: lastDegS
        }


        if (repPeakAngles.size > lastSeenRepCount) {
            snapLastRepConcVelDegS = repPeakOmegas.lastOrNull()?.let { Math.toDegrees(it) }
            snapLastRepShoulderCompDeg = shoulderShiftDeg
            snapLastRepFormQuality = errorResult.level
            lastSeenRepCount = repPeakAngles.size
        }

        return AlgorithmResult(
            angleDeg            = angleDeg,
            angularVelocity     = omega,
            angularAcceleration = alpha,
            fatigueDetected     = fatigue.detected,
            fatigueReason       = reasonCombined,
            technicalError      = errorResult.level,
            errorMagnitude      = errorResult.magnitude,
            velocityLossPercent = fatigue.velocityLossPct,
            alert               = alert,
            repCount            = repPeakAngles.size,
            attemptedRepCount   = attemptCount,
            algorithmName       = "CurlBiomechanics",

            currentRepPeakFlexionDeg     = livePeak,
            currentRepValleyExtensionDeg = liveValley,
            currentRepRomDeg             = liveRom,
            lastRepPeakFlexionDeg        = lastPeak,
            lastRepRomDeg                = lastRom,
            shoulderCompensationDeg      = shoulderShiftDeg,
            wristAboveShoulderRatio      = wristAboveShoulder,
            concentricPeakVelocityDegS   = concentricPeakDegS,

            lastRepConcentricPeakVelocityDegS = snapLastRepConcVelDegS,
            lastRepShoulderCompensationDeg    = snapLastRepShoulderCompDeg,
            lastRepFormQuality                = snapLastRepFormQuality
        )
    }

    override fun reset() {
        prevAngleDeg = null
        prevOmega    = null
        angleWindow.clear()
        repPeakAngles.clear()
        repValleyAngles.clear()
        repPeakOmegas.clear()
        inFlexion          = false
        currentPeak        = Double.POSITIVE_INFINITY
        currentValley      = Double.NEGATIVE_INFINITY
        currentRepMaxOmega = 0.0
        hasSeenExtension   = false
        inAttemptFlexion    = false
        attemptReadyForNext = true
        attemptCount        = 0
        thetaRefPeak       = null
        thetaRefValley     = null
        deltaSigma         = null
        omegaPeakRef       = null
        varianceBaseline   = null
        thetaShoulderRest  = null
        shoulderRestBuffer.clear()
        restVarianceBuffer.clear()
        noDataFrames       = 0
        lastSeenRepCount   = 0
        snapLastRepConcVelDegS = null
        snapLastRepShoulderCompDeg = null
        snapLastRepFormQuality = null
        lockedSide = ArmSide.NONE
        sideDecisionMade = false
        minAngleR = Double.MAX_VALUE
        maxAngleR = -Double.MAX_VALUE
        minAngleL = Double.MAX_VALUE
        maxAngleL = -Double.MAX_VALUE
        sideFrameCount = 0
    }



    private fun computeElbowAngle(shoulder: Vec3, elbow: Vec3, wrist: Vec3): Double {
        val ba = Vec3(shoulder.x - elbow.x, shoulder.y - elbow.y, shoulder.z - elbow.z)
        val bc = Vec3(wrist.x - elbow.x, wrist.y - elbow.y, wrist.z - elbow.z)
        val dot   = ba.x * bc.x + ba.y * bc.y + ba.z * bc.z
        val magBA = sqrt(ba.x * ba.x + ba.y * ba.y + ba.z * ba.z)
        val magBC = sqrt(bc.x * bc.x + bc.y * bc.y + bc.z * bc.z)
        if (magBA < 1e-6 || magBC < 1e-6) return 0.0
        return Math.toDegrees(acos((dot / (magBA * magBC)).coerceIn(-1.0, 1.0)))
    }


    private fun computeUpperArmVerticalAngle(shoulder: Vec3, elbow: Vec3): Double {
        val dx = elbow.x - shoulder.x
        val dy = elbow.y - shoulder.y
        val mag = sqrt(dx * dx + dy * dy)
        if (mag < 1e-6) return 0.0
        return Math.toDegrees(acos((dy / mag).coerceIn(-1.0, 1.0)))
    }


    private fun detectRepetition(angleDeg: Double) {

        // Marcar que el brazo ha llegado a extensión al menos una vez.
        // Hasta que esto ocurra no se permite contar ninguna rep (evita
        // la rep fantasma cuando MediaPipe detecta el cuerpo con el codo
        // ya flexionado en el primer frame).
        if (angleDeg >= EXTENSION_VALLEY_THRESHOLD) hasSeenExtension = true

        if (!inAttemptFlexion) {
            if (angleDeg >= ATTEMPT_RESET_THRESHOLD) attemptReadyForNext = true
            if (attemptReadyForNext && angleDeg < ATTEMPT_FLEXION_THRESHOLD) {
                inAttemptFlexion    = true
                attemptReadyForNext = false
            }
        } else {
            if (angleDeg > ATTEMPT_EXTENSION_THRESHOLD) {
                attemptCount++
                inAttemptFlexion = false

            }
        }


        if (!inFlexion) {

            if (angleDeg > currentValley) currentValley = angleDeg
            // Solo entrar en fase de flexión si el brazo ya estuvo extendido
            // al menos una vez — protege contra la rep fantasma inicial.
            if (hasSeenExtension && angleDeg < FLEXION_PEAK_THRESHOLD) {
                inFlexion   = true
                currentPeak = angleDeg
            }
        } else {
            if (angleDeg < currentPeak) currentPeak = angleDeg
            if (angleDeg > EXTENSION_VALLEY_THRESHOLD) {

                repPeakAngles.add(currentPeak)
                // For continuous reps, currentValley resets to EXTENSION_VALLEY_THRESHOLD
                // after each rep. If user doesn't fully extend, valley gets truncated.
                // Use thetaRefValley (best extension seen in calibration) as floor.
                val valleyToRecord = if (thetaRefValley != null && currentValley < thetaRefValley!!)
                    thetaRefValley!! else currentValley
                if (valleyToRecord.isFinite()) repValleyAngles.add(valleyToRecord)
                repPeakOmegas.add(currentRepMaxOmega)


                if (repPeakAngles.size >= MIN_REPS_FOR_REF && thetaRefPeak == null) {
                    thetaRefPeak   = repPeakAngles.average()
                    thetaRefValley = repValleyAngles.maxOrNull()?.takeIf { it.isFinite() }
                    deltaSigma     = stddev(repPeakAngles).coerceAtLeast(
                        DELTA_MIN_DEG / DELTA_SIGMA_MILD)
                    omegaPeakRef   = repPeakOmegas.sorted()[repPeakOmegas.size / 2]
                    varianceBaseline = restVarianceBuffer.lastOrNull()
                }


                inFlexion          = false
                currentPeak        = Double.POSITIVE_INFINITY
                currentValley      = angleDeg
                currentRepMaxOmega = 0.0
            }
        }
    }

    private fun stddev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m).pow(2) } / (xs.size - 1))
    }



    private data class FatigueResult(
        val detected: Boolean,
        val reason: String?,
        val velocityLossPct: Double?
    )

    private fun detectFatigue(): FatigueResult {
        val reasons = mutableListOf<String>()
        var vlPct: Double? = null


        val ref = omegaPeakRef
        if (ref != null && ref > 0.0 && repPeakOmegas.isNotEmpty()) {
            val last = repPeakOmegas.last()
            val vl   = (1.0 - (last / ref)).coerceIn(0.0, 1.0)
            vlPct = vl * 100.0
            when {
                vl >= VL_SEVERE -> reasons += "Pérdida de velocidad severa (VL=${pct(vl)})"
                vl >= VL_MILD   -> reasons += "Pérdida de velocidad (VL=${pct(vl)})"
            }
        }


        val base = varianceBaseline
        if (base != null && angleWindow.size >= VARIANCE_WINDOW) {
            val v = computeVariance(angleWindow)
            if (v > VAR_RATIO_FATIGUE * base) {
                reasons += "Variabilidad cinemática elevada (×${String.format("%.1f", v / base)})"
            }
        }

        return FatigueResult(
            detected = reasons.isNotEmpty(),
            reason   = reasons.joinToString(" | ").ifEmpty { null },
            velocityLossPct = vlPct
        )
    }

    private fun pct(x: Double) = "${String.format("%.0f", x * 100)}%"

    private fun computeVariance(values: Collection<Double>): Double {
        val mean = values.average()
        return values.map { (it - mean).pow(2) }.average()
    }

    private data class ErrorResult(
        val level: ErrorLevel,
        val magnitude: Double?,
        val reason: String?
    )

    private fun classifyError(angleDeg: Double, shoulderDeg: Double): ErrorResult {
        val refPeak   = thetaRefPeak   ?: return ErrorResult(ErrorLevel.NONE, null, null)
        val refValley = thetaRefValley
        val sigma     = deltaSigma     ?: return ErrorResult(ErrorLevel.NONE, null, null)
        val delta1    = DELTA_SIGMA_MILD   * sigma
        val delta2    = DELTA_SIGMA_SEVERE * sigma

        val reasons   = mutableListOf<String>()
        var worst: ErrorLevel = ErrorLevel.NONE
        var magnitudeReported: Double? = null


        if (repPeakAngles.size >= MIN_REPS_FOR_REF) {
            val lastPeak = repPeakAngles.last()
            val e = abs(lastPeak - refPeak)
            magnitudeReported = e
            val lvl = when {
                e < delta1 -> ErrorLevel.NONE
                e < delta2 -> ErrorLevel.MILD
                else       -> ErrorLevel.SEVERE
            }
            if (lvl != ErrorLevel.NONE) {
                reasons += "Desviación de pico ${String.format("%.1f°", e)} (δ₁=${String.format("%.1f°", delta1)})"
                worst = maxOf(worst, lvl)
            }
        }


        if (refValley != null && repPeakAngles.size >= MIN_REPS_FOR_REF) {
            val lastRom = repValleyAngles.lastOrNull()?.minus(repPeakAngles.last())
            if (lastRom != null && lastRom < FULL_ROM_MIN_DEG) {
                reasons += "Rango incompleto (${String.format("%.0f°", lastRom)} < 110°)"
                worst = maxOf(worst, ErrorLevel.MODERATE)
            }
        }


        val rest = thetaShoulderRest
        if (rest != null) {
            val shoulderShift = abs(shoulderDeg - rest)
            if (shoulderShift > SHOULDER_COMPENSATION_DEG) {
                reasons += "Compensación del hombro ${String.format("%.0f°", shoulderShift)}"
                worst = maxOf(worst, ErrorLevel.SEVERE)
            }
        }

        return ErrorResult(
            level     = worst,
            magnitude = magnitudeReported,
            reason    = reasons.joinToString(" | ").ifEmpty { null }
        )
    }


    private fun selectArm(flat: List<Double>): ArmSample? {
        val sR = getLandmark(flat, IDX_SHOULDER_R)
        val eR = getLandmark(flat, IDX_ELBOW_R)
        val wR = getLandmark(flat, IDX_WRIST_R)
        val sL = getLandmark(flat, IDX_SHOULDER_L)
        val eL = getLandmark(flat, IDX_ELBOW_L)
        val wL = getLandmark(flat, IDX_WRIST_L)

        val rightOk = listOf(sR, eR, wR).all { it.visibility >= MIN_VISIBILITY }
        val leftOk  = listOf(sL, eL, wL).all { it.visibility >= MIN_VISIBILITY }

        // Bloqueo permanente: una vez decidido, usar siempre el mismo brazo.
        if (sideDecisionMade) {
            return when (lockedSide) {
                ArmSide.RIGHT -> if (rightOk) ArmSample(sR.vec, eR.vec, wR.vec) else null
                ArmSide.LEFT  -> if (leftOk)  ArmSample(sL.vec, eL.vec, wL.vec) else null
                ArmSide.NONE  -> null
            }
        }

        // Un solo brazo visible → bloquear de inmediato, sin ambigüedad.
        if (rightOk && !leftOk) {
            lockedSide = ArmSide.RIGHT; sideDecisionMade = true
            return ArmSample(sR.vec, eR.vec, wR.vec)
        }
        if (leftOk && !rightOk) {
            lockedSide = ArmSide.LEFT; sideDecisionMade = true
            return ArmSample(sL.vec, eL.vec, wL.vec)
        }
        if (!rightOk && !leftOk) return null

        // Ambos visibles: trackear rango (max-min) del ángulo de codo por brazo.
        // Rango es inmune al jitter de profundidad de MediaPipe: brazo en reposo
        // tiene rango ~4-6°; brazo ejercitado supera 30° en la primera media-rep.
        val angleR = computeElbowAngle(sR.vec, eR.vec, wR.vec)
        val angleL = computeElbowAngle(sL.vec, eL.vec, wL.vec)
        if (angleR < minAngleR) minAngleR = angleR
        if (angleR > maxAngleR) maxAngleR = angleR
        if (angleL < minAngleL) minAngleL = angleL
        if (angleL > maxAngleL) maxAngleL = angleL
        sideFrameCount++

        val rangeR = maxAngleR - minAngleR
        val rangeL = maxAngleL - minAngleL
        val rMoves = rangeR >= MOTION_LOCK_DEG
        val lMoves = rangeL >= MOTION_LOCK_DEG
        if (sideFrameCount >= MOTION_MIN_FRAMES && (rMoves || lMoves || sideFrameCount >= SIDE_LOCK_MAX_FRAMES)) {
            lockedSide = if (rangeR >= rangeL) ArmSide.RIGHT else ArmSide.LEFT
            sideDecisionMade = true
            return when (lockedSide) {
                ArmSide.RIGHT -> ArmSample(sR.vec, eR.vec, wR.vec)
                ArmSide.LEFT  -> ArmSample(sL.vec, eL.vec, wL.vec)
                ArmSide.NONE  -> null
            }
        }

        // Todavía acumulando: devolver el brazo con mayor rango hasta ahora.
        return if (rangeR >= rangeL) ArmSample(sR.vec, eR.vec, wR.vec)
               else                  ArmSample(sL.vec, eL.vec, wL.vec)
    }

    private data class ArmSample(
        val shoulder: Vec3,
        val elbow: Vec3,
        val wrist: Vec3
    )

    private fun getLandmark(flat: List<Double>, index: Int): Landmark {
        val base = index * 4
        return Landmark(Vec3(flat[base], flat[base + 1], flat[base + 2]), flat[base + 3].toFloat())
    }

    private fun emptyResult() = AlgorithmResult(algorithmName = "CurlBiomechanics")

    private data class Vec3(val x: Double, val y: Double, val z: Double)
    private data class Landmark(val vec: Vec3, val visibility: Float)
    private data class ArmLandmarks(val shoulder: Vec3, val elbow: Vec3, val wrist: Vec3)
}