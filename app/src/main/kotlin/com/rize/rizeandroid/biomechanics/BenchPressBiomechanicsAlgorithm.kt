package com.rize.rizeandroid.biomechanics

import com.rize.rizeandroid.biomechanics.calibration.BenchPressCalibratedThresholds
import com.rize.rizeandroid.biomechanics.calibration.BenchPressCalibrator
import com.rize.rizeandroid.biomechanics.calibration.BenchPressDebugSwitches
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Algoritmo de biomecánica específico para press de banca, con detección de errores técnicos comunes y métricas relevantes para esta actividad.
 */
class BenchPressBiomechanicsAlgorithm(
    calibrationEnabled: Boolean = BenchPressDebugSwitches.calibrationEnabled
) : BiomechanicsAlgorithm {

    companion object {
        private const val IDX_SHOULDER_L = 11
        private const val IDX_SHOULDER_R = 12
        private const val IDX_ELBOW_L = 13
        private const val IDX_ELBOW_R = 14
        private const val IDX_WRIST_L = 15
        private const val IDX_WRIST_R = 16
        private const val IDX_HIP_L = 23
        private const val IDX_HIP_R = 24

        private const val SAMPLE_RATE_HZ = 30.0
        private const val DT = 1.0 / SAMPLE_RATE_HZ
        private const val MIN_VISIBILITY = 0.5f

        
        private const val VELOCITY_HYSTERESIS = 15.0
     
        private const val GRIP_WIDTH_RATIO_TARGET = 1.5
        private const val GRIP_WIDTH_RATIO_MIN = GRIP_WIDTH_RATIO_TARGET - 0.25
        private const val GRIP_WIDTH_RATIO_MAX = GRIP_WIDTH_RATIO_TARGET + 0.25
        private const val GRIP_WIDTH_RATIO_CRITICAL = 2.0

        private const val SYMMETRY_THRESHOLD_PERCENT = 8.0
        private const val SYMMETRY_ALERT_THRESHOLD_PERCENT = 15.0

        private const val LIVE_CHECK_RESET_DELTA_DEG = 10.0

        private const val STICKING_DURATION_MS = 870L

        private const val VL_WARNING_PERCENT = 25.0
        private const val VL_CRITICAL_PERCENT = 35.0

        private const val MIN_VALID_ROM_DEG = 30.0

        private const val OMEGA_EMA_ALPHA = 0.4

        private const val POSTURAL_DEBOUNCE_FRAMES = 10

        private const val SYMMETRY_MEDIAN_WINDOW = 5

        private const val READY_STABLE_FRAMES = 8            
        private const val READY_ANGLE_STD_MAX_DEG = 20.0     
        private const val READY_WINDOW_FRAMES = 8
    }

    private enum class RepPhase { IDLE, DESCENT, ASCENT }

    private enum class ReadinessState { NOT_READY, STABILIZING, READY }

    private val calibrator = BenchPressCalibrator(calibrationEnabled)
    private val t: BenchPressCalibratedThresholds
        get() = calibrator.thresholds
    private var lastCalibrationCommittedReported = false

    private var prevElbowAngleDeg: Double? = null
    private var prevAngularVelocityDegS: Double? = null
    private var smoothedAngularVelocityDegS: Double? = null

    private var phase: RepPhase = RepPhase.IDLE

    private var readinessState: ReadinessState = ReadinessState.NOT_READY
    private var stableFrameCount = 0
    private val recentAngles = ArrayDeque<Double>(READY_WINDOW_FRAMES)

    private var asymmetryConsecutiveFrames = 0
    private var abductionWarningConsecutiveFrames = 0
    private var abductionCriticalConsecutiveFrames = 0

    private val asymmetryWindow = ArrayDeque<Double>(SYMMETRY_MEDIAN_WINDOW)

    private var currentMinElbowAngleDeg = Double.MAX_VALUE
    private var currentRepStartElbowAngleDeg = Double.MAX_VALUE
    private var currentPeakConcentricVelocityDegS = 0.0
    private var currentPeakEccentricVelocityDegS = 0.0
    private var currentRepElbowWentBelowTorso = false
    private var currentRepTopElbowAngleDeg = 0.0
    private var currentDepthCheckVisible = false
    private var currentExtensionCheckVisible = false
    private var currentRepWorstShoulderAbductionDeg: Double? = null
    private var currentRepShoulderAbductionRisk = false
    private var currentRepShoulderAbductionCritical = false

    private var stickingStartMs: Long? = null
    private var currentStickingPeriodDetected = false

    private var lastGripWidthRatioMeasured: Double? = null
    private var lastDepthInsufficientBench = false
    private var lastExtensionIncomplete = false
    private var lastExtensionIncompleteDeg: Double? = null
    private var lastGripTooWide = false
    private var lastShoulderAbductionRisk = false
    private var lastShoulderAbductionDeg: Double? = null
    private var lastBilateralAsymmetry = false
    private var lastBilateralAsymmetryDeg: Double? = null
    private var lastVelocityLossPercent: Double? = null
    private var lastTechnicalError: ErrorLevel = ErrorLevel.NONE
    private var lastErrorMagnitude: Double? = null
    private var repCount = 0
    private var attemptCount = 0

    private val concentricVelocityByRep = mutableListOf<Double>()

    private var snapLastRepMinElbow: Double? = null
    private var snapLastRepRom: Double? = null
    private var snapLastRepConcVel: Double? = null
    private var snapLastRepBilateralAsymmetryDeg: Double? = null
    private var snapLastRepShoulderAbductionDeg: Double? = null
    private var snapLastRepGripWidthRatio: Double? = null
    private var snapLastRepExtensionIncompleteDeg: Double? = null
    private var snapLastRepStickingPeriodDetected = false
    private var snapLastRepGripTooWide = false
    private var snapLastRepBilateralAsymmetry = false
    private var snapLastRepDepthInsufficientBench = false
    private var snapLastRepExtensionIncomplete = false
    private var snapLastRepFormQuality: ErrorLevel? = null

    /**
     * Procesa una lista plana de landmarks (x, y, visibility) y devuelve un [AlgorithmResult] con las métricas calculadas para el press de banca, 
     * incluyendo detección de errores técnicos comunes como grip demasiado ancho, falta de profundidad, extensión incompleta, abducción de hombros fuera de rango, 
     * asimetría bilateral, pérdida de velocidad significativa en la fase concéntrica, y detección de períodos de sticking. 
     */
    override fun process(landmarkFlatList: List<Double>): AlgorithmResult {
        if (landmarkFlatList.size < 132) return emptyResult()

        val leftArm = getArmLandmarks(landmarkFlatList, Side.LEFT)
        val rightArm = getArmLandmarks(landmarkFlatList, Side.RIGHT)

        val leftVisible = leftArm.allVisible()
        val rightVisible = rightArm.allVisible()

        if (!leftVisible && !rightVisible) {
            degradeReadiness()
            return emptyResult()
        }

        val leftElbowAngle = if (leftVisible) computeAngle2D(leftArm.shoulder.vec, leftArm.elbow.vec, leftArm.wrist.vec) else null
        val rightElbowAngle = if (rightVisible) computeAngle2D(rightArm.shoulder.vec, rightArm.elbow.vec, rightArm.wrist.vec) else null

        // Angulo primario = promedio de ambos lados (o el disponible)
        val primaryElbowAngle = when {
            leftElbowAngle != null && rightElbowAngle != null -> (leftElbowAngle + rightElbowAngle) / 2.0
            leftElbowAngle != null -> leftElbowAngle
            rightElbowAngle != null -> rightElbowAngle
            else -> {
                degradeReadiness()
                return emptyResult()
            }
        }

        val rawAngularVelocityDegS = prevElbowAngleDeg?.let { (primaryElbowAngle - it) / DT }
        val angularVelocityDegS = if (rawAngularVelocityDegS != null) {
            val prev = smoothedAngularVelocityDegS
            val next = if (prev == null) {
                rawAngularVelocityDegS
            } else {
                OMEGA_EMA_ALPHA * rawAngularVelocityDegS + (1.0 - OMEGA_EMA_ALPHA) * prev
            }
            smoothedAngularVelocityDegS = next
            next
        } else null

        val angularAccelerationDegS2 = if (angularVelocityDegS != null && prevAngularVelocityDegS != null) {
            (angularVelocityDegS - prevAngularVelocityDegS!!) / DT
        } else null

        updateReadiness(primaryElbowAngle, leftVisible && rightVisible)

        val wasCommitted = calibrator.state == BenchPressCalibrator.State.COMMITTED
        when (readinessState) {
            ReadinessState.READY -> calibrator.onReadyFrame(landmarkFlatList, primaryElbowAngle)
            ReadinessState.NOT_READY -> calibrator.onReadinessLost()
            ReadinessState.STABILIZING -> { /* no-op: aun no estamos estables */ }
        }

        if (!wasCommitted && calibrator.state == BenchPressCalibrator.State.COMMITTED) {
            stickingStartMs = null
            currentStickingPeriodDetected = false
        }

        var gripWidthRatio: Double? = null
        if (leftVisible && rightVisible) {
            val wristDist = horizontalDistance(leftArm.wrist.vec, rightArm.wrist.vec)
            val biacromialDist = horizontalDistance(leftArm.shoulder.vec, rightArm.shoulder.vec)
            if (biacromialDist > 1e-6) {
                gripWidthRatio = (wristDist / biacromialDist) * t.gripPerspectiveCorrection
                lastGripWidthRatioMeasured = gripWidthRatio

                lastGripTooWide = gripWidthRatio > GRIP_WIDTH_RATIO_CRITICAL
            }
        }
     
        val evaluateAbductionNow = primaryElbowAngle < t.abductionEvaluationElbowDeg
        val abductionLeft = if (evaluateAbductionNow && leftVisible) {
            computeAngle2D(leftArm.hip.vec, leftArm.shoulder.vec, leftArm.elbow.vec)
        } else null
        val abductionRight = if (evaluateAbductionNow && rightVisible) {
            computeAngle2D(rightArm.hip.vec, rightArm.shoulder.vec, rightArm.elbow.vec)
        } else null
        val abductionValues = listOfNotNull(abductionLeft, abductionRight)
        val worstAbduction = when {
            abductionValues.any { it > t.abductionMaxOkDeg } -> abductionValues.maxOrNull()
            abductionValues.any { it < t.abductionMinOkDeg } -> abductionValues.minOrNull()
            else -> abductionValues.maxOrNull()
        }
        lastShoulderAbductionDeg = worstAbduction
        lastShoulderAbductionRisk = updateAbductionRiskDebounced(worstAbduction, primaryElbowAngle)
        if (worstAbduction != null && phase != RepPhase.IDLE) {
            currentRepWorstShoulderAbductionDeg = maxOf(
                currentRepWorstShoulderAbductionDeg ?: worstAbduction,
                worstAbduction
            )
            currentRepShoulderAbductionRisk =
                currentRepShoulderAbductionRisk || isAbductionOutsideOkRange(worstAbduction, primaryElbowAngle)
            currentRepShoulderAbductionCritical =
                currentRepShoulderAbductionCritical || worstAbduction > t.abductionCriticalDeg
        }

        var bilateralAsymmetryDeg: Double? = null
        if (leftVisible && rightVisible) {
            val shoulderWidth = horizontalDistance(leftArm.shoulder.vec, rightArm.shoulder.vec)
            val rawAsymmetry = if (shoulderWidth > 1e-6) {
                (verticalDistance(leftArm.wrist.vec, rightArm.wrist.vec) / shoulderWidth) * 100.0
            } else null
            if (rawAsymmetry != null) {
                val smoothedAsymmetry = pushAndMedian(asymmetryWindow, rawAsymmetry, SYMMETRY_MEDIAN_WINDOW)
                bilateralAsymmetryDeg = smoothedAsymmetry
                lastBilateralAsymmetryDeg = smoothedAsymmetry
                lastBilateralAsymmetry = updateAsymmetryDebounced(smoothedAsymmetry)
            }
        }

        updateLiveRuleIndicators(primaryElbowAngle)

        val calibrationReady = calibrator.state == BenchPressCalibrator.State.COMMITTED ||
                calibrator.state == BenchPressCalibrator.State.DISABLED
        if (calibrationReady && phase == RepPhase.ASCENT && angularVelocityDegS != null) {
            if (abs(angularVelocityDegS) < t.stickingVelocityThresholdDegS) {
                if (stickingStartMs == null) {
                    stickingStartMs = System.currentTimeMillis()
                } else {
                    val elapsed = System.currentTimeMillis() - stickingStartMs!!
                    if (elapsed > t.stickingDurationMs) {
                        currentStickingPeriodDetected = true
                    }
                }
            } else {
                stickingStartMs = null
            }
        } else {
            stickingStartMs = null
        }

        if (angularVelocityDegS != null) {
            updateRepState(primaryElbowAngle, angularVelocityDegS)
        }

        prevElbowAngleDeg = primaryElbowAngle
        prevAngularVelocityDegS = angularVelocityDegS

        val fatigueDetected = (lastVelocityLossPercent ?: 0.0) >= VL_CRITICAL_PERCENT

        val alert = fatigueDetected ||
                lastTechnicalError != ErrorLevel.NONE ||
                currentStickingPeriodDetected

        val liveMinElbow = if (phase != RepPhase.IDLE && currentMinElbowAngleDeg != Double.MAX_VALUE) {
            currentMinElbowAngleDeg
        } else null
        val liveMaxElbow = if (phase == RepPhase.ASCENT && currentRepTopElbowAngleDeg > 0.0) {
            currentRepTopElbowAngleDeg
        } else null

        return AlgorithmResult(
            angleDeg = primaryElbowAngle,
            angularVelocity = angularVelocityDegS,
            angularAcceleration = angularAccelerationDegS2,
            fatigueDetected = fatigueDetected,
            fatigueReason = buildFatigueReason(),
            technicalError = lastTechnicalError,
            errorMagnitude = lastErrorMagnitude,
            alert = alert,
            algorithmName = "BenchPressBiomechanics",
            concentricPeakVelocityDegS = if (currentPeakConcentricVelocityDegS > 0.0) currentPeakConcentricVelocityDegS else null,
            eccentricPeakVelocityDegS = if (currentPeakEccentricVelocityDegS > 0.0) currentPeakEccentricVelocityDegS else null,
            velocityLossPercent = lastVelocityLossPercent,
            repCount = repCount,
            attemptedRepCount = attemptCount,
            elbowAngleDeg = primaryElbowAngle,
            leftElbowAngleDeg = leftElbowAngle,
            rightElbowAngleDeg = rightElbowAngle,
            shoulderAbductionDeg = worstAbduction,
            gripWidthRatio = gripWidthRatio,
            bilateralAsymmetryDeg = bilateralAsymmetryDeg,
            extensionIncompleteDeg = lastExtensionIncompleteDeg,
            stickingPeriodDetected = currentStickingPeriodDetected,
            gripTooWide = lastGripTooWide,
            shoulderAbductionRisk = lastShoulderAbductionRisk,
            bilateralAsymmetry = lastBilateralAsymmetry,
            depthInsufficientBench = lastDepthInsufficientBench,
            extensionIncomplete = lastExtensionIncomplete,
            currentRepMinElbowAngleDeg = liveMinElbow,
            currentRepMaxElbowAngleDeg = liveMaxElbow,
            elbowBelowTorsoLive = currentDepthCheckVisible,
            extensionCompleteLive = currentExtensionCheckVisible,
            readinessReady = readinessState == ReadinessState.READY,
            lastRepMinElbowAngleDeg = snapLastRepMinElbow,
            lastRepBenchRomDeg = snapLastRepRom,
            lastRepConcentricPeakVelocityDegS = snapLastRepConcVel,
            lastRepBilateralAsymmetryDeg = snapLastRepBilateralAsymmetryDeg,
            lastRepShoulderAbductionDeg = snapLastRepShoulderAbductionDeg,
            lastRepGripWidthRatio = snapLastRepGripWidthRatio,
            lastRepExtensionIncompleteDeg = snapLastRepExtensionIncompleteDeg,
            lastRepStickingPeriodDetected = snapLastRepStickingPeriodDetected,
            lastRepGripTooWide = snapLastRepGripTooWide,
            lastRepBilateralAsymmetry = snapLastRepBilateralAsymmetry,
            lastRepDepthInsufficientBench = snapLastRepDepthInsufficientBench,
            lastRepExtensionIncomplete = snapLastRepExtensionIncomplete,
            lastRepFormQuality = snapLastRepFormQuality,
            calibrationCommitted = calibrator.state == BenchPressCalibrator.State.COMMITTED,
            calibrationDebug = if (BenchPressDebugSwitches.emitDebugMap &&
                calibrator.state == BenchPressCalibrator.State.COMMITTED
            ) t.toDebugMap() else null
        )
    }

    /**
     * Resetea el estado interno del algoritmo, incluyendo la calibración si está habilitada, y todas las métricas y máquinas de estado relacionadas 
     * con la detección de repeticiones, errores técnicos, asimetría, abducción de hombros, períodos de sticking, etc. Esto se llama típicamente al iniciar 
     * una nueva sesión de entrenamiento o al reiniciar la aplicación.
     */
    override fun reset() {
        calibrator.reset(enabled = BenchPressDebugSwitches.calibrationEnabled)
        lastCalibrationCommittedReported = false
        prevElbowAngleDeg = null
        prevAngularVelocityDegS = null
        smoothedAngularVelocityDegS = null
        phase = RepPhase.IDLE

        readinessState = ReadinessState.NOT_READY
        stableFrameCount = 0
        recentAngles.clear()

        asymmetryConsecutiveFrames = 0
        abductionWarningConsecutiveFrames = 0
        abductionCriticalConsecutiveFrames = 0
        asymmetryWindow.clear()

        currentMinElbowAngleDeg = Double.MAX_VALUE
        currentRepStartElbowAngleDeg = Double.MAX_VALUE
        currentPeakConcentricVelocityDegS = 0.0
        currentPeakEccentricVelocityDegS = 0.0
        currentRepElbowWentBelowTorso = false
        currentRepTopElbowAngleDeg = 0.0
        currentDepthCheckVisible = false
        currentExtensionCheckVisible = false
        currentRepWorstShoulderAbductionDeg = null
        currentRepShoulderAbductionRisk = false
        currentRepShoulderAbductionCritical = false

        stickingStartMs = null
        currentStickingPeriodDetected = false

        lastDepthInsufficientBench = false
        lastExtensionIncomplete = false
        lastExtensionIncompleteDeg = null
        lastGripTooWide = false
        lastShoulderAbductionRisk = false
        lastShoulderAbductionDeg = null
        lastBilateralAsymmetry = false
        lastBilateralAsymmetryDeg = null
        lastVelocityLossPercent = null
        lastTechnicalError = ErrorLevel.NONE
        lastErrorMagnitude = null
        repCount = 0
        attemptCount = 0

        concentricVelocityByRep.clear()

        lastGripWidthRatioMeasured = null
        snapLastRepMinElbow = null
        snapLastRepRom = null
        snapLastRepConcVel = null
        snapLastRepBilateralAsymmetryDeg = null
        snapLastRepShoulderAbductionDeg = null
        snapLastRepGripWidthRatio = null
        snapLastRepExtensionIncompleteDeg = null
        snapLastRepStickingPeriodDetected = false
        snapLastRepGripTooWide = false
        snapLastRepBilateralAsymmetry = false
        snapLastRepDepthInsufficientBench = false
        snapLastRepExtensionIncomplete = false
        snapLastRepFormQuality = null
    }

    /**
     * Actualiza la máquina de estados de readiness basada en la estabilidad del ángulo de codo en las últimas frames y la visibilidad de ambos brazos. 
     * Si ambos brazos no son visibles, se degrada inmediatamente el estado a NOT_READY. Si los brazos son visibles, se calcula la desviación estándar de los 
     * ángulos recientes: si es menor que READY_ANGLE_STD_MAX_DEG, se considera estable. Para pasar de NOT_READY a STABILIZING se requiere estabilidad, y para pasar 
     * de STABILIZING a READY se requieren READY_STABLE_FRAMES consecutivas estables. Cualquier frame inestable resetea el conteo y puede degradar el estado a NOT_READY.
     */
    private fun updateReadiness(currentElbowAngleDeg: Double, bothArmsVisible: Boolean) {
        if (!bothArmsVisible) {
            degradeReadiness()
            return
        }

        if (recentAngles.size >= READY_WINDOW_FRAMES) recentAngles.removeFirst()
        recentAngles.addLast(currentElbowAngleDeg)

        val stdDev = if (recentAngles.size >= READY_WINDOW_FRAMES) angleStdDev(recentAngles) else Double.MAX_VALUE
        val stable = stdDev < READY_ANGLE_STD_MAX_DEG

        when (readinessState) {
            ReadinessState.NOT_READY -> {
                if (stable) {
                    readinessState = ReadinessState.STABILIZING
                    stableFrameCount = 1
                } else {
                    stableFrameCount = 0
                }
            }

            ReadinessState.STABILIZING -> {
                if (stable) {
                    stableFrameCount += 1
                    if (stableFrameCount >= READY_STABLE_FRAMES) {
                        readinessState = ReadinessState.READY
                    }
                } else {
                    readinessState = ReadinessState.NOT_READY
                    stableFrameCount = 0
                }
            }

            ReadinessState.READY -> {           
            }
        }
    }

    /**
     * Resetea el estado de readiness a NOT_READY, limpiando el conteo de frames estables y los ángulos recientes. Esto se llama típicamente cuando 
     * se pierde la visibilidad de ambos brazos,
     */
    private fun degradeReadiness() {
        readinessState = ReadinessState.NOT_READY
        stableFrameCount = 0
        recentAngles.clear()
    }

    /**
     * Calcula la desviación estándar de una colección de valores angulares en grados. Esto se utiliza para evaluar la estabilidad del ángulo de 
     * codo en las últimas frames,
     */
    private fun angleStdDev(values: Collection<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    /**
     * Actualiza la detección de asimetría bilateral utilizando un enfoque de debounce: si el porcentaje de asimetría supera SYMMETRY_ALERT_THRESHOLD_PERCENT, se 
     * incrementa un contador de frames consecutivos. Si el porcentaje cae por debajo del umbral, se resetea el contador. Se considera que hay una asimetría 
     * significativa solo si el contador alcanza POSTURAL_DEBOUNCE_FRAMES, lo que ayuda a evitar alertas falsas por fluctuaciones momentáneas en la posición de 
     * las manos o errores de detección.
     */
    private fun updateAsymmetryDebounced(currentPercent: Double): Boolean {
        if (currentPercent >= SYMMETRY_ALERT_THRESHOLD_PERCENT) {
            asymmetryConsecutiveFrames += 1
        } else {
            asymmetryConsecutiveFrames = 0
        }
        return asymmetryConsecutiveFrames >= POSTURAL_DEBOUNCE_FRAMES
    }

    /**
     * Actualiza la detección de riesgo de abducción de hombros utilizando un enfoque de debounce similar al de asimetría: si el peor ángulo de 
     * abducción supera los umbrales
     */
    private fun updateAbductionRiskDebounced(worstAbduction: Double?, elbowAngleDeg: Double): Boolean {
        val angle = worstAbduction ?: 0.0
        if (angle > t.abductionCriticalDeg) {
            abductionCriticalConsecutiveFrames += 1
            abductionWarningConsecutiveFrames += 1
        } else if (isAbductionOutsideOkRange(angle, elbowAngleDeg)) {
            abductionCriticalConsecutiveFrames = 0
            abductionWarningConsecutiveFrames += 1
        } else {
            abductionCriticalConsecutiveFrames = 0
            abductionWarningConsecutiveFrames = 0
        }
        return abductionWarningConsecutiveFrames >= POSTURAL_DEBOUNCE_FRAMES
    }

    private fun isAbductionCriticalDebounced(): Boolean =
        abductionCriticalConsecutiveFrames >= POSTURAL_DEBOUNCE_FRAMES

    private fun isAbductionOutsideOkRange(angle: Double, elbowAngleDeg: Double): Boolean =
        if (elbowAngleDeg < t.abductionDeepElbowDeg) {
            angle > t.abductionDeepMaxOkDeg
        } else {
            angle < t.abductionMinOkDeg || angle > t.abductionMaxOkDeg
        }

        /**
         * Actualiza los indicadores de reglas en vivo para profundidad y extensión basados en el ángulo actual del codo. Si el ángulo del codo cae por debajo del umbral
         * de profundidad durante la fase de descenso, se muestra el indicador de profundidad insuficiente. Si el ángulo del codo alcanza o supera el umbral de posición 
         * superior durante la fase de ascenso, se muestra el indicador de extensión incompleta. Ambos indicadores se resetean si el ángulo del codo se mueve 
         * significativamente en la dirección opuesta, lo que permite una retroalimentación en vivo sin que los indicadores queden atascados por fluctuaciones momentáneas 
         * en el ángulo del codo.
         */
    private fun updateLiveRuleIndicators(elbowAngleDeg: Double) {
        if (phase != RepPhase.IDLE && elbowAngleDeg <= t.maxValidBottomAngleDeg) {
            currentRepElbowWentBelowTorso = true
            currentDepthCheckVisible = true
        }
        if (currentDepthCheckVisible &&
            elbowAngleDeg >= t.maxValidBottomAngleDeg + LIVE_CHECK_RESET_DELTA_DEG
        ) {
            currentDepthCheckVisible = false
        }

        if (phase != RepPhase.IDLE && elbowAngleDeg >= t.topPositionAngleDeg) {
            currentExtensionCheckVisible = true
        }
        if (currentExtensionCheckVisible &&
            elbowAngleDeg <= t.topPositionAngleDeg - LIVE_CHECK_RESET_DELTA_DEG
        ) {
            currentExtensionCheckVisible = false
        }
    }

    /**
     * Actualiza la máquina de estados de repetición basada en el ángulo del codo y su velocidad angular. En IDLE, detecta el inicio de la fase de descenso 
     * cuando el ángulo del codo cae por debajo del umbral y la velocidad angular es negativa. En DESCENT, actualiza el ángulo mínimo alcanzado y 
     * la velocidad máxima excéntrica, y detecta la transición a ASCENT cuando la velocidad angular se vuelve positiva. En ASCENT, actualiza el ángulo mínimo, 
     * la velocidad máxima concéntrica, y el ángulo máximo alcanzado durante el ascenso, y detecta la finalización de la repetición cuando el ángulo del codo 
     * alcanza o supera el umbral de posición superior y la velocidad angular es baja. Esta máquina de estados permite segmentar claramente cada repetición y 
     * calcular métricas específicas para cada fase del movimiento.
     */
    private fun updateRepState(elbowAngleDeg: Double, angularVelocityDegS: Double) {
        when (phase) {
            RepPhase.IDLE -> {
                if (elbowAngleDeg < t.startDescentAngleDeg && angularVelocityDegS < -VELOCITY_HYSTERESIS) {
                    phase = RepPhase.DESCENT
                    attemptCount++
                    startNewRepTracking(elbowAngleDeg)
                    currentPeakEccentricVelocityDegS = maxOf(currentPeakEccentricVelocityDegS, -angularVelocityDegS)
                }
            }

            RepPhase.DESCENT -> {
                currentMinElbowAngleDeg = minOf(currentMinElbowAngleDeg, elbowAngleDeg)

                if (angularVelocityDegS < 0.0) {
                    currentPeakEccentricVelocityDegS = maxOf(currentPeakEccentricVelocityDegS, -angularVelocityDegS)
                }

                if (angularVelocityDegS > VELOCITY_HYSTERESIS) {
                    phase = RepPhase.ASCENT
                    currentPeakConcentricVelocityDegS = maxOf(currentPeakConcentricVelocityDegS, angularVelocityDegS)
                    stickingStartMs = null
                    currentStickingPeriodDetected = false
                }
            }

            RepPhase.ASCENT -> {
                currentMinElbowAngleDeg = minOf(currentMinElbowAngleDeg, elbowAngleDeg)

                if (angularVelocityDegS > 0.0) {
                    currentPeakConcentricVelocityDegS = maxOf(currentPeakConcentricVelocityDegS, angularVelocityDegS)
                }
              
                currentRepTopElbowAngleDeg = maxOf(currentRepTopElbowAngleDeg, elbowAngleDeg)

                if (elbowAngleDeg >= t.topPositionAngleDeg && angularVelocityDegS > -VELOCITY_HYSTERESIS) {
                    completeRep()
                    phase = RepPhase.IDLE
                }
            }
        }
    }

    /**
     * Inicializa el seguimiento de una nueva repetición al establecer el ángulo de inicio del codo, resetear el ángulo mínimo alcanzado, 
     * las velocidades máximas concéntrica y excéntrica,
     */
    private fun startNewRepTracking(elbowAngleDeg: Double) {
        currentRepStartElbowAngleDeg = elbowAngleDeg
        currentMinElbowAngleDeg = elbowAngleDeg
        currentPeakConcentricVelocityDegS = 0.0
        currentPeakEccentricVelocityDegS = 0.0
        currentRepElbowWentBelowTorso = false
        currentRepTopElbowAngleDeg = 0.0
        currentDepthCheckVisible = false
        currentRepWorstShoulderAbductionDeg = null
        currentRepShoulderAbductionRisk = false
        currentRepShoulderAbductionCritical = false
        stickingStartMs = null
        currentStickingPeriodDetected = false
    }

    /**
     * Completa una repetición al evaluar el rango de movimiento, la profundidad, la extensión, la abducción de hombros, la asimetría bilateral, 
     * la pérdida de velocidad, y otros errores técnicos.
     */
    private fun completeRep() {
        if (currentMinElbowAngleDeg == Double.MAX_VALUE) return

        val repRomDeg = currentRepStartElbowAngleDeg - currentMinElbowAngleDeg
        val isValidRep = repRomDeg >= MIN_VALID_ROM_DEG
                && currentMinElbowAngleDeg <= t.maxValidBottomAngleDeg
                && currentMinElbowAngleDeg >= t.minValidBottomAngleDeg

        if (!isValidRep) return

        repCount += 1

        lastDepthInsufficientBench = !currentRepElbowWentBelowTorso

        lastExtensionIncomplete = currentRepTopElbowAngleDeg < t.fullExtensionMinDeg
        lastExtensionIncompleteDeg = if (lastExtensionIncomplete) {
            t.fullExtensionMinDeg - currentRepTopElbowAngleDeg
        } else {
            null
        }

        if (currentPeakConcentricVelocityDegS > 0.0) {
            concentricVelocityByRep.add(currentPeakConcentricVelocityDegS)
            val vRef = concentricVelocityByRep.firstOrNull() ?: currentPeakConcentricVelocityDegS
            if (vRef > 0.0) {
                val rawLoss = ((vRef - currentPeakConcentricVelocityDegS) / vRef) * 100.0
                lastVelocityLossPercent = maxOf(0.0, rawLoss)
            }
        }

        val abduction = currentRepWorstShoulderAbductionDeg ?: lastShoulderAbductionDeg ?: 0.0
        val velocityLoss = lastVelocityLossPercent ?: 0.0

        lastTechnicalError = when {
            currentRepShoulderAbductionCritical -> ErrorLevel.SEVERE
            velocityLoss >= VL_CRITICAL_PERCENT -> ErrorLevel.SEVERE
            lastDepthInsufficientBench && lastExtensionIncomplete -> ErrorLevel.SEVERE
            currentRepShoulderAbductionRisk -> ErrorLevel.MODERATE
            lastBilateralAsymmetry -> ErrorLevel.MODERATE
            lastGripTooWide -> ErrorLevel.MODERATE
            velocityLoss >= VL_WARNING_PERCENT -> ErrorLevel.MODERATE
            lastDepthInsufficientBench || lastExtensionIncomplete -> ErrorLevel.MODERATE
            else -> ErrorLevel.NONE
        }

        val depthMagnitude = if (lastDepthInsufficientBench) 5.0 else 0.0
        val extensionMagnitude = lastExtensionIncompleteDeg ?: 0.0
        val abductionMagnitude = when {
            abduction > t.abductionMaxOkDeg -> abduction - t.abductionMaxOkDeg
            abduction < t.abductionMinOkDeg -> t.abductionMinOkDeg - abduction
            currentRepShoulderAbductionRisk && abduction > t.abductionDeepMaxOkDeg ->
                abduction - t.abductionDeepMaxOkDeg
            else -> 0.0
        }
        val asymmetryMagnitude = if (lastBilateralAsymmetry) (lastBilateralAsymmetryDeg ?: 0.0) - SYMMETRY_THRESHOLD_PERCENT else 0.0
        val vlMagnitude = if (velocityLoss >= VL_WARNING_PERCENT) velocityLoss - VL_WARNING_PERCENT else 0.0

        lastErrorMagnitude = listOf(depthMagnitude, extensionMagnitude, abductionMagnitude, asymmetryMagnitude, vlMagnitude)
            .maxOrNull()?.takeIf { it > 0.0 }

        snapLastRepMinElbow = currentMinElbowAngleDeg
        snapLastRepRom = repRomDeg
        snapLastRepConcVel = currentPeakConcentricVelocityDegS.takeIf { it > 0.0 }
        snapLastRepBilateralAsymmetryDeg = lastBilateralAsymmetryDeg
        snapLastRepShoulderAbductionDeg = currentRepWorstShoulderAbductionDeg
        snapLastRepGripWidthRatio = lastGripWidthRatioMeasured
        snapLastRepExtensionIncompleteDeg = lastExtensionIncompleteDeg
        snapLastRepStickingPeriodDetected = currentStickingPeriodDetected
        snapLastRepGripTooWide = lastGripTooWide
        snapLastRepBilateralAsymmetry = lastBilateralAsymmetry
        snapLastRepDepthInsufficientBench = lastDepthInsufficientBench
        snapLastRepExtensionIncomplete = lastExtensionIncomplete
        snapLastRepFormQuality = lastTechnicalError
    }

    /**
     * Agrega un nuevo valor a una ventana deslizante y devuelve la mediana de los valores en la ventana. Si la ventana ya tiene el tamaño máximo, 
     * se elimina el valor más antiguo antes de agregar el nuevo.
     */
    private fun pushAndMedian(window: ArrayDeque<Double>, value: Double, windowSize: Int): Double {
        if (window.size >= windowSize) window.removeFirst()
        window.addLast(value)
        val sorted = window.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /**
     * Construye una cadena descriptiva del motivo de fatiga basado en el porcentaje de pérdida de velocidad. Si no hay pérdida de velocidad, devuelve null. 
     * Si la pérdida es menor al umbral de advertencia, indica que la velocidad es estable. Si la pérdida supera el umbral de advertencia pero no el crítico, 
     * indica inicio de fatiga. Si supera el umbral crítico, indica fatiga crítica. Esta función proporciona una explicación legible para el usuario sobre por qué 
     * se detectó fatiga en la repetición actual.
     */
    private fun buildFatigueReason(): String? {
        val loss = lastVelocityLossPercent ?: return null
        return when {
            loss < 10.0 -> "Velocidad estable"
            loss < VL_WARNING_PERCENT -> "Inicio de fatiga (${format(loss)}% de perdida)"
            loss < VL_CRITICAL_PERCENT -> "Fatiga moderada - VL15 (${format(loss)}% de perdida)"
            else -> "Fatiga critica - VL25 (${format(loss)}% de perdida)"
        }
    }

    /**
     * Calcula el ángulo en grados entre tres puntos (a, b, c) proyectados en 2D (ignorando la coordenada z). El punto b se considera el vértice del ángulo,
     * y el ángulo se calcula entre los vectores ba y bc. El resultado se devuelve en grados y se limita al rango [0, 180]. Si alguno de los vectores tiene una magnitud
     * muy pequeña, se devuelve 0 para evitar inestabilidad numérica. Esta función se utiliza para calcular el ángulo del codo y la abducción de hombros en el plano 2D.
     */
    private fun computeAngle2D(a: Vec3, b: Vec3, c: Vec3): Double {
        val bax = a.x - b.x
        val bay = a.y - b.y
        val bcx = c.x - b.x
        val bcy = c.y - b.y

        val dot = bax * bcx + bay * bcy
        val magBa = sqrt(bax * bax + bay * bay)
        val magBc = sqrt(bcx * bcx + bcy * bcy)

        if (magBa < 1e-6 || magBc < 1e-6) return 0.0

        val cosValue = (dot / (magBa * magBc)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosValue))
    }

    private fun horizontalDistance(a: Vec3, b: Vec3): Double = abs(a.x - b.x)

    private fun verticalDistance(a: Vec3, b: Vec3): Double = abs(a.y - b.y)

    private fun getLandmark(flat: List<Double>, index: Int): Landmark {
        val base = index * 4
        return Landmark(
            vec = Vec3(flat[base], flat[base + 1], flat[base + 2]),
            visibility = flat[base + 3].toFloat()
        )
    }

    /**
     * Extrae los landmarks relevantes para un brazo específico (izquierdo o derecho) de una lista plana de landmarks. Para cada brazo, 
     * se extraen los landmarks del hombro, codo, muñeca y cadera, y se devuelve un objeto ArmLandmarks que contiene estos puntos y sus respectivas visibilidades. 
     * Esta función facilita el acceso a los puntos clave necesarios para calcular las métricas de biomecánica del press de banca, como el ángulo del codo, la abducción 
     * de hombros, la anchura del agarre, etc.
     */
    private fun getArmLandmarks(flat: List<Double>, side: Side): ArmLandmarks {
        return when (side) {
            Side.LEFT -> ArmLandmarks(
                shoulder = getLandmark(flat, IDX_SHOULDER_L),
                elbow = getLandmark(flat, IDX_ELBOW_L),
                wrist = getLandmark(flat, IDX_WRIST_L),
                hip = getLandmark(flat, IDX_HIP_L)
            )
            Side.RIGHT -> ArmLandmarks(
                shoulder = getLandmark(flat, IDX_SHOULDER_R),
                elbow = getLandmark(flat, IDX_ELBOW_R),
                wrist = getLandmark(flat, IDX_WRIST_R),
                hip = getLandmark(flat, IDX_HIP_R)
            )
        }
    }

    /**
     * Construye un resultado vacío con solo el nombre del algoritmo y los conteos de repeticiones, utilizado cuando no se pueden calcular métricas 
     * válidas debido a falta de visibilidad o inestabilidad en los ángulos. Esto permite que el sistema reporte que el algoritmo está activo y cuente las 
     * repeticiones intentadas, incluso si no se pueden proporcionar métricas detalladas en ese momento.
     */
    private fun emptyResult() = AlgorithmResult(
        algorithmName = "BenchPressBiomechanics",
        repCount = repCount,
        attemptedRepCount = attemptCount
    )

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    private enum class Side { LEFT, RIGHT }

    private data class Vec3(val x: Double, val y: Double, val z: Double)
    private data class Landmark(val vec: Vec3, val visibility: Float)

    private data class ArmLandmarks(
        val shoulder: Landmark,
        val elbow: Landmark,
        val wrist: Landmark,
        val hip: Landmark
    ) {
        fun allVisible(): Boolean = listOf(
            shoulder.visibility,
            elbow.visibility,
            wrist.visibility,
            hip.visibility
        ).all { it >= MIN_VISIBILITY }
    }
}
