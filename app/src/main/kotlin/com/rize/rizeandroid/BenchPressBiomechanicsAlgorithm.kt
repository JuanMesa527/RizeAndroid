package com.rize.rizeandroid

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * BenchPressBiomechanicsAlgorithm
 *
 * Implementa las reglas de correccion postural y prediccion de fatiga
 * para press de banca segun la seccion 8.3.1.1 del documento de tesis.
 *
 * Reglas de correccion postural:
 *   1. Ancho de agarre: distancia entre munecas <= 1.5x ancho biacromial
 *   2. Abduccion de hombro: angulo cadera-hombro-codo. Alerta >45, critico >90
 *   3. Simetria bilateral: |codo izq - codo der| <= 2.75
 *   4. Profundidad de descenso: codo debe bajar por debajo de la linea del torso
 *   5. Extension completa: angulo de codo debe alcanzar >= 176 en la cima
 *
 * Prediccion de fatiga/fallo:
 *   6. Periodo de estancamiento: velocidad ~0 durante >870ms en fase concentrica
 *   7. Perdida de velocidad: VL15 (advertencia), VL25 (critico)
 *
 * Vista de camara: FRONTAL (persona acostada en el banco, camara de frente)
 */
class BenchPressBiomechanicsAlgorithm : BiomechanicsAlgorithm {

    companion object {
        // MediaPipe landmark indices
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

        // ── Deteccion de repeticiones ────────────────────────────────────────
        private const val VELOCITY_HYSTERESIS = 8.0       // deg/s banda de ruido
        private const val START_DESCENT_ANGLE = 160.0     // angulo codo para iniciar descenso
        private const val TOP_POSITION_ANGLE = 165.0      // angulo codo para confirmar rep completa

        // ── Regla 1: Ancho de agarre ────────────────────────────────────────
        private const val GRIP_WIDTH_RATIO_MAX = 1.5

        // ── Regla 2: Abduccion de hombro ────────────────────────────────────
        private const val ABDUCTION_WARNING_DEG = 45.0
        private const val ABDUCTION_CRITICAL_DEG = 90.0

        // ── Regla 3: Simetria bilateral ─────────────────────────────────────
        private const val SYMMETRY_THRESHOLD_DEG = 2.75

        // ── Regla 5: Extension completa ─────────────────────────────────────
        private const val FULL_EXTENSION_MIN_DEG = 176.0

        // ── Regla 6: Periodo de estancamiento ───────────────────────────────
        private const val STICKING_VELOCITY_THRESHOLD = 5.0  // deg/s, "cerca de cero"
        private const val STICKING_DURATION_MS = 870L

        // ── Regla 7: Perdida de velocidad ───────────────────────────────────
        private const val VL_WARNING_PERCENT = 15.0
        private const val VL_CRITICAL_PERCENT = 25.0

        // ── Validacion de rep ────────────────────────────────────────────────
        private const val MIN_VALID_ROM_DEG = 30.0
        private const val MAX_VALID_BOTTOM_ANGLE_DEG = 120.0
        private const val MIN_VALID_BOTTOM_ANGLE_DEG = 20.0
    }

    private enum class RepPhase { IDLE, DESCENT, ASCENT }

    // ── Estado por frame ─────────────────────────────────────────────────────
    private var prevElbowAngleDeg: Double? = null
    private var prevAngularVelocityDegS: Double? = null

    // ── Maquina de estados ───────────────────────────────────────────────────
    private var phase: RepPhase = RepPhase.IDLE

    // ── Tracking por rep ─────────────────────────────────────────────────────
    private var currentMinElbowAngleDeg = Double.MAX_VALUE
    private var currentRepStartElbowAngleDeg = Double.MAX_VALUE
    private var currentPeakConcentricVelocityDegS = 0.0
    private var currentPeakEccentricVelocityDegS = 0.0
    private var currentRepElbowWentBelowTorso = false
    private var currentRepTopElbowAngleDeg = 0.0

    // ── Sticking period (Regla 6) ────────────────────────────────────────────
    private var stickingStartMs: Long? = null
    private var currentStickingPeriodDetected = false

    // ── Estado persistente entre reps ────────────────────────────────────────
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

    // ── Historial de velocidad ───────────────────────────────────────────────
    private val concentricVelocityByRep = mutableListOf<Double>()

    // ═══════════════════════════════════════════════════════════════════════════
    // process()
    // ═══════════════════════════════════════════════════════════════════════════

