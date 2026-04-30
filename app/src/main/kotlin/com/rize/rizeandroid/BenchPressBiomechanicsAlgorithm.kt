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
 *   1. Ancho de agarre: distancia entre munecas vs. ancho biacromial.
 *   2. Abduccion de hombro: angulo cadera-hombro-codo en la parte baja.
 *      Solo se evalua cuando el angulo del codo es <90°. Verde 45-80°, critico >90°
 *   3. Simetria bilateral: diferencia vertical de munecas normalizada por hombros.
 *   4. Profundidad de descenso: angulo minimo de codo debe alcanzar <= 95
 *   5. Extension completa: angulo de codo debe alcanzar >= 176 en la cima
 *
 * Prediccion de fatiga/fallo:
 *   6. Periodo de estancamiento: velocidad ~0 durante >870ms en fase concentrica
 *   7. Perdida de velocidad: VL15 (advertencia), VL25 (critico)
 *
 * ─── Vista de camara y geometria 2D (v3) ────────────────────────────────
 *
 * La vista de bench-press en RIZE es FRONTAL a la altura de la cabeza,
 * ligeramente picada. En esta toma el eje del press (sube/baja la barra)
 * coincide con el eje optico de la camara, lo que hace que el componente
 * Z de los landmarks de MediaPipe sea la coordenada con mayor varianza
 * (BlazePose infiere Z monocularmente, sin sensor de profundidad).
 *
 * Por eso TODAS las medidas geometricas en este algoritmo se calculan en
 * el plano de imagen 2D (ignorando Z). La proyeccion XY conserva senal
 * suficiente: la apertura lateral de codos y munecas durante el descenso
 * vive en X, y la subida/bajada de las munecas durante extension/flexion
 * vive en Y debido al ligero picado de la camara. Squat y curl, que usan
 * vistas laterales, mantienen su geometria 3D.
 *
 * ─── Robustez operacional (v3) ──────────────────────────────────────────
 *
 * Los landmarks que llegan aqui ya vienen filtrados por LandmarkSmoother
 * con perfil bench (minCutoff=1.2 Hz, beta=0.015) — ver Algorithms.kt.
 * Sobre eso, este algoritmo aplica:
 *
 *   a) Estado READY/NOT_READY para no emitir alertas hasta que la pose
 *      es estable y visible. Protege al usuario del ruido inicial mientras
 *      encuadra la camara.
 *   b) EMA sobre velocidad angular para suavizar el residuo de ruido que
 *      queda tras derivar con diferencia finita.
 *   c) Mediana movil de 5 muestras sobre la asimetria bilateral antes de
 *      compararla al umbral, para absorber el pico transitorio durante la
 *      fase rapida del descenso.
 *   d) Debounce de banderas (10 frames ~ 330 ms) para no parpadear.
 *   e) Umbrales operacionales por encima del suelo de ruido medido en campo.
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
        // Hysteresis subido de 8 a 15 deg/s tras el suavizado de landmarks.
        // Con 1€-Filter el ruido de velocidad cae a <10 deg/s en reposo; 15
        // deja margen sin perder sensibilidad a reps reales (~40-200 deg/s).
        private const val VELOCITY_HYSTERESIS = 15.0
        private const val START_DESCENT_ANGLE = 155.0     // angulo codo para iniciar descenso
        private const val TOP_POSITION_ANGLE = 160.0      // angulo codo para confirmar rep completa

        // ── Regla 1: Ancho de agarre ────────────────────────────────────────
        // El objetivo biomecanico es 1.5x el ancho biacromial:
        //   gripRatio = ancho horizontal entre munecas / ancho horizontal entre hombros.
        // Usamos solo X porque el "ancho" del agarre no debe inflarse por diferencias
        // verticales entre munecas durante el press.
        //  - [1.25, 1.75] -> optimo (verde, centrado en 1.5x)
        //  - fuera de ese rango -> advertencia (ambar)
        //  - > 1.8 -> excesivo, riesgo hombro (rojo / flag gripTooWide)
        private const val GRIP_WIDTH_RATIO_TARGET = 1.5
        private const val GRIP_WIDTH_RATIO_MIN = GRIP_WIDTH_RATIO_TARGET - 0.25
        private const val GRIP_WIDTH_RATIO_MAX = GRIP_WIDTH_RATIO_TARGET + 0.25
        private const val GRIP_WIDTH_RATIO_CRITICAL = 2.0
        // Calibracion empirica para la perspectiva actual: las munecas suelen
        // estar mas cerca de la camara que los hombros, inflando el ratio 2D.
        // Medicion de referencia: 2.5x observado debe mapear a 1.5x real.
        private const val GRIP_PERSPECTIVE_CORRECTION = 0.6

        // ── Regla 2: Abduccion de hombro ────────────────────────────────────
        private const val ABDUCTION_EVALUATION_ELBOW_DEG = 90.0
        private const val ABDUCTION_MIN_OK_DEG = 45.0
        private const val ABDUCTION_MAX_OK_DEG = 80.0
        private const val ABDUCTION_CRITICAL_DEG = 90.0

        // ── Regla 3: Simetria bilateral ─────────────────────────────────────
        // En vista frontal el indicador mas estable es si ambas munecas estan
        // a la misma altura. Usamos diferencia vertical normalizada por ancho
        // biacromial horizontal:
        //   symmetryPct = |wristL.y - wristR.y| / |shoulderL.x - shoulderR.x| * 100
        //  - < 8%  -> simetria OK
        //  - >= 8% -> advertencia visual
        //  - >= 15% sostenido -> asimetria tecnica (flag)
        private const val SYMMETRY_THRESHOLD_PERCENT = 8.0
        private const val SYMMETRY_ALERT_THRESHOLD_PERCENT = 15.0

        // ── Regla 4: Profundidad de descenso ────────────────────────────────
        // Criterio adaptado a vista frontal-cabeza: en lugar de "codo debajo
        // del torso" (no observable en este encuadre porque la linea hombro-
        // cadera es ~vertical en imagen y el codo no cruza Y de forma
        // significativa), evaluamos el ANGULO MINIMO de codo alcanzado en
        // la rep. La tesis describe la profundidad como "barra finalizar
        // 4-6 cm sobre el pecho" — para un brazo medio adulto eso traduce
        // a un angulo de codo en la fase inferior cercano a 90-100°.
        // 95° toma el punto medio como umbral operacional: si el codo no
        // baja de 95°, la rep es de profundidad insuficiente.
        private const val BENCH_DEPTH_MIN_ELBOW_DEG = 95.0

        // ── Regla 5: Extension completa ─────────────────────────────────────
        private const val FULL_EXTENSION_MIN_DEG = 176.0

        // ── Regla 6: Periodo de estancamiento ───────────────────────────────
        // 5 deg/s era el umbral teorico; con el ruido medido elevamos el
        // umbral operacional a 10 deg/s. 870 ms se mantiene (literatura).
        private const val STICKING_VELOCITY_THRESHOLD = 10.0
        private const val STICKING_DURATION_MS = 870L

        // ── Regla 7: Perdida de velocidad ───────────────────────────────────
        private const val VL_WARNING_PERCENT = 15.0
        private const val VL_CRITICAL_PERCENT = 25.0

        // ── Validacion de rep ────────────────────────────────────────────────
        private const val MIN_VALID_ROM_DEG = 30.0
        private const val MAX_VALID_BOTTOM_ANGLE_DEG = 120.0
        private const val MIN_VALID_BOTTOM_ANGLE_DEG = 20.0

        // ── Suavizado de velocidad angular (EMA) ────────────────────────────
        // 0.4 = peso al valor nuevo. Latencia ~2-3 frames, suficiente para no
        // perder el arranque de concentrica pero basta para estabilizar.
        private const val OMEGA_EMA_ALPHA = 0.4

        // ── Debounce de banderas posturales ─────────────────────────────────
        // Un frame aislado que cruza el umbral no dispara alerta; se exigen N
        // frames consecutivos (~330 ms a 30 Hz). Subido de 5 a 10 tras pasar
        // a geometria 2D para absorber picos transitorios durante la fase
        // rapida del descenso de la barra.
        private const val POSTURAL_DEBOUNCE_FRAMES = 10

        // ── Mediana movil de simetria bilateral ─────────────────────────────
        // Tamano de ventana para suavizar |L-R| antes de comparar al umbral.
        // Una mediana de 5 muestras (~167 ms) elimina picos transitorios
        // sin retrasar significativamente la deteccion de asimetria real.
        private const val SYMMETRY_MEDIAN_WINDOW = 5

        // ── Estado READY ────────────────────────────────────────────────────
        // Frames estables requeridos antes de marcar READY. Mas permisivo que
        // antes: el objetivo es saber si la pose es visible con suficiente
        // calidad, NO forzar que el atleta este completamente estatico.
        // El umbral de std era 4° con ventana de 10 frames, lo que exigia una
        // micro-estatica casi imposible durante la ejecucion. Lo elevamos a
        // 20° (cualquier movimiento tipico cabe adentro) y reducimos los
        // frames requeridos para no retrasar el feedback.
        private const val READY_STABLE_FRAMES = 8            // ~0.27 s a 30 Hz
        private const val READY_ANGLE_STD_MAX_DEG = 20.0     // tolera movimiento real
        private const val READY_WINDOW_FRAMES = 8
    }

    private enum class RepPhase { IDLE, DESCENT, ASCENT }

    private enum class ReadinessState { NOT_READY, STABILIZING, READY }

    // ── Estado por frame ─────────────────────────────────────────────────────
    private var prevElbowAngleDeg: Double? = null
    private var prevAngularVelocityDegS: Double? = null
    private var smoothedAngularVelocityDegS: Double? = null

    // ── Maquina de estados de rep ────────────────────────────────────────────
    private var phase: RepPhase = RepPhase.IDLE

    // ── Readiness ────────────────────────────────────────────────────────────
    private var readinessState: ReadinessState = ReadinessState.NOT_READY
    private var stableFrameCount = 0
    private val recentAngles = ArrayDeque<Double>(READY_WINDOW_FRAMES)

    // ── Debounce de banderas posturales ──────────────────────────────────────
    private var asymmetryConsecutiveFrames = 0
    private var abductionWarningConsecutiveFrames = 0
    private var abductionCriticalConsecutiveFrames = 0

    // ── Mediana movil para asimetria bilateral ───────────────────────────────
    private val asymmetryWindow = ArrayDeque<Double>(SYMMETRY_MEDIAN_WINDOW)

    // ── Tracking por rep ─────────────────────────────────────────────────────
    private var currentMinElbowAngleDeg = Double.MAX_VALUE
    private var currentRepStartElbowAngleDeg = Double.MAX_VALUE
    private var currentPeakConcentricVelocityDegS = 0.0
    private var currentPeakEccentricVelocityDegS = 0.0
    private var currentRepElbowWentBelowTorso = false
    private var currentRepTopElbowAngleDeg = 0.0
    private var currentRepWorstShoulderAbductionDeg: Double? = null
    private var currentRepShoulderAbductionRisk = false
    private var currentRepShoulderAbductionCritical = false

    // ── Sticking period (Regla 6) ────────────────────────────────────────────
    private var stickingStartMs: Long? = null
    private var currentStickingPeriodDetected = false

    // ── Estado persistente entre reps ────────────────────────────────────────
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

    // ── Historial de velocidad ───────────────────────────────────────────────
    private val concentricVelocityByRep = mutableListOf<Double>()

    // ── Snapshots de la ultima rep cerrada (para persistencia per-rep) ──────
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

        if (!leftVisible && !rightVisible) {
            // Perdida total de pose -> readiness cae a NOT_READY
            degradeReadiness()
            return emptyResult()
        }

        // Calcular angulos de codo bilaterales en 2D imagen (X-Y, sin Z)
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

        // Velocidad angular: derivada cruda + EMA para amortiguar ruido residual
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

        // Actualizar readiness
        updateReadiness(primaryElbowAngle, leftVisible && rightVisible)

        // ── Reglas por frame ─────────────────────────────────────────────────

        // Regla 1: Ancho de agarre — apertura horizontal (X) en vista frontal.
        var gripWidthRatio: Double? = null
        if (leftVisible && rightVisible) {
            val wristDist = horizontalDistance(leftArm.wrist.vec, rightArm.wrist.vec)
            val biacromialDist = horizontalDistance(leftArm.shoulder.vec, rightArm.shoulder.vec)
            if (biacromialDist > 1e-6) {
                gripWidthRatio = (wristDist / biacromialDist) * GRIP_PERSPECTIVE_CORRECTION
                lastGripWidthRatioMeasured = gripWidthRatio
                // Solo marcamos "demasiado ancho" (riesgo real) cuando cruza el
                // umbral critico. Las advertencias entre MAX y CRITICAL se
                // pintan en la UI como ambar pero no disparan el flag de error
                // tecnico del algoritmo (para no saturar alertas severas).
                lastGripTooWide = gripWidthRatio > GRIP_WIDTH_RATIO_CRITICAL
            }
        }

        // Regla 2: Abduccion de hombro — solo se evalua en la parte baja.
        // En vista frontal, el angulo torso-codo es interpretable cuando el
        // codo ya esta flexionado (<90°). En la parte alta se inflaba por
        // proyeccion y generaba falsos positivos.
        val evaluateAbductionNow = primaryElbowAngle < ABDUCTION_EVALUATION_ELBOW_DEG
        val abductionLeft = if (evaluateAbductionNow && leftVisible) {
            computeAngle2D(leftArm.hip.vec, leftArm.shoulder.vec, leftArm.elbow.vec)
        } else null
        val abductionRight = if (evaluateAbductionNow && rightVisible) {
            computeAngle2D(rightArm.hip.vec, rightArm.shoulder.vec, rightArm.elbow.vec)
        } else null
        val abductionValues = listOfNotNull(abductionLeft, abductionRight)
        val worstAbduction = when {
            abductionValues.any { it > ABDUCTION_MAX_OK_DEG } -> abductionValues.maxOrNull()
            abductionValues.any { it < ABDUCTION_MIN_OK_DEG } -> abductionValues.minOrNull()
            else -> abductionValues.maxOrNull()
        }
        lastShoulderAbductionDeg = worstAbduction
        lastShoulderAbductionRisk = updateAbductionRiskDebounced(worstAbduction)
        if (worstAbduction != null && phase != RepPhase.IDLE) {
            currentRepWorstShoulderAbductionDeg = maxOf(
                currentRepWorstShoulderAbductionDeg ?: worstAbduction,
                worstAbduction
            )
            currentRepShoulderAbductionRisk =
                currentRepShoulderAbductionRisk || isAbductionOutsideOkRange(worstAbduction)
            currentRepShoulderAbductionCritical =
                currentRepShoulderAbductionCritical || worstAbduction > ABDUCTION_CRITICAL_DEG
        }

        // Regla 3: Simetria bilateral — diferencia vertical de munecas.
        // Aunque el campo expuesto conserva el nombre legacy "Deg", en bench
        // representa porcentaje del ancho biacromial para evitar falsos
        // positivos por angulos 2D proyectados.
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

        // Regla 4: Profundidad — evaluacion por frame con angulo de codo.
        // En vista frontal-cabeza no se puede observar "codo bajo el torso"
        // (la linea hombro-cadera es ~vertical en imagen y el codo no cruza
        // Y de manera significativa). Usamos el angulo de codo comparado
        // contra BENCH_DEPTH_MIN_ELBOW_DEG: si la rep alcanza un minimo
        // por debajo del umbral, la profundidad es suficiente.
        val elbowAtDepthNow = phase != RepPhase.IDLE
                && primaryElbowAngle <= BENCH_DEPTH_MIN_ELBOW_DEG
        if (phase == RepPhase.DESCENT && elbowAtDepthNow) {
            currentRepElbowWentBelowTorso = true
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

        // Las banderas (asymmetry, abduction, grip) ya estan debounced por
        // N frames consecutivos; eso es suficiente filtro de ruido inicial.
        // El gating extra por readiness era redundante y, al no alcanzarse
        // READY con movimiento exagerado, silenciaba TODAS las alertas —
        // exactamente lo contrario de lo que queremos. readinessReady sigue
        // exponiendose para que la UI pinte la pildora de calidad de senal.
        val alert = fatigueDetected ||
                lastTechnicalError != ErrorLevel.NONE ||
                currentStickingPeriodDetected

        // Valores "live" de la rep en curso para feedback en tiempo real.
        // Solo tienen sentido cuando hay una rep activa (DESCENT/ASCENT);
        // en IDLE devolvemos null para que la UI muestre "--".
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
            extensionIncomplete = lastExtensionIncomplete,
            currentRepMinElbowAngleDeg = liveMinElbow,
            currentRepMaxElbowAngleDeg = liveMaxElbow,
            elbowBelowTorsoLive = elbowAtDepthNow,
            readinessReady = readinessState == ReadinessState.READY,
            // Snapshots per-rep para persistencia.
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
            lastRepFormQuality = snapLastRepFormQuality
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // reset()
    // ═══════════════════════════════════════════════════════════════════════════

    override fun reset() {
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Readiness
    // ═══════════════════════════════════════════════════════════════════════════

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
                // Una vez READY, mantenemos el estado mientras haya pose.
                // No volvemos a NOT_READY por variabilidad angular: la rep
                // real produce std alta por definicion (p.ej. bajar de 170 a
                // 90 en <1 s). Si antes degradabamos aqui, las alertas se
                // silenciaban justo durante la ejecucion — lo opuesto a lo
                // que queremos.
                // Solo degradeReadiness() via perdida de pose (arriba en
                // updateReadiness con bothArmsVisible=false, o desde
                // process() si no hay landmarks) nos saca de READY.
            }
        }
    }

    private fun degradeReadiness() {
        readinessState = ReadinessState.NOT_READY
        stableFrameCount = 0
        recentAngles.clear()
    }

    private fun angleStdDev(values: Collection<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Debounce de banderas posturales
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * El flag de asimetria se activa solo si el umbral operacional
     * (SYMMETRY_ALERT_THRESHOLD_PERCENT) se supera durante N frames consecutivos.
     * Se desactiva apenas la asimetria baja del umbral base (SYMMETRY_THRESHOLD_PERCENT).
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
     * Abduccion. Dos niveles: warning y critical. Ambos con debounce.
     */
    private fun updateAbductionRiskDebounced(worstAbduction: Double?): Boolean {
        val angle = worstAbduction ?: 0.0
        if (angle > ABDUCTION_CRITICAL_DEG) {
            abductionCriticalConsecutiveFrames += 1
            abductionWarningConsecutiveFrames += 1
        } else if (isAbductionOutsideOkRange(angle)) {
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

    private fun isAbductionOutsideOkRange(angle: Double): Boolean =
        angle < ABDUCTION_MIN_OK_DEG || angle > ABDUCTION_MAX_OK_DEG

    // ═══════════════════════════════════════════════════════════════════════════
    // Maquina de estados de repeticion
    // ═══════════════════════════════════════════════════════════════════════════

    private fun updateRepState(elbowAngleDeg: Double, angularVelocityDegS: Double) {
        when (phase) {
            RepPhase.IDLE -> {
                if (elbowAngleDeg < START_DESCENT_ANGLE && angularVelocityDegS < -VELOCITY_HYSTERESIS) {
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
        currentRepWorstShoulderAbductionDeg = null
        currentRepShoulderAbductionRisk = false
        currentRepShoulderAbductionCritical = false
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

        // Regla 4: Profundidad de descenso — el codo debe haber bajado de
        // BENCH_DEPTH_MIN_ELBOW_DEG en algun momento de la rep (flag
        // currentRepElbowWentBelowTorso, reaprovechado con nueva semantica).
        // Tambien validamos contra el min observado por seguridad si el flag
        // no se levanto pero el min final cumple (proteccion contra perdida
        // momentanea de la pose).
        lastDepthInsufficientBench = !currentRepElbowWentBelowTorso
                && currentMinElbowAngleDeg > BENCH_DEPTH_MIN_ELBOW_DEG

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

        // Magnitud del error (el mayor de los errores activos)
        val depthMagnitude = if (lastDepthInsufficientBench) 5.0 else 0.0
        val extensionMagnitude = lastExtensionIncompleteDeg ?: 0.0
        val abductionMagnitude = when {
            abduction > ABDUCTION_MAX_OK_DEG -> abduction - ABDUCTION_MAX_OK_DEG
            abduction < ABDUCTION_MIN_OK_DEG -> ABDUCTION_MIN_OK_DEG - abduction
            else -> 0.0
        }
        val asymmetryMagnitude = if (lastBilateralAsymmetry) (lastBilateralAsymmetryDeg ?: 0.0) - SYMMETRY_THRESHOLD_PERCENT else 0.0
        val vlMagnitude = if (velocityLoss >= VL_WARNING_PERCENT) velocityLoss - VL_WARNING_PERCENT else 0.0

        lastErrorMagnitude = listOf(depthMagnitude, extensionMagnitude, abductionMagnitude, asymmetryMagnitude, vlMagnitude)
            .maxOrNull()?.takeIf { it > 0.0 }

        // Snapshot de la rep recien cerrada — para persistencia per-rep.
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Utilidades
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Mantiene una ventana FIFO de tamano [windowSize] y devuelve la
     * mediana de los valores acumulados (1..windowSize) tras anadir
     * [value]. Util para suavizar picos transitorios sin retrasar mucho.
     */
    private fun pushAndMedian(window: ArrayDeque<Double>, value: Double, windowSize: Int): Double {
        if (window.size >= windowSize) window.removeFirst()
        window.addLast(value)
        val sorted = window.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

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
     * Angulo formado por A-B-C proyectado en el plano de imagen 2D
     * (componentes X-Y, ignorando Z). En vista frontal-cabeza Z es la
     * coordenada con mayor varianza de BlazePose, asi que ignorarla
     * estabiliza dramaticamente el angulo medido.
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

    /**
     * Separacion horizontal entre dos landmarks. Para el agarre de press banca
     * buscamos ancho lateral, no distancia euclidiana X-Y.
     */
    private fun horizontalDistance(a: Vec3, b: Vec3): Double = abs(a.x - b.x)

    private fun verticalDistance(a: Vec3, b: Vec3): Double = abs(a.y - b.y)

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
