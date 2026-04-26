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
        // MediaPipe Pose landmark indices (BlazePose GHUM, 33 kpts)
        private const val IDX_SHOULDER_R = 12
        private const val IDX_ELBOW_R    = 14
        private const val IDX_WRIST_R    = 16
        private const val IDX_HIP_R      = 24
        private const val IDX_SHOULDER_L = 11
        private const val IDX_ELBOW_L    = 13
        private const val IDX_WRIST_L    = 15
        private const val IDX_HIP_L      = 23

        // Captura a 30 Hz (Serbest 2022, §2.1: "30 Hz image frequency")
        private const val SAMPLE_RATE_HZ = 30.0
        private const val DT = 1.0 / SAMPLE_RATE_HZ

        // ── §7 FATIGA (umbrales relativos, no absolutos) ─────────────────────
        //
        // Sánchez-Medina & González-Badillo (2011) Med Sci Sports Exerc 43(9):
        // 1725-1734 — la pérdida de velocidad (VL) pico respecto a la mejor
        // repetición es un marcador neuromuscular de fatiga validado
        // (r = 0.91-0.97 con lactato y caída de CMJ).
        //
        // Rodríguez-Rosell et al. (2023) Sports Medicine: VL ≥ 20 % = fatiga
        // moderada; VL ≥ 40 % = cercano al fallo muscular.
        private const val VL_MILD   = 0.20   // 20 % de pérdida de ω_peak
        private const val VL_SEVERE = 0.40   // 40 % de pérdida de ω_peak

        // Cortes et al. (2014) PMC3960345: la variabilidad cinemática cambia
        // con la fatiga, pero el umbral debe ser relativo al baseline.
        private const val VARIANCE_WINDOW       = 60    // 60 frames = 2 s @ 30 Hz
        private const val VAR_RATIO_FATIGUE     = 1.5   // 1.5x baseline

        // ── §2 Metodología (Serbest 2022): 3 reps por carga ──────────────────
        private const val MIN_REPS_FOR_REF = 3

        // ── §8 ERROR TÉCNICO (umbrales adaptativos) ──────────────────────────
        //
        // Liu et al. (2024) arXiv:2402.11421 — el curl se analiza por
        // desviación relativa al patrón personal del sujeto, no por
        // umbrales absolutos.
        private const val DELTA_SIGMA_MILD   = 1.5  // δ1 = 1.5·σ
        private const val DELTA_SIGMA_SEVERE = 2.5  // δ2 = 2.5·σ
        private const val DELTA_MIN_DEG      = 10.0 // piso clínico (goniometría ±5°)

        // Pinto et al. (2012) / Goto et al. (2019): Full ROM curl = 0°–130°.
        // Con 20° de tolerancia aceptable ⇒ mínimo 110° de recorrido.
        private const val FULL_ROM_MIN_DEG = 110.0

        // Liu et al. (2024) arXiv:2402.11421 — bajo fatiga/error, el hombro
        // se flexiona significativamente (10-20° por encima de reposo).
        private const val SHOULDER_COMPENSATION_DEG = 15.0
        private const val REST_SAMPLES              = 15  // 0.5 s para fijar reposo

        // Detección de fase del curl (umbrales de HISTÉRESIS, no de calidad).
        // Morrey et al. (1981) / Soldado et al. (2019) PMC6555111:
        // ROM normal de flexión del codo ≈ 130-150° (MediaPipe 30-50°).
        private const val FLEXION_PEAK_THRESHOLD     = 80.0
        private const val EXTENSION_VALLEY_THRESHOLD = 140.0

        private const val MIN_VISIBILITY = 0.5f
    }

    // ── Estado interno ────────────────────────────────────────────────────────

    private var prevAngleDeg: Double? = null
    private var prevOmega: Double?    = null

    private val angleWindow = ArrayDeque<Double>(VARIANCE_WINDOW)

    // Máquina de estados del ciclo de curl
    private var inFlexion     = false
    private var currentPeak   = Double.POSITIVE_INFINITY   // mínimo de flexión actual
    private var currentValley = Double.NEGATIVE_INFINITY   // máximo de extensión actual
    private var currentRepMaxOmega = 0.0                   // ω_peak de esta rep

    // Historial por repetición
    private val repPeakAngles   = mutableListOf<Double>()  // mínimos (flexión máxima)
    private val repValleyAngles = mutableListOf<Double>()  // máximos (extensión)
    private val repPeakOmegas   = mutableListOf<Double>()  // ω_peak por rep

    // Referencias adaptativas (§8) — se congelan tras MIN_REPS_FOR_REF reps
    private var thetaRefPeak:   Double? = null   // promedio mínimos
    private var thetaRefValley: Double? = null   // promedio máximos
    private var deltaSigma:     Double? = null   // σ de los picos (para δ1/δ2)
    private var omegaPeakRef:   Double? = null   // mediana ω_peak (§7)
    private var varianceBaseline: Double? = null // varianza en reposo-baseline

    // Ángulo del hombro en reposo (para §8 — compensación del hombro)
    // Liu et al. (2024) arXiv:2402.11421
    private val shoulderRestBuffer = ArrayDeque<Double>(REST_SAMPLES)
    private var thetaShoulderRest: Double? = null
    private var restVarianceBuffer = ArrayDeque<Double>(VARIANCE_WINDOW)

    // Snapshots de la ultima rep cerrada — exportados para persistencia per-rep.
    // Se actualizan cuando repPeakAngles.size cambia (nueva rep completada).
    private var lastSeenRepCount = 0
    private var snapLastRepConcVelDegS: Double? = null
    private var snapLastRepShoulderCompDeg: Double? = null
    private var snapLastRepFormQuality: ErrorLevel? = null

    // ── BiomechanicsAlgorithm ─────────────────────────────────────────────────

    override fun process(landmarkFlatList: List<Double>): AlgorithmResult {
        if (landmarkFlatList.size < 132) return emptyResult()

        val sample = selectArm(landmarkFlatList) ?: return emptyResult()

        // §1 — Ángulo articular del codo θ(t)
        val angleDeg = computeElbowAngle(sample.shoulder, sample.elbow, sample.wrist)

        // Liu et al. (2024) arXiv:2402.11421 — ángulo del hombro (cadera-hombro-codo)
        // = indicador directo de compensación del hombro.
        val shoulderDeg = computeShoulderAngle(sample.hip, sample.shoulder, sample.elbow)

        // §1 — Velocidad angular ω(t) = dθ/dt
        val omega = prevAngleDeg?.let { Math.toRadians(angleDeg - it) / DT }

        // §1 — Aceleración angular α(t) = dω/dt
        val alpha = if (omega != null && prevOmega != null) {
            (omega - prevOmega!!) / DT
        } else null

        prevAngleDeg = angleDeg
        prevOmega    = omega

        // Ventana móvil para Var(θ) — §7 Cortes et al. 2014
        if (angleWindow.size >= VARIANCE_WINDOW) angleWindow.removeFirst()
        angleWindow.addLast(angleDeg)

        // Captura de postura de reposo (primeros REST_SAMPLES frames)
        if (thetaShoulderRest == null) {
            shoulderRestBuffer.addLast(shoulderDeg)
            if (shoulderRestBuffer.size >= REST_SAMPLES) {
                thetaShoulderRest = shoulderRestBuffer.average()
            }
        }

        // Baseline de varianza (durante las primeras MIN_REPS_FOR_REF reps)
        if (varianceBaseline == null && angleWindow.size >= VARIANCE_WINDOW) {
            restVarianceBuffer.addLast(computeVariance(angleWindow))
            if (restVarianceBuffer.size >= VARIANCE_WINDOW) restVarianceBuffer.removeFirst()
        }

        // Tracking de ω_peak durante la rep (§7)
        if (omega != null) currentRepMaxOmega = max(currentRepMaxOmega, abs(omega))

        // Detección de rep → congela referencias tras MIN_REPS_FOR_REF reps
        detectRepetition(angleDeg)

        // §7 — Fatiga
        val fatigue = detectFatigue()

        // §8 — Error técnico
        val errorResult = classifyError(angleDeg, shoulderDeg)

        // §9 — Alerta: A(t)=1 si hay fatiga o error ≥ MILD
        val alert = fatigue.detected || errorResult.level != ErrorLevel.NONE

        val reasonCombined = listOfNotNull(fatigue.reason, errorResult.reason)
            .joinToString(" | ").ifEmpty { null }

        // ── Telemetria en vivo para el panel del curl ─────────────────────────
        // Pico de la rep en curso = minimo θ observado en concentrica.
        // Valle de la rep en curso = maximo θ observado en excentrica/inicio.
        // Solo son validos si ya se han poblado (isFinite).
        val livePeak  = currentPeak.takeIf  { it.isFinite() && it < FLEXION_PEAK_THRESHOLD }
        val liveValley = currentValley.takeIf { it.isFinite() }
        val liveRom = if (livePeak != null && liveValley != null) liveValley - livePeak else null

        // Ultima rep cerrada — persiste entre reps para que el panel no se
        // quede en blanco mientras el usuario baja el peso.
        val lastPeak   = repPeakAngles.lastOrNull()
        val lastValley = repValleyAngles.lastOrNull()
        val lastRom    = if (lastPeak != null && lastValley != null) lastValley - lastPeak else null

        // Compensacion del hombro (Liu 2024). Continuo, no solo flag binario.
        val shoulderShiftDeg = thetaShoulderRest?.let { abs(shoulderDeg - it) }

        // Velocidad angular pico (deg/s) — VBT (Sanchez-Medina 2011,
        // Rodriguez-Rosell 2023). Mientras la rep esta activa devuelvo el
        // peak |omega| acumulado de la rep actual; entre reps caigo al
        // peak de la ULTIMA rep cerrada para que el panel no parpadee a 0
        // mientras el usuario baja el peso. Antes del primer movimiento real
        // (currentRepMaxOmega == 0 y sin reps cerradas) devuelvo null.
        val concentricPeakDegS: Double? = run {
            val liveDegS = if (currentRepMaxOmega > 0.0)
                Math.toDegrees(currentRepMaxOmega) else null
            val lastDegS = repPeakOmegas.lastOrNull()?.let { Math.toDegrees(it) }
            liveDegS ?: lastDegS
        }

        // Snapshot de la rep recien cerrada (si aplica) — para persistencia
        // per-rep. Se calcula DESPUES de classifyError para incluir su nivel.
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
            velocityLossPercent = fatigue.velocityLossPct,   // ← nuevo
            alert               = alert,
            repCount            = repPeakAngles.size,
            algorithmName       = "CurlBiomechanics",
            // Live telemetry — usados por CameraActivity para los 3 paneles.
            currentRepPeakFlexionDeg     = livePeak,
            currentRepValleyExtensionDeg = liveValley,
            currentRepRomDeg             = liveRom,
            lastRepPeakFlexionDeg        = lastPeak,
            lastRepRomDeg                = lastRom,
            shoulderCompensationDeg      = shoulderShiftDeg,
            concentricPeakVelocityDegS   = concentricPeakDegS,
            // Snapshot de la ultima rep — para persistencia per-rep.
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
        thetaRefPeak       = null
        thetaRefValley     = null
        deltaSigma         = null
        omegaPeakRef       = null
        varianceBaseline   = null
        thetaShoulderRest  = null
        shoulderRestBuffer.clear()
        restVarianceBuffer.clear()
        lastSeenRepCount   = 0
        snapLastRepConcVelDegS = null
        snapLastRepShoulderCompDeg = null
        snapLastRepFormQuality = null
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

    // Ángulo del hombro = hip-shoulder-elbow (Liu et al. 2024).
    // En reposo (brazo colgando) ≈ 0°; en flexión del hombro aumenta.
    private fun computeShoulderAngle(hip: Vec3, shoulder: Vec3, elbow: Vec3): Double {
        val sh = Vec3(hip.x - shoulder.x, hip.y - shoulder.y, hip.z - shoulder.z)
        val se = Vec3(elbow.x - shoulder.x, elbow.y - shoulder.y, elbow.z - shoulder.z)
        val dot   = sh.x * se.x + sh.y * se.y + sh.z * se.z
        val magSH = sqrt(sh.x * sh.x + sh.y * sh.y + sh.z * sh.z)
        val magSE = sqrt(se.x * se.x + se.y * se.y + se.z * se.z)
        if (magSH < 1e-6 || magSE < 1e-6) return 0.0
        return Math.toDegrees(acos((dot / (magSH * magSE)).coerceIn(-1.0, 1.0)))
    }

    // ── Detección de repeticiones ─────────────────────────────────────────────
    // En MediaPipe: ángulo BAJA durante flexión (brazo sube) → pico = mínimo local
    //               ángulo SUBE durante extensión (brazo baja) → valle = máximo local
    //
    // Máquina de estados:
    //   Estado 0 (inFlexion=false): esperando que ángulo baje de FLEXION_PEAK_THRESHOLD
    //   Estado 1 (inFlexion=true) : rastreando mínimo, esperando que suba a EXTENSION_VALLEY_THRESHOLD

    // ── Detección de repeticiones + congelado de referencias ─────────────────
    // Extensión → ángulo ALTO; Flexión máxima → ángulo BAJO (MediaPipe).
    // Al completar una rep se almacenan: pico de flexión (mínimo), valle de
    // extensión (máximo) y ω_peak de esa rep.
    private fun detectRepetition(angleDeg: Double) {
        if (!inFlexion) {
            // Rastrear el valle de extensión antes de bajar
            if (angleDeg > currentValley) currentValley = angleDeg
            if (angleDeg < FLEXION_PEAK_THRESHOLD) {
                inFlexion   = true
                currentPeak = angleDeg
            }
        } else {
            if (angleDeg < currentPeak) currentPeak = angleDeg
            if (angleDeg > EXTENSION_VALLEY_THRESHOLD) {
                // Rep cerrada: registrar métricas
                repPeakAngles.add(currentPeak)
                if (currentValley.isFinite()) repValleyAngles.add(currentValley)
                repPeakOmegas.add(currentRepMaxOmega)

                // Congelar referencias tras MIN_REPS_FOR_REF reps (Serbest §2.1)
                if (repPeakAngles.size >= MIN_REPS_FOR_REF && thetaRefPeak == null) {
                    thetaRefPeak   = repPeakAngles.average()
                    thetaRefValley = repValleyAngles.average().takeIf { it.isFinite() }
                    deltaSigma     = stddev(repPeakAngles).coerceAtLeast(
                        DELTA_MIN_DEG / DELTA_SIGMA_MILD)
                    omegaPeakRef   = repPeakOmegas.sorted()[repPeakOmegas.size / 2] // mediana
                    varianceBaseline = restVarianceBuffer.lastOrNull()
                }

                // Preparar siguiente rep
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

    // ── §7 Fatiga ────────────────────────────────────────────────────────────
    //  Criterio 1 — Pérdida de velocidad (Sánchez-Medina 2011 / Rodríguez-Rosell 2023)
    //  Criterio 2 — Variabilidad de θ respecto al baseline (Cortes 2014)
    //  Sin criterio sobre α (no validado en literatura específica de curl).

    private data class FatigueResult(
        val detected: Boolean,
        val reason: String?,
        val velocityLossPct: Double?
    )

    private fun detectFatigue(): FatigueResult {
        val reasons = mutableListOf<String>()
        var vlPct: Double? = null

        // Criterio 1: VL continuo (Sánchez-Medina 2011 / Rodríguez-Rosell 2023)
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

        // Criterio 2: Var(θ) > VAR_RATIO_FATIGUE · baseline
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

    // ── §8 Error técnico ─────────────────────────────────────────────────────
    //  E1 — Desviación del pico (E = |θ_min_rep − θ_ref_peak|), solo en flexión
    //  E2 — Rango de movimiento insuficiente (Pinto 2012, Goto 2019)
    //  E3 — Compensación del hombro (Liu et al. 2024 arXiv:2402.11421)

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

        // E1 — Tras al menos MIN_REPS_FOR_REF reps completadas, ref y σ ya existen
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

        // E2 — Misma condición que E1
        if (refValley != null && repPeakAngles.size >= MIN_REPS_FOR_REF) {
            val lastRom = repValleyAngles.lastOrNull()?.minus(repPeakAngles.last())
            if (lastRom != null && lastRom < FULL_ROM_MIN_DEG) {
                reasons += "Rango incompleto (${String.format("%.0f°", lastRom)} < 110°)"
                worst = maxOf(worst, ErrorLevel.MODERATE)
            }
        }

        // E3 — sin cambio
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

    // ── Selección de brazo más visible ───────────────────────────────────────

    private fun selectArm(flat: List<Double>): ArmSample? {
        val sR = getLandmark(flat, IDX_SHOULDER_R)
        val eR = getLandmark(flat, IDX_ELBOW_R)
        val wR = getLandmark(flat, IDX_WRIST_R)
        val hR = getLandmark(flat, IDX_HIP_R)
        val sL = getLandmark(flat, IDX_SHOULDER_L)
        val eL = getLandmark(flat, IDX_ELBOW_L)
        val wL = getLandmark(flat, IDX_WRIST_L)
        val hL = getLandmark(flat, IDX_HIP_L)

        val visR = listOf(sR, eR, wR, hR).map { it.visibility }
        val visL = listOf(sL, eL, wL, hL).map { it.visibility }
        val rightOk = visR.all { it >= MIN_VISIBILITY }
        val leftOk  = visL.all { it >= MIN_VISIBILITY }

        return when {
            rightOk && leftOk ->
                if (visR.average() >= visL.average())
                    ArmSample(sR.vec, eR.vec, wR.vec, hR.vec)
                else
                    ArmSample(sL.vec, eL.vec, wL.vec, hL.vec)
            rightOk -> ArmSample(sR.vec, eR.vec, wR.vec, hR.vec)
            leftOk  -> ArmSample(sL.vec, eL.vec, wL.vec, hL.vec)
            else    -> null
        }
    }

    private data class ArmSample(
        val shoulder: Vec3,
        val elbow: Vec3,
        val wrist: Vec3,
        val hip: Vec3
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