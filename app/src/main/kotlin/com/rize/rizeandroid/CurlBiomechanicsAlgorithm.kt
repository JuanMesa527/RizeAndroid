package com.rize.rizeandroid

import kotlin.math.*

/**
 * CurlBiomechanicsAlgorithm
 *
 * Basado estrictamente en:
 * - Documento "Medición de variables cinemáticas" (§1-§9)
 * - Serbest (2022) "A Biomechanical Analysis of Dumbbell Curl..."
 *
 * Datos clave del paper (Serbest 2022, Figure 5):
 * - El curl va de ~0° a ~130° en coordenadas relativas
 * - En coordenadas MediaPipe (ángulo absoluto entre 3 puntos):
 *     brazo extendido  = ~160-175°
 *     máxima flexión   = ~30-50°
 * - Velocidad angular pico: ~150 deg/s = ~2.6 rad/s (Figure 5, bottom)
 * - 3 repeticiones por sesión como mínimo (paper methodology)
 * - Captura a 30 Hz (paper methodology)
 */
class CurlBiomechanicsAlgorithm : BiomechanicsAlgorithm {

    companion object {
        private const val IDX_SHOULDER_R = 12
        private const val IDX_ELBOW_R    = 14
        private const val IDX_WRIST_R    = 16
        private const val IDX_SHOULDER_L = 11
        private const val IDX_ELBOW_L    = 13
        private const val IDX_WRIST_L    = 15

        private const val SAMPLE_RATE_HZ = 30.0   // paper: "30 Hz image frequency"
        private const val DT = 1.0 / SAMPLE_RATE_HZ

        // ── §7 Fatiga — umbrales basados en Serbest (2022) Figure 5 ──────────
        //
        // Paper Figure 5 (bottom): velocidad angular pico ~150 deg/s ≈ 2.6 rad/s
        // Fatiga = cuando la velocidad cae significativamente respecto al pico.
        // Usamos 30% del pico observado = ~0.8 rad/s como ω_ref (§7 documento)
        //
        // MOVEMENT_THRESHOLD: por debajo de este valor el brazo está en reposo
        // entre repeticiones — NO se penaliza (no es fatiga, es pausa normal)
        private const val OMEGA_REF          = 0.8    // rad/s — §7: ω(t) < ω_ref
        private const val MOVEMENT_THRESHOLD = 0.2    // rad/s — umbral reposo/movimiento

        // Varianza §7: ventana de 60 frames (~2s a 30 Hz)
        // VAR_REF basado en variabilidad natural del movimiento del paper
        private const val VARIANCE_WINDOW = 60
        private const val VAR_REF         = 30.0   // deg² — umbral Var(θ) > Var_ref

        // Inestabilidad dinámica §7: dω/dt irregular
        // Paper Figure 5 muestra aceleración suave entre reps, picos bruscos = inestabilidad
        private const val ALPHA_INSTABILITY = 100.0  // rad/s²

        // ── §8 Error técnico ──────────────────────────────────────────────────
        //
        // θ_ref = DINÁMICO, calculado como promedio de los picos de flexión
        // de las primeras MIN_REPS_FOR_REF repeticiones del usuario.
        // Paper methodology: "subject completed 3 repetitions for each load"
        // → usamos 3 reps como mínimo para establecer el patrón de referencia.
        private const val MIN_REPS_FOR_REF = 3

        // δ1 y δ2 del documento §8
        // Paper Figure 5: variabilidad natural entre reps ~5-10°
        // → δ1=15° es el margen aceptable, δ2=30° es error significativo
        private const val DELTA_1 = 15.0   // deg — error leve
        private const val DELTA_2 = 30.0   // deg — error moderado → severo

        // ── Detección de repeticiones ─────────────────────────────────────────
        //
        // Paper Figure 5 (top): en coordenadas MediaPipe (ángulo absoluto)
        //   - Brazo extendido (valle del curl)  : ángulo ALTO (~160-175°)
        //   - Máxima flexión  (pico del curl)   : ángulo BAJO  (~30-50°)
        //
        // Una rep completa = ángulo baja hasta FLEXION_PEAK_THRESHOLD
        //                    y luego vuelve a subir hasta EXTENSION_VALLEY_THRESHOLD
        private const val FLEXION_PEAK_THRESHOLD      = 80.0   // deg — máxima flexión detectada
        private const val EXTENSION_VALLEY_THRESHOLD  = 140.0  // deg — brazo extendido detectado

        private const val MIN_VISIBILITY = 0.5f
    }

    // ── Estado interno ────────────────────────────────────────────────────────

    private var prevAngleDeg: Double? = null
    private var prevOmega: Double?    = null

    private val angleWindow = ArrayDeque<Double>(VARIANCE_WINDOW)