    override fun process(landmarkFlatList: List<Double>): AlgorithmResult {
        if (landmarkFlatList.size < 132) return emptyResult()

        // Extraer landmarks de ambos lados
        val leftArm = getArmLandmarks(landmarkFlatList, Side.LEFT)
        val rightArm = getArmLandmarks(landmarkFlatList, Side.RIGHT)

        val leftVisible = leftArm.allVisible()
        val rightVisible = rightArm.allVisible()

        if (!leftVisible && !rightVisible) return emptyResult()

        // Calcular angulos de codo bilaterales
        val leftElbowAngle = if (leftVisible) computeAngle(leftArm.shoulder.vec, leftArm.elbow.vec, leftArm.wrist.vec) else null
        val rightElbowAngle = if (rightVisible) computeAngle(rightArm.shoulder.vec, rightArm.elbow.vec, rightArm.wrist.vec) else null

        // Angulo primario = promedio de ambos lados (o el disponible)
        val primaryElbowAngle = when {
            leftElbowAngle != null && rightElbowAngle != null -> (leftElbowAngle + rightElbowAngle) / 2.0
            leftElbowAngle != null -> leftElbowAngle
            rightElbowAngle != null -> rightElbowAngle
            else -> return emptyResult()
        }

        // Velocidad y aceleracion angular
        val angularVelocityDegS = prevElbowAngleDeg?.let { (primaryElbowAngle - it) / DT }
        val angularAccelerationDegS2 = if (angularVelocityDegS != null && prevAngularVelocityDegS != null) {
            (angularVelocityDegS - prevAngularVelocityDegS!!) / DT
        } else {
            null
        }

        // ── Reglas por frame ─────────────────────────────────────────────────

        // Regla 1: Ancho de agarre
        var gripWidthRatio: Double? = null
        if (leftVisible && rightVisible) {
            val wristDist = distance(leftArm.wrist.vec, rightArm.wrist.vec)
            val biacromialDist = distance(leftArm.shoulder.vec, rightArm.shoulder.vec)
            if (biacromialDist > 1e-6) {
                gripWidthRatio = wristDist / biacromialDist
                lastGripTooWide = gripWidthRatio > GRIP_WIDTH_RATIO_MAX
            }
        }

        // Regla 2: Abduccion de hombro (angulo cadera-hombro-codo)
        val abductionLeft = if (leftVisible) computeAngle(leftArm.hip.vec, leftArm.shoulder.vec, leftArm.elbow.vec) else null
        val abductionRight = if (rightVisible) computeAngle(rightArm.hip.vec, rightArm.shoulder.vec, rightArm.elbow.vec) else null
        val worstAbduction = listOfNotNull(abductionLeft, abductionRight).maxOrNull()
        lastShoulderAbductionDeg = worstAbduction
        lastShoulderAbductionRisk = (worstAbduction ?: 0.0) > ABDUCTION_WARNING_DEG

        // Regla 3: Simetria bilateral
        var bilateralAsymmetryDeg: Double? = null
        if (leftElbowAngle != null && rightElbowAngle != null) {
            bilateralAsymmetryDeg = abs(leftElbowAngle - rightElbowAngle)
            lastBilateralAsymmetryDeg = bilateralAsymmetryDeg
            lastBilateralAsymmetry = bilateralAsymmetryDeg > SYMMETRY_THRESHOLD_DEG
        }

        // Regla 4: Profundidad (evaluar por frame durante descenso)
        if (phase == RepPhase.DESCENT) {
            if (!currentRepElbowWentBelowTorso) {
                currentRepElbowWentBelowTorso = checkElbowBelowTorso(leftArm, rightArm, leftVisible, rightVisible)
            }
        }

        // Regla 6: Sticking period (durante ascenso)
        if (phase == RepPhase.ASCENT && angularVelocityDegS != null) {
            if (abs(angularVelocityDegS) < STICKING_VELOCITY_THRESHOLD) {
                if (stickingStartMs == null) {
                    stickingStartMs = System.currentTimeMillis()
                } else {
                    val elapsed = System.currentTimeMillis() - stickingStartMs!!
                    if (elapsed > STICKING_DURATION_MS) {
                        currentStickingPeriodDetected = true
                    }
                }
            } else {
                stickingStartMs = null
            }
        } else {
            stickingStartMs = null
        }

        // Actualizar maquina de estados de rep
        if (angularVelocityDegS != null) {
            updateRepState(primaryElbowAngle, angularVelocityDegS)
        }

        prevElbowAngleDeg = primaryElbowAngle
        prevAngularVelocityDegS = angularVelocityDegS

        val fatigueDetected = (lastVelocityLossPercent ?: 0.0) >= VL_CRITICAL_PERCENT
        val alert = fatigueDetected || lastTechnicalError != ErrorLevel.NONE || currentStickingPeriodDetected

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
            // Bench press specific
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
            extensionIncomplete = lastExtensionIncomplete
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // reset()
    // ═══════════════════════════════════════════════════════════════════════════

    override fun reset() {
        prevElbowAngleDeg = null
        prevAngularVelocityDegS = null
        phase = RepPhase.IDLE

        currentMinElbowAngleDeg = Double.MAX_VALUE
        currentRepStartElbowAngleDeg = Double.MAX_VALUE
        currentPeakConcentricVelocityDegS = 0.0
        currentPeakEccentricVelocityDegS = 0.0
        currentRepElbowWentBelowTorso = false
        currentRepTopElbowAngleDeg = 0.0

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

        concentricVelocityByRep.clear()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Maquina de estados de repeticion
    // ═══════════════════════════════════════════════════════════════════════════

    private fun updateRepState(elbowAngleDeg: Double, angularVelocityDegS: Double) {
        when (phase) {
            RepPhase.IDLE -> {
                if (elbowAngleDeg < START_DESCENT_ANGLE && angularVelocityDegS < -VELOCITY_HYSTERESIS) {
                    phase = RepPhase.DESCENT
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
                    // Reset sticking period al iniciar ascenso
                    stickingStartMs = null
                    currentStickingPeriodDetected = false
                }
            }

            RepPhase.ASCENT -> {
                currentMinElbowAngleDeg = minOf(currentMinElbowAngleDeg, elbowAngleDeg)

                if (angularVelocityDegS > 0.0) {
                    currentPeakConcentricVelocityDegS = maxOf(currentPeakConcentricVelocityDegS, angularVelocityDegS)
                }

                // Rastrear el angulo maximo alcanzado en la cima (para Regla 5)
                currentRepTopElbowAngleDeg = maxOf(currentRepTopElbowAngleDeg, elbowAngleDeg)

                if (elbowAngleDeg >= TOP_POSITION_ANGLE && angularVelocityDegS > -VELOCITY_HYSTERESIS) {
                    completeRep()
                    phase = RepPhase.IDLE
                }
            }
        }
    }

    private fun startNewRepTracking(elbowAngleDeg: Double) {
        currentRepStartElbowAngleDeg = elbowAngleDeg
        currentMinElbowAngleDeg = elbowAngleDeg
        currentPeakConcentricVelocityDegS = 0.0
        currentPeakEccentricVelocityDegS = 0.0
        currentRepElbowWentBelowTorso = false
        currentRepTopElbowAngleDeg = 0.0
        stickingStartMs = null
        currentStickingPeriodDetected = false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // completeRep() — evaluacion de rep completada
    // ═══════════════════════════════════════════════════════════════════════════

    private fun completeRep() {
        if (currentMinElbowAngleDeg == Double.MAX_VALUE) return

        val repRomDeg = currentRepStartElbowAngleDeg - currentMinElbowAngleDeg
        val isValidRep = repRomDeg >= MIN_VALID_ROM_DEG
                && currentMinElbowAngleDeg <= MAX_VALID_BOTTOM_ANGLE_DEG
                && currentMinElbowAngleDeg >= MIN_VALID_BOTTOM_ANGLE_DEG

        if (!isValidRep) return

        repCount += 1

        // Regla 4: Profundidad de descenso
        lastDepthInsufficientBench = !currentRepElbowWentBelowTorso

        // Regla 5: Extension completa
        lastExtensionIncomplete = currentRepTopElbowAngleDeg < FULL_EXTENSION_MIN_DEG
        lastExtensionIncompleteDeg = if (lastExtensionIncomplete) {
            FULL_EXTENSION_MIN_DEG - currentRepTopElbowAngleDeg
        } else {
            null
        }

        // Regla 7: Perdida de velocidad
        if (currentPeakConcentricVelocityDegS > 0.0) {
            concentricVelocityByRep.add(currentPeakConcentricVelocityDegS)
            val vRef = concentricVelocityByRep.firstOrNull() ?: currentPeakConcentricVelocityDegS
            if (vRef > 0.0) {
                val rawLoss = ((vRef - currentPeakConcentricVelocityDegS) / vRef) * 100.0
                lastVelocityLossPercent = maxOf(0.0, rawLoss)
            }
        }

        // Clasificacion de error tecnico
        val abduction = lastShoulderAbductionDeg ?: 0.0
        val velocityLoss = lastVelocityLossPercent ?: 0.0

        lastTechnicalError = when {
            abduction > ABDUCTION_CRITICAL_DEG -> ErrorLevel.SEVERE
            velocityLoss >= VL_CRITICAL_PERCENT -> ErrorLevel.SEVERE
            lastDepthInsufficientBench && lastExtensionIncomplete -> ErrorLevel.SEVERE
            abduction > ABDUCTION_WARNING_DEG -> ErrorLevel.MODERATE
            lastBilateralAsymmetry -> ErrorLevel.MODERATE
            lastGripTooWide -> ErrorLevel.MODERATE
            velocityLoss >= VL_WARNING_PERCENT -> ErrorLevel.MODERATE
            lastDepthInsufficientBench || lastExtensionIncomplete -> ErrorLevel.MODERATE
            else -> ErrorLevel.NONE
        }

        // Magnitud del error (el mayor de los errores activos)
        val depthMagnitude = if (lastDepthInsufficientBench) 5.0 else 0.0
        val extensionMagnitude = lastExtensionIncompleteDeg ?: 0.0
        val abductionMagnitude = if (abduction > ABDUCTION_WARNING_DEG) abduction - ABDUCTION_WARNING_DEG else 0.0
        val asymmetryMagnitude = if (lastBilateralAsymmetry) (lastBilateralAsymmetryDeg ?: 0.0) - SYMMETRY_THRESHOLD_DEG else 0.0
        val vlMagnitude = if (velocityLoss >= VL_WARNING_PERCENT) velocityLoss - VL_WARNING_PERCENT else 0.0

        lastErrorMagnitude = listOf(depthMagnitude, extensionMagnitude, abductionMagnitude, asymmetryMagnitude, vlMagnitude)
            .maxOrNull()?.takeIf { it > 0.0 }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Regla 4: Profundidad — codo por debajo de la linea del torso
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * En vista frontal con persona acostada, verifica si el codo esta
     * por debajo de la linea hombro-cadera (torso).
     * En coordenadas MediaPipe normalizadas, Y=0 arriba, Y=1 abajo.
     * El codo "abajo del torso" significa que su Y es mayor que la Y
     * interpolada de la linea hombro-cadera en esa posicion lateral.
     */
    private fun checkElbowBelowTorso(
        leftArm: ArmLandmarks,
        rightArm: ArmLandmarks,
        leftVisible: Boolean,
        rightVisible: Boolean
    ): Boolean {
        val leftBelow = if (leftVisible) {
            isPointBelowLine(leftArm.elbow.vec, leftArm.shoulder.vec, leftArm.hip.vec)
        } else false

        val rightBelow = if (rightVisible) {
            isPointBelowLine(rightArm.elbow.vec, rightArm.shoulder.vec, rightArm.hip.vec)
        } else false

        return leftBelow || rightBelow
    }

    /**
     * Verifica si el punto P esta por debajo de la linea A-B.
     * "Por debajo" en coordenadas de imagen = Y mayor.
     * Interpola la Y de la linea A-B en la posicion X del punto P.
     */
    private fun isPointBelowLine(point: Vec3, lineA: Vec3, lineB: Vec3): Boolean {
        val dx = lineB.x - lineA.x
        if (abs(dx) < 1e-6) {
            // Linea vertical: comparar Y directamente con el promedio
            return point.y > (lineA.y + lineB.y) / 2.0
        }
        val t = (point.x - lineA.x) / dx
        val interpolatedY = lineA.y + t * (lineB.y - lineA.y)
        return point.y > interpolatedY
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utilidades
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildFatigueReason(): String? {
        val loss = lastVelocityLossPercent ?: return null
        return when {
            loss < 10.0 -> "Velocidad estable"
            loss < VL_WARNING_PERCENT -> "Inicio de fatiga (${format(loss)}% de perdida)"
            loss < VL_CRITICAL_PERCENT -> "Fatiga moderada - VL15 (${format(loss)}% de perdida)"
            else -> "Fatiga critica - VL25 (${format(loss)}% de perdida)"
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

    private fun distance(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun getLandmark(flat: List<Double>, index: Int): Landmark {
        val base = index * 4
        return Landmark(
            vec = Vec3(flat[base], flat[base + 1], flat[base + 2]),
            visibility = flat[base + 3].toFloat()
        )
    }

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

    private fun emptyResult() = AlgorithmResult(algorithmName = "BenchPressBiomechanics")

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    // ── Tipos internos ───────────────────────────────────────────────────────

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