    // Detección de repeticiones
    // inFlexion = true cuando el brazo está en fase de flexión (ángulo bajando)
    private var inFlexion     = false
    private var currentPeak   = 999.0  // buscamos el MÍNIMO durante la flexión

    // Historial de picos de flexión por rep (ángulos bajos = máxima flexión)
    private val repPeaks = mutableListOf<Double>()

    // θ_ref dinámico — promedio de picos de reps anteriores
    private var thetaRef: Double? = null

    // ── BiomechanicsAlgorithm ─────────────────────────────────────────────────

    override fun process(landmarkFlatList: List<Double>): AlgorithmResult {
        if (landmarkFlatList.size < 132) return emptyResult()

        val arm = selectArm(landmarkFlatList) ?: return emptyResult()

        // ── §1 Ángulo articular θ(t) ──────────────────────────────────────────
        val angleDeg = computeElbowAngle(arm.shoulder, arm.elbow, arm.wrist)

        // ── §1 Velocidad angular ω(t) = dθ/dt ────────────────────────────────
        val omega = prevAngleDeg?.let { prev ->
            Math.toRadians(angleDeg - prev) / DT
        }

        // ── §1 Aceleración angular α(t) = dω/dt ──────────────────────────────
        val alpha = if (omega != null && prevOmega != null) {
            (omega - prevOmega!!) / DT
        } else null

        prevAngleDeg = angleDeg
        prevOmega    = omega

        // Ventana de ángulos para varianza §7
        if (angleWindow.size >= VARIANCE_WINDOW) angleWindow.removeFirst()
        angleWindow.addLast(angleDeg)

        // Detección de rep → construye θ_ref dinámico para §8
        detectRepetition(angleDeg)

        // ── §7 Detección de fatiga técnica ────────────────────────────────────
        val fatigue = detectFatigue(omega, alpha)

        // ── §8 Clasificación de error técnico ─────────────────────────────────
        val errorResult = classifyError(angleDeg)

        // ── §9 Alerta en tiempo real: A(t) = 1 si E(t) > δ o hay fatiga ──────
        val alert = fatigue.detected || errorResult.level != ErrorLevel.NONE

        return AlgorithmResult(
            angleDeg            = angleDeg,
            angularVelocity     = omega,
            angularAcceleration = alpha,
            fatigueDetected     = fatigue.detected,
            fatigueReason       = fatigue.reason,
            technicalError      = errorResult.level,
            errorMagnitude      = errorResult.magnitude,
            alert               = alert,
            algorithmName       = "CurlBiomechanics"
        )
    }

    override fun reset() {
        prevAngleDeg = null
        prevOmega    = null
        angleWindow.clear()
        repPeaks.clear()
        inFlexion   = false
        currentPeak = 999.0
        thetaRef    = null
    }

    // ── §1 Ángulo articular del codo ──────────────────────────────────────────
    // θ = arccos( (BA · BC) / (|BA| × |BC|) )
    // A = hombro, B = codo (vértice), C = muñeca

    private fun computeElbowAngle(shoulder: Vec3, elbow: Vec3, wrist: Vec3): Double {
        val ba = Vec3(shoulder.x - elbow.x, shoulder.y - elbow.y, shoulder.z - elbow.z)
        val bc = Vec3(wrist.x - elbow.x, wrist.y - elbow.y, wrist.z - elbow.z)
        val dot   = ba.x * bc.x + ba.y * bc.y + ba.z * bc.z
        val magBA = sqrt(ba.x * ba.x + ba.y * ba.y + ba.z * ba.z)
        val magBC = sqrt(bc.x * bc.x + bc.y * bc.y + bc.z * bc.z)
        if (magBA < 1e-6 || magBC < 1e-6) return 0.0
        return Math.toDegrees(acos((dot / (magBA * magBC)).coerceIn(-1.0, 1.0)))
    }

    // ── Detección de repeticiones ─────────────────────────────────────────────
    // En MediaPipe: ángulo BAJA durante flexión (brazo sube) → pico = mínimo local
    //               ángulo SUBE durante extensión (brazo baja) → valle = máximo local
    //
    // Máquina de estados:
    //   Estado 0 (inFlexion=false): esperando que ángulo baje de FLEXION_PEAK_THRESHOLD
    //   Estado 1 (inFlexion=true) : rastreando mínimo, esperando que suba a EXTENSION_VALLEY_THRESHOLD

    private fun detectRepetition(angleDeg: Double) {
        if (!inFlexion) {
            if (angleDeg < FLEXION_PEAK_THRESHOLD) {
                inFlexion   = true
                currentPeak = angleDeg
            }
        } else {
            // Rastrear el ángulo mínimo de esta flexión
            if (angleDeg < currentPeak) {
                currentPeak = angleDeg
            }
            // Rep completada cuando el brazo vuelve a extenderse
            if (angleDeg > EXTENSION_VALLEY_THRESHOLD) {
                repPeaks.add(currentPeak)
                // θ_ref = promedio de picos tras MIN_REPS_FOR_REF reps (paper: 3 reps)
                if (repPeaks.size >= MIN_REPS_FOR_REF) {
                    thetaRef = repPeaks.average()
                }
                inFlexion   = false
                currentPeak = 999.0
            }
        }
    }

    // ── §7 Fatiga técnica ─────────────────────────────────────────────────────

    private data class FatigueResult(val detected: Boolean, val reason: String?)

    private fun detectFatigue(omega: Double?, alpha: Double?): FatigueResult {
        val reasons = mutableListOf<String>()

        // Criterio 1 §7: ω(t) < ω_ref — solo durante movimiento activo
        // Si |ω| < MOVEMENT_THRESHOLD → en reposo entre reps → no penalizar
        if (omega != null
            && abs(omega) > MOVEMENT_THRESHOLD
            && abs(omega) < OMEGA_REF) {
            reasons += "Velocidad angular baja (ω=${String.format("%.2f", omega)} rad/s)"
        }

        // Criterio 2 §7: Var(θ(t)) > Var_ref
        if (angleWindow.size >= VARIANCE_WINDOW) {
            val variance = computeVariance(angleWindow)
            if (variance > VAR_REF) {
                reasons += "Alta variabilidad (Var=${String.format("%.1f", variance)} deg²)"
            }
        }

        // Criterio 3 §7: dω/dt irregular
        if (alpha != null && abs(alpha) > ALPHA_INSTABILITY) {
            reasons += "Inestabilidad dinámica (α=${String.format("%.1f", alpha)} rad/s²)"
        }

        return if (reasons.isNotEmpty()) FatigueResult(true, reasons.joinToString(" | "))
        else FatigueResult(false, null)
    }

    private fun computeVariance(values: Collection<Double>): Double {
        val mean = values.average()
        return values.map { (it - mean).pow(2) }.average()
    }

    // ── §8 Error técnico ──────────────────────────────────────────────────────
    // E(t) = |θ_real(t) − θ_ref(t)|
    // θ_ref = promedio de picos de reps anteriores (dinámico, no fijo)
    // Sin datos de 3 reps aún → no hay error (NONE)

    private data class ErrorResult(val level: ErrorLevel, val magnitude: Double?)

    private fun classifyError(angleDeg: Double): ErrorResult {
        val ref = thetaRef ?: return ErrorResult(ErrorLevel.NONE, null)

        val e = abs(angleDeg - ref)
        val level = when {
            e < DELTA_1       -> ErrorLevel.NONE
            e < DELTA_2       -> ErrorLevel.MILD
            e < DELTA_2 * 1.5 -> ErrorLevel.MODERATE
            else              -> ErrorLevel.SEVERE
        }
        return ErrorResult(level, e)
    }

    // ── Selección de brazo más visible ───────────────────────────────────────

    private fun selectArm(flat: List<Double>): ArmLandmarks? {
        val sR = getLandmark(flat, IDX_SHOULDER_R)
        val eR = getLandmark(flat, IDX_ELBOW_R)
        val wR = getLandmark(flat, IDX_WRIST_R)
        val sL = getLandmark(flat, IDX_SHOULDER_L)
        val eL = getLandmark(flat, IDX_ELBOW_L)
        val wL = getLandmark(flat, IDX_WRIST_L)

        val visR = listOf(sR, eR, wR).map { it.visibility }
        val visL = listOf(sL, eL, wL).map { it.visibility }
        val rightOk = visR.all { it >= MIN_VISIBILITY }
        val leftOk  = visL.all { it >= MIN_VISIBILITY }

        return when {
            rightOk && leftOk ->
                if (visR.average() >= visL.average())
                    ArmLandmarks(sR.vec, eR.vec, wR.vec)
                else
                    ArmLandmarks(sL.vec, eL.vec, wL.vec)
            rightOk -> ArmLandmarks(sR.vec, eR.vec, wR.vec)
            leftOk  -> ArmLandmarks(sL.vec, eL.vec, wL.vec)
            else    -> null
        }
    }

    private fun getLandmark(flat: List<Double>, index: Int): Landmark {
        val base = index * 4
        return Landmark(Vec3(flat[base], flat[base + 1], flat[base + 2]), flat[base + 3].toFloat())
    }

    private fun emptyResult() = AlgorithmResult(algorithmName = "CurlBiomechanics")

    private data class Vec3(val x: Double, val y: Double, val z: Double)
    private data class Landmark(val vec: Vec3, val visibility: Float)
    private data class ArmLandmarks(val shoulder: Vec3, val elbow: Vec3, val wrist: Vec3)
}