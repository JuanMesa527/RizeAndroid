package com.rize.rizeandroid;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.rize.rizeandroid.data.PendingRep;
import com.rize.rizeandroid.data.PendingSessionBuilder;
import com.rize.rizeandroid.data.PendingSessionData;
import com.rize.rizeandroid.data.PendingSessionHolder;
import com.rize.rizeandroid.data.entity.WorkoutSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CameraActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST = 100;
    public static final String EXTRA_AUTO_SAVE = "auto_save";
    public static final String EXTRA_EXERCISE_TYPE = "exercise_type";
    public static final String EXTRA_EXERCISE_NAME = "exercise_name_display";
    public static final String EXTRA_ALREADY_SAVED = "already_saved";

    private FrameLayout cameraContainer;
    private TextView cameraTitle;
    private TextView cameraTimer;
    private CameraViewManager cameraViewManager;
    private Algorithms algorithms;

    // ── Views de métricas (panel estándar: squat / curl) ─────────────────────
    private View metricsStandardPanel;
    private TextView metricPeakAngle;
    private TextView metricStability;
    private ProgressBar progressConsistency;
    private TextView metricAngleLabel;
    private TextView metricHipAngle;
    private TextView metricStabilityLabel;
    private TextView metricConsistencyLabel;
    private TextView metricConsistencyHint;
    private TextView squatAlertText;
    // Curl-specific: card de Velocity + Live Flex que sustituye al
    // progress_consistency cuando el ejercicio activo es curl. consistencyCard
    // es el contenedor del progress bar para poder ocultarlo en curl.
    private View consistencyCard;
    private View curlMetricsRow;
    private TextView metricCurlVelocity;
    private TextView metricCurlFlex;

    // ── Views de métricas (panel press banca: 5 reglas + alert banner) ───────
    private View metricsBenchPanel;
    private TextView benchAlertBanner;
    private TextView benchRepCount;
    private TextView benchElbowAngle;
    private TextView benchReadinessPill;
    // Rule row views: dot (status color) + value (text)
    private View benchRuleGripDot;
    private TextView benchRuleGripValue;
    private View benchRuleAbductionDot;
    private TextView benchRuleAbductionValue;
    private View benchRuleSymmetryDot;
    private TextView benchRuleSymmetryValue;
    private View benchRuleDepthDot;
    private TextView benchRuleDepthValue;
    private View benchRuleExtensionDot;
    private TextView benchRuleExtensionValue;

    // ── Estado de métricas ────────────────────────────────────────────────────

    private double currentAngle = 0.0;
    private double peakAngle    = 0.0;

    private Double lastSquatCvtDisplay = null;
    private Double lastSquatRetentionDisplay = null;
    private int lastSquatAlertTextRes = -1;
    private int lastSquatAlertColor = -1;

    // EMA (Exponential Moving Average) — reacciona rápido a cambios reales.
    // Alpha alto (~0.4) = muy reactivo, Alpha bajo (~0.05) = muy suave.
    //
    //   STABILITY_ALPHA = 0.06  — score lento, tendencia (suaviza ~17 frames a 30 Hz)
    //   CURL_ANGLE_ALPHA = 0.30 — angulo y flex viven con el movimiento pero no
    //                             saltan frame a frame por jitter de MediaPipe.
    //   CURL_VEL_ALPHA   = 0.45 — velocidad casi en vivo, solo amortigua picos
    //                             aislados que el suavizador 1€ deja pasar.
    private static final double STABILITY_ALPHA    = 0.06;
    private static final double CURL_ANGLE_ALPHA   = 0.30;
    private static final double CURL_VEL_ALPHA     = 0.45;
    private double emaStability    = 100.0; // arranca en 100%
    private Double emaCurlAngle    = null;  // null hasta el primer frame con pose
    private Double emaCurlFlex     = null;
    private Double emaCurlVelocity = null;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private int elapsedSeconds = 0;
    private boolean timerRunning = false;
    // Guard de idempotencia para finishSession(). El back fisico, el gesto
    // de borde y el boton del top bar comparten el mismo flujo, por lo que
    // necesitamos asegurar que SummaryActivity solo se abre una vez por sesion.
    private boolean sessionFinishing = false;

    // ── Persistencia de sesion ────────────────────────────────────────────────
    private boolean autoSaveEnabled = false;
    private long sessionStartMs = 0L;
    private String exerciseDisplayName = "";
    private final List<PendingRep> pendingReps = new ArrayList<>();
    private int lastSeenRepCount = 0;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (timerRunning) {
                elapsedSeconds++;
                int minutes = elapsedSeconds / 60;
                int seconds = elapsedSeconds % 60;
                cameraTimer.setText(String.format(Locale.US, "%02d:%02d", minutes, seconds));
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        cameraContainer     = findViewById(R.id.camera_container);
        cameraTitle         = findViewById(R.id.camera_title);
        cameraTimer         = findViewById(R.id.camera_timer);
        metricPeakAngle     = findViewById(R.id.metric_peak_angle);
        metricStability     = findViewById(R.id.metric_stability);
        progressConsistency = findViewById(R.id.progress_consistency);

        // EMA arranca en 100% para estabilidad (todo OK al inicio)
        // Consistencia arranca en -1 (sin datos hasta 3 reps)

        String exerciseName = getIntent().getStringExtra("exercise_name");
        if (exerciseName != null) {
            cameraTitle.setText(getString(
                    R.string.camera_title_analysis_format,
                    exerciseName.toUpperCase(Locale.US)
            ));
        }

        exerciseDisplayName = exerciseName == null ? "" : exerciseName;
        autoSaveEnabled = getIntent().getBooleanExtra(EXTRA_AUTO_SAVE, true);
        sessionStartMs = System.currentTimeMillis();

        setupToolbar();
        setupBottomNav();
        setupBackNavigation();
        setupAlgorithms(exerciseName);
        checkCameraPermission();
    }

    // ── Algoritmos ────────────────────────────────────────────────────────────

    private boolean isCurlExercise = false;
    private boolean isSquatExercise = false;
    private boolean isBenchPressExercise = false;
    private boolean isAnalyzedExercise = false;

    private void setupAlgorithms(String exerciseName) {
        metricsStandardPanel   = findViewById(R.id.metrics_standard);
        metricAngleLabel       = findViewById(R.id.metric_angle_label);
        metricHipAngle         = findViewById(R.id.metric_hip_angle);
        metricStabilityLabel   = findViewById(R.id.metric_stability_label);
        metricConsistencyLabel = findViewById(R.id.metric_consistency_label);
        metricConsistencyHint  = findViewById(R.id.metric_consistency_hint);
        squatAlertText         = findViewById(R.id.squat_alert_text);
        // Curl-specific
        consistencyCard        = findViewById(R.id.consistency_card);
        curlMetricsRow         = findViewById(R.id.curl_metrics_row);
        metricCurlVelocity     = findViewById(R.id.metric_curl_velocity);
        metricCurlFlex         = findViewById(R.id.metric_curl_flex);

        // Views del nuevo panel de press banca
        metricsBenchPanel           = findViewById(R.id.metrics_bench);
        benchAlertBanner            = findViewById(R.id.bench_alert_banner);
        benchRepCount               = findViewById(R.id.bench_rep_count);
        benchElbowAngle             = findViewById(R.id.bench_elbow_angle);
        benchReadinessPill          = findViewById(R.id.bench_readiness_pill);
        benchRuleGripDot            = findViewById(R.id.bench_rule_grip_dot);
        benchRuleGripValue          = findViewById(R.id.bench_rule_grip_value);
        benchRuleAbductionDot       = findViewById(R.id.bench_rule_abduction_dot);
        benchRuleAbductionValue     = findViewById(R.id.bench_rule_abduction_value);
        benchRuleSymmetryDot        = findViewById(R.id.bench_rule_symmetry_dot);
        benchRuleSymmetryValue      = findViewById(R.id.bench_rule_symmetry_value);
        benchRuleDepthDot           = findViewById(R.id.bench_rule_depth_dot);
        benchRuleDepthValue         = findViewById(R.id.bench_rule_depth_value);
        benchRuleExtensionDot       = findViewById(R.id.bench_rule_extension_dot);
        benchRuleExtensionValue     = findViewById(R.id.bench_rule_extension_value);

        // Detectar tipo de ejercicio desde el extra tipado (inmune al idioma).
        // Fallback a detección por string para compatibilidad con VideoAnalysisActivity.
        String exerciseTypeRaw = getIntent().getStringExtra("exercise_type");
        ExerciseType exerciseType = ExerciseType.UNKNOWN;
        if (exerciseTypeRaw != null) {
            try { exerciseType = ExerciseType.valueOf(exerciseTypeRaw); }
            catch (IllegalArgumentException ignored) {}
        }
        if (exerciseType == ExerciseType.UNKNOWN && exerciseName != null) {
            String n = exerciseName.toLowerCase(Locale.ROOT);
            if (n.contains("curl")  || n.contains("mancuerna"))   exerciseType = ExerciseType.CURL;
            else if (n.contains("squat") || n.contains("sentadilla")) exerciseType = ExerciseType.SQUAT;
            else if (n.contains("bench") || n.contains("banca"))      exerciseType = ExerciseType.BENCH_PRESS;
        }
        isCurlExercise       = exerciseType == ExerciseType.CURL;
        isSquatExercise      = exerciseType == ExerciseType.SQUAT;
        isBenchPressExercise = exerciseType == ExerciseType.BENCH_PRESS;
        isAnalyzedExercise   = exerciseType != ExerciseType.UNKNOWN;

        if (!isAnalyzedExercise) {
            // Ocultar las métricas para ejercicios sin análisis
            findViewById(R.id.metric_peak_angle).setVisibility(View.GONE);
            findViewById(R.id.metric_stability).setVisibility(View.GONE);
            if (consistencyCard != null) consistencyCard.setVisibility(View.GONE);
            if (curlMetricsRow != null) curlMetricsRow.setVisibility(View.GONE);
            if (metricHipAngle != null) {
                metricHipAngle.setVisibility(View.GONE);
            }
            if (squatAlertText != null) {
                squatAlertText.setVisibility(View.GONE);
            }
            if (metricsBenchPanel != null) {
                metricsBenchPanel.setVisibility(View.GONE);
            }
            return;
        }

        if (isSquatExercise) {
            metricsStandardPanel.setVisibility(View.VISIBLE);
            metricsBenchPanel.setVisibility(View.GONE);
            // Squat usa el card clasico de progreso, NO los dos cards de curl.
            consistencyCard.setVisibility(View.VISIBLE);
            curlMetricsRow.setVisibility(View.GONE);
            metricAngleLabel.setText(R.string.camera_knee_angle);
            metricStabilityLabel.setText(R.string.camera_cvt);
            metricConsistencyLabel.setText(R.string.camera_velocity_retention);
            metricConsistencyHint.setText(R.string.camera_vl20_hint);
            lastSquatCvtDisplay = null;
            lastSquatRetentionDisplay = null;
            lastSquatAlertTextRes = -1;
            lastSquatAlertColor = -1;
            metricPeakAngle.setText("--");
            metricHipAngle.setText(R.string.camera_hip_placeholder);
            metricHipAngle.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
            metricStability.setText("--");
            progressConsistency.setProgress(0);
            squatAlertText.setVisibility(View.VISIBLE);
            squatAlertText.setText(R.string.camera_squat_status_ready);
            squatAlertText.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
        } else if (isBenchPressExercise) {
            // Press banca usa su propio panel con las 5 reglas visibles
            metricsStandardPanel.setVisibility(View.GONE);
            metricsBenchPanel.setVisibility(View.VISIBLE);
            resetBenchPanel();
        } else {
            // ── Curl de biceps ──────────────────────────────────────────
            // Layout reutilizado del panel estandar pero recableado para
            // exhibir, EN VIVO durante la rep:
            //   * metric_peak_angle  → pico de flexion real (min θ MediaPipe)
            //   * metric_hip_angle   → "Now XX°" con el angulo actual
            //   * metric_stability   → estabilidad dominada por compensacion
            //                          de hombro (Liu 2024)
            //   * curl_metrics_row   → DOS tarjetas en horizontal:
            //                          - Velocity (Sanchez-Medina 2011 / VBT)
            //                          - Live Flex % (Pinto 2012, theta(t))
            //                          Sustituye al progress_consistency.
            //   * squat_alert_text   → mensaje contextual del curl
            metricsStandardPanel.setVisibility(View.VISIBLE);
            metricsBenchPanel.setVisibility(View.GONE);
            // Ocultamos el card del progress bar y mostramos las dos tarjetas
            // nuevas en su lugar.
            consistencyCard.setVisibility(View.GONE);
            curlMetricsRow.setVisibility(View.VISIBLE);
            metricAngleLabel.setText(R.string.camera_curl_peak_label);
            metricHipAngle.setVisibility(View.VISIBLE);
            metricHipAngle.setText(R.string.bench_status_placeholder);
            metricHipAngle.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
            metricStabilityLabel.setText(R.string.camera_curl_stability_label);
            metricPeakAngle.setText("--");
            metricStability.setText("--");
            metricCurlVelocity.setText("--");
            metricCurlFlex.setText("--");
            squatAlertText.setVisibility(View.VISIBLE);
            squatAlertText.setText(R.string.camera_curl_status_ready);
            squatAlertText.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
            // Reset de los EMAs del curl: queremos arrancar limpios sin
            // arrastre del ejercicio anterior.
            emaStability    = 100.0;
            emaCurlAngle    = null;
            emaCurlFlex     = null;
            emaCurlVelocity = null;
        }

        algorithms = new Algorithms();
        algorithms.selectAlgorithm(exerciseType);

        PoseDataManager.INSTANCE.setPoseDataListener(landmarkData -> {
            algorithms.onPoseData(landmarkData);
            return kotlin.Unit.INSTANCE;
        });
        algorithms.setResultCallback(result -> {
            onAlgorithmResult(result);
            return kotlin.Unit.INSTANCE;
        });
    }

    private void onAlgorithmResult(AlgorithmResult result) {
        captureRepIfClosed(result);
        if (isSquatExercise) {
            onSquatResult(result);
            return;
        }
        if (isBenchPressExercise) {
            onBenchPressResult(result);
            return;
        }
        onCurlResult(result);
    }

    /**
     * Detecta el cierre de una nueva rep comparando repCount con el ultimo
     * valor visto. Cuando incrementa, captura un snapshot a partir de los
     * campos lastRep* del AlgorithmResult y lo añade a la lista de la sesion.
     */
    private void captureRepIfClosed(AlgorithmResult result) {
        int currentRepCount = result.getRepCount();
        if (currentRepCount <= lastSeenRepCount) return;

        long timestampOffsetMs = System.currentTimeMillis() - sessionStartMs;
        // Pueden haberse cerrado varias reps entre callbacks (raro pero posible).
        // Solo registramos UN snapshot — el algoritmo solo expone la ultima.
        // Si en el futuro queremos historico denso habria que emitir eventos.
        int repNumber = currentRepCount;
        PendingRep pendingRep;
        if (isSquatExercise) {
            pendingRep = PendingSessionBuilder.buildSquatRep(repNumber, timestampOffsetMs, result);
        } else if (isBenchPressExercise) {
            pendingRep = PendingSessionBuilder.buildBenchRep(repNumber, timestampOffsetMs, result);
        } else {
            pendingRep = PendingSessionBuilder.buildCurlRep(repNumber, timestampOffsetMs, result);
        }
        pendingReps.add(pendingRep);
        lastSeenRepCount = currentRepCount;
    }

    /**
     * Handler especifico del curl de biceps.
     *
     * Lo que muestra cada panel — todos en VIVO durante la repeticion:
     *
     *  1) PEAK ANGLE (metric_peak_angle) — angulo theta(t) en VIVO.
     *     Convencion MediaPipe (Serbest 2022, Fig 5): brazo extendido ~170-180°,
     *     pico de flexion ~30-50°. El numero principal sigue al codo cada frame.
     *     Codigo de color anclado a Liu et al. 2024 (arXiv:2402.11421) que fija
     *     el umbral de compensacion clara del hombro en 15° respecto al reposo:
     *       * shoulderCompensationDeg >= 15°  → ROJO  (compensacion confirmada)
     *       * shoulderCompensationDeg en [8°,15°) → AMBAR (zona de aviso)
     *       * resto / sin dato            → VERDE (forma limpia o pre-rep)
     *       * sin pose                    → "--" en plata
     *     Sub-texto "Pico XX°" = minimo theta observado en la rep en curso, o
     *     el de la ultima rep cerrada si no hay rep activa (Pinto 2012 ancla
     *     el objetivo en pico <= 60° para alcanzar ROM >= 110°).
     *
     *  2) STABILITY (metric_stability)
     *     Score 0-100 dominado por la compensacion del hombro en grados
     *     (Liu et al. 2024 arXiv:2402.11421), que es la senal directa de
     *     tecnica para el curl. Pondera secundariamente la perdida de
     *     velocidad (Sanchez-Medina 2011 / Rodriguez-Rosell 2023) y la
     *     desviacion del pico personal (E1 del algoritmo). Suavizado EMA.
     *
     *  3) Velocity (metric_curl_velocity) [Sanchez-Medina 2011 / VBT]
     *     Pico de velocidad angular concentrica (deg/s) de la rep en curso,
     *     fallback al peak de la ultima rep cerrada cuando el usuario baja
     *     el peso. Es la metrica de oro del Velocity Based Training:
     *     correlacion 0.91-0.97 con marcadores fisiologicos de fatiga
     *     (Sanchez-Medina & Gonzalez-Badillo 2011).
     *
     *  4) Live Flex (metric_curl_flex) [Pinto 2012 / Goto 2019]
     *     theta(t) actual normalizada al rango anatomico extendido(170°) →
     *     full flex(30°). Cada frame: 0% = brazo extendido, 100% = pico de
     *     flexion. Visualmente la barra del curl en directo. Pinto (2012)
     *     ancla el target ROM >=110° (= ~78% en este display), util como
     *     guia visual sin esperar a calibracion.
     *
     *  5) Alerta contextual (squat_alert_text)
     *     Prioriza compensacion de hombro > fatiga (VL>=20) > ROM parcial >
     *     calibracion > tecnica estable.
     */
    private void onCurlResult(AlgorithmResult result) {
        Double curAngle = result.getAngleDeg();
        if (curAngle != null) {
            currentAngle = curAngle;
            if (currentAngle > peakAngle) peakAngle = currentAngle;
        }

        // Compensacion de hombro (Liu 2024) — la usamos en panel 1 (color del
        // angulo) y en panel 2 (penalizacion de stability). Una sola lectura.
        Double shoulderShift = result.getShoulderCompensationDeg();

        // ── Panel 1: ÁNGULO — theta(t) en VIVO con color verde/rojo ─────────
        // Numero principal = angulo actual del codo, suavizado con EMA para
        // que NO salte frame a frame por jitter de MediaPipe (ya pasa por el
        // suavizador 1€ aguas arriba; aqui le añadimos una segunda pasada
        // ligera). Color por compensacion de hombro (umbral clave 15°): asi
        // el usuario ve el angulo subir/bajar de forma fluida y, en paralelo,
        // si su forma es limpia (verde) o esta cruzando el umbral (rojo).
        if (curAngle != null) {
            if (emaCurlAngle == null) emaCurlAngle = curAngle;
            else emaCurlAngle = emaCurlAngle + CURL_ANGLE_ALPHA * (curAngle - emaCurlAngle);

            metricPeakAngle.setText(String.format(Locale.US, "%.0f°", emaCurlAngle));

            int peakColor;
            if (shoulderShift != null && shoulderShift >= 15.0) {
                // Compensacion clara — el algoritmo dispara error E2.
                peakColor = ContextCompat.getColor(this, R.color.risk_red);
            } else if (shoulderShift != null && shoulderShift >= 8.0) {
                // Zona de aviso (mitad inferior del rango).
                peakColor = ContextCompat.getColor(this, R.color.toasted_almond);
            } else {
                // Sin compensacion / aun calibrando → forma limpia.
                peakColor = ContextCompat.getColor(this, R.color.improvement_green);
            }
            metricPeakAngle.setTextColor(peakColor);
        } else {
            metricPeakAngle.setText("--");
            metricPeakAngle.setTextColor(ContextCompat.getColor(this, R.color.white));
        }

        // Sub-texto: pico de flexion (min θ) de la rep en curso o ultima
        // cerrada. Pinto 2012 / Goto 2019 marcan target de full ROM >=110°,
        // i.e. pico <= 60° en convencion MediaPipe.
        Double livePeak = result.getCurrentRepPeakFlexionDeg();
        Double lastPeak = result.getLastRepPeakFlexionDeg();
        Double displayPeak = livePeak != null ? livePeak : lastPeak;
        if (displayPeak != null) {
            metricHipAngle.setVisibility(View.VISIBLE);
            metricHipAngle.setText(getString(R.string.camera_curl_peak_format, displayPeak));
            metricHipAngle.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
        } else {
            metricHipAngle.setVisibility(View.GONE);
        }

        // ── Panel 2: STABILITY ───────────────────────────────────────────────
        // Penalizacion CONTINUA por compensacion de hombro (Liu 2024). El
        // umbral SHOULDER_COMPENSATION_DEG=15° del algoritmo se traduce a
        // 50 puntos de penalizacion (~mitad del rango), de modo que cruzarlo
        // arrastra la estabilidad al 50% sin esperar a flag binario.
        double penaltyShoulder = 0.0;
        if (shoulderShift != null) {
            penaltyShoulder = Math.min(100.0, (shoulderShift / 15.0) * 50.0);
        }

        // Penalizacion menor por fatiga (Sanchez-Medina 2011) — VL40% → 40 puntos.
        double penaltyVL = 0.0;
        if (result.getVelocityLossPercent() != null) {
            penaltyVL = Math.min(40.0, result.getVelocityLossPercent());
        }

        // Penalizacion por desviacion del pico (E1) — error de 20° → 30 puntos.
        double penaltyError = 0.0;
        if (result.getErrorMagnitude() != null) {
            penaltyError = Math.min(30.0, result.getErrorMagnitude() * 1.5);
        }

        double targetStability = Math.max(0.0,
                100.0 - penaltyShoulder - penaltyVL - penaltyError);

        emaStability = emaStability + STABILITY_ALPHA * (targetStability - emaStability);
        int displayStability = (int) Math.round(emaStability);
        metricStability.setText(String.format(Locale.US, "%d%%", displayStability));

        int stabilityColor;
        if (displayStability > 70) {
            stabilityColor = ContextCompat.getColor(this, R.color.improvement_green);
        } else if (displayStability > 40) {
            stabilityColor = ContextCompat.getColor(this, R.color.toasted_almond);
        } else {
            stabilityColor = ContextCompat.getColor(this, R.color.risk_red);
        }
        metricStability.setTextColor(stabilityColor);

        // ── Panel 3: VELOCIDAD (°/s) — instantanea con EMA ligera ───────────
        // Antes mostrabamos el pico de la rep (solo subia, casi nunca bajaba):
        // sentir "vivo" requiere mostrar |omega| del frame actual. omega viene
        // del algoritmo en rad/s; convertimos a deg/s y suavizamos con un EMA
        // de alpha 0.45 para que siga al brazo sin parpadeos.
        //   - Activa (movimiento real): el numero sube y baja con cada fase.
        //   - Pausa (|omega| ~ 0): cae a "--" para no llenar la UI de ceros.
        // Color: VL del propio algoritmo (verde / ambar / rojo) marca fatiga.
        Double omegaRad = result.getAngularVelocity();
        if (omegaRad != null) {
            double instDegS = Math.abs(Math.toDegrees(omegaRad));
            if (emaCurlVelocity == null) emaCurlVelocity = instDegS;
            else emaCurlVelocity = emaCurlVelocity
                    + CURL_VEL_ALPHA * (instDegS - emaCurlVelocity);
        }

        if (emaCurlVelocity != null && emaCurlVelocity > 5.0) {
            metricCurlVelocity.setText(
                    String.format(Locale.US, "%.0f", emaCurlVelocity));
            int velColor;
            Double vl = result.getVelocityLossPercent();
            if (vl != null && vl >= 40.0) {
                velColor = ContextCompat.getColor(this, R.color.risk_red);
            } else if (vl != null && vl >= 20.0) {
                velColor = ContextCompat.getColor(this, R.color.toasted_almond);
            } else {
                velColor = ContextCompat.getColor(this, R.color.improvement_green);
            }
            metricCurlVelocity.setTextColor(velColor);
        } else {
            metricCurlVelocity.setText("--");
            metricCurlVelocity.setTextColor(
                    ContextCompat.getColor(this, R.color.silver_2));
        }

        // ── Panel 4: FLEXIÓN (%) — theta(t) en % del rango anatomico ────────
        // Brazo extendido (~170°) = 0%, pico anatomico (~30°) = 100%. Cada
        // frame el porcentaje sigue al antebrazo. ROM objetivo >=110° = ~78%.
        // Aplicamos el mismo EMA que al angulo (de hecho derivamos el % a
        // partir del angulo ya suavizado para coherencia 1:1 entre paneles).
        if (curAngle != null) {
            final double EXTENDED_DEG  = 170.0;
            final double FULL_FLEX_DEG = 30.0;
            double range = EXTENDED_DEG - FULL_FLEX_DEG; // 140°
            double rawFlexPct = ((EXTENDED_DEG - curAngle) / range) * 100.0;
            rawFlexPct = Math.max(0.0, Math.min(100.0, rawFlexPct));
            if (emaCurlFlex == null) emaCurlFlex = rawFlexPct;
            else emaCurlFlex = emaCurlFlex
                    + CURL_ANGLE_ALPHA * (rawFlexPct - emaCurlFlex);

            metricCurlFlex.setText(String.format(Locale.US, "%.0f", emaCurlFlex));
            int flexColor;
            if (emaCurlFlex < 30.0) {
                flexColor = ContextCompat.getColor(this, R.color.silver_2);
            } else {
                flexColor = ContextCompat.getColor(this, R.color.improvement_green);
            }
            metricCurlFlex.setTextColor(flexColor);
        } else {
            metricCurlFlex.setText("--");
            metricCurlFlex.setTextColor(
                    ContextCompat.getColor(this, R.color.silver_2));
        }

        // Para la alerta seguimos pasando el ROM de la ultima rep cerrada
        // — la regla "(3) ROM incompleto" sigue siendo Pinto 2012 / Goto 2019.
        Double displayRom = result.getCurrentRepRomDeg();
        if (displayRom == null) displayRom = result.getLastRepRomDeg();

        // ── Panel 5: alerta contextual ───────────────────────────────────────
        updateCurlAlert(result, shoulderShift, displayRom, livePeak);
    }

    /**
     * Linea de estado bajo los cuatro paneles. Cada mensaje apunta DIRECTO al
     * panel afectado, asi el usuario sabe por que ese panel cambio de color.
     * En estado OK, en lugar de un texto generico, mostramos un resumen vivo
     * con los tres datos mas accionables: rep actual, pico de la ultima rep
     * y velocidad pico de la ultima rep — todos numeros ya familiares de los
     * paneles de arriba.
     *
     * Prioridad:
     *   (1) Compensacion de hombro >15°   → ROJO   apunta a ÁNGULO
     *   (2) Fatiga VL>=20%                → ROJO   apunta a VELOCIDAD
     *   (3) ROM ultima rep <110°          → ÁMBAR  apunta a FLEXIÓN
     *   (4) Calibrando (<3 reps)          → GRIS   informativo
     *   (5) Todo OK                       → VERDE  resumen vivo (Rep · Pico · Vel pico)
     */
    private void updateCurlAlert(AlgorithmResult result,
                                 Double shoulderShift,
                                 Double displayRom,
                                 Double livePeak) {
        if (squatAlertText == null) return;
        squatAlertText.setVisibility(View.VISIBLE);

        int repCount = result.getRepCount();
        Double vl = result.getVelocityLossPercent();

        // Pre-arranque: aun no se ha iniciado el primer curl.
        if (repCount == 0 && livePeak == null && (shoulderShift == null || shoulderShift < 2.0)) {
            squatAlertText.setText(R.string.camera_curl_status_ready);
            squatAlertText.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
            return;
        }

        // (1) Compensacion de hombro — apunta al panel ÁNGULO (que ahora mismo
        // esta en rojo por el mismo motivo).
        if (shoulderShift != null && shoulderShift > 15.0) {
            squatAlertText.setText(getString(R.string.camera_curl_alert_shoulder, shoulderShift));
            squatAlertText.setTextColor(ContextCompat.getColor(this, R.color.risk_red));
            return;
        }

        // (2) Fatiga VL≥20% — apunta al panel VELOCIDAD (que ahora mismo lleva
        // color ambar/rojo por VL).
        if (vl != null && vl >= 20.0) {
            squatAlertText.setText(getString(R.string.camera_curl_alert_fatigue, vl));
            squatAlertText.setTextColor(ContextCompat.getColor(this, R.color.risk_red));
            return;
        }

        // (3) ROM incompleto en la ULTIMA rep cerrada (no en una rep activa
        // que aun no ha llegado al pico). repCount > 0 garantiza que hay una
        // rep terminada; livePeak == null indica que no estamos en concentrica.
        // Apunta al panel FLEXIÓN.
        if (repCount > 0 && livePeak == null
                && displayRom != null && displayRom < 110.0) {
            squatAlertText.setText(getString(R.string.camera_curl_alert_partial_rom, displayRom));
            squatAlertText.setTextColor(ContextCompat.getColor(this, R.color.toasted_almond));
            return;
        }

        // (4) Calibrando — referencias se congelan a las 3 reps.
        if (repCount < 3) {
            squatAlertText.setText(getString(R.string.camera_curl_calibrando, repCount));
            squatAlertText.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
            return;
        }

        // (5) Estado OK → resumen vivo. Tomamos el pico y la velocidad pico de
        // la ULTIMA rep cerrada (no la rep activa, para que no parpadee mientras
        // bajas el peso). Si por algun motivo falta uno, degradamos a un formato
        // mas corto en vez de poner placeholders.
        Double summaryPeak     = result.getLastRepPeakFlexionDeg();
        Double summaryPeakVel  = result.getConcentricPeakVelocityDegS();
        if (summaryPeak != null && summaryPeakVel != null) {
            squatAlertText.setText(getString(
                    R.string.camera_curl_summary_full_format,
                    repCount, summaryPeak, summaryPeakVel));
        } else if (summaryPeak != null) {
            squatAlertText.setText(getString(
                    R.string.camera_curl_summary_partial_format,
                    repCount, summaryPeak));
        } else {
            squatAlertText.setText(getString(
                    R.string.camera_curl_summary_repcount_format, repCount));
        }
        squatAlertText.setTextColor(ContextCompat.getColor(this, R.color.improvement_green));
    }

    private void onSquatResult(AlgorithmResult result) {
        int repCount = result.getRepCount();

        Double kneeAngle = result.getKneeAngleDeg();
        if (kneeAngle != null) {
            metricPeakAngle.setText(String.format(Locale.US, "%.0f°", kneeAngle));
        }

        metricHipAngle.setVisibility(View.VISIBLE);
        Double hipAngle = result.getHipAngleDeg();
        if (hipAngle != null) {
            metricHipAngle.setText(getString(R.string.camera_hip_angle_format, hipAngle));
        } else {
            metricHipAngle.setText(R.string.camera_hip_placeholder);
        }

        Double cvt = result.getCvtPercent();
        if (cvt != null) {
            lastSquatCvtDisplay = cvt;
            metricStability.setText(String.format(Locale.US, "%.1f%%", cvt));
        } else if (lastSquatCvtDisplay != null) {
            metricStability.setText(String.format(Locale.US, "%.1f%%", lastSquatCvtDisplay));
        } else if (repCount > 0) {
            metricStability.setText(getString(R.string.camera_squat_cvt_calibrando, repCount));
        } else {
            metricStability.setText("--");
        }

        int stabilityColor;
        if (cvt == null || cvt < 5.0) {
            stabilityColor = ContextCompat.getColor(this, R.color.improvement_green);
        } else if (cvt <= 10.0) {
            stabilityColor = ContextCompat.getColor(this, R.color.toasted_almond);
        } else {
            stabilityColor = ContextCompat.getColor(this, R.color.risk_red);
        }
        metricStability.setTextColor(stabilityColor);

        Double velocityLoss = result.getVelocityLossPercent();
        String velocityLossText = "--";
        if (velocityLoss != null) {
            double retention = Math.max(0.0, 100.0 - velocityLoss);
            lastSquatRetentionDisplay = retention;
            progressConsistency.setProgress((int) Math.round(retention));
            velocityLossText = String.format(Locale.US, "%.1f%%", velocityLoss);
        } else if (lastSquatRetentionDisplay != null) {
            progressConsistency.setProgress((int) Math.round(lastSquatRetentionDisplay));
        } else if (repCount > 0) {
            progressConsistency.setProgress(100);
        }

        // En release mantenemos el hint funcional; en debug mostramos trazas de validacion.
        boolean isDebuggable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (isDebuggable) {
            String cvtDebugText = cvt != null
                    ? String.format(Locale.US, "%.1f%%", cvt)
                    : (lastSquatCvtDisplay != null
                    ? String.format(Locale.US, "%.1f%%", lastSquatCvtDisplay)
                    : "--");
            metricConsistencyHint.setText(String.format(Locale.US, "Reps:%d | VL:%s | CVT:%s", repCount, velocityLossText, cvtDebugText));
        } else {
            metricConsistencyHint.setText(R.string.camera_vl20_hint);
        }

        updateSquatAlert(result, cvt, velocityLoss, repCount);
    }

    private void updateSquatAlert(AlgorithmResult result, Double cvt, Double velocityLoss, int repCount) {
        if (squatAlertText == null) {
            return;
        }

        int color = ContextCompat.getColor(this, R.color.improvement_green);
        int messageRes = R.string.camera_squat_status_ready;

        if (repCount <= 0) {
            if (messageRes != lastSquatAlertTextRes || color != lastSquatAlertColor) {
                squatAlertText.setText(messageRes);
                squatAlertText.setTextColor(color);
                lastSquatAlertTextRes = messageRes;
                lastSquatAlertColor = color;
            }
            squatAlertText.setVisibility(View.VISIBLE);
            return;
        }

        if (repCount < 2 && cvt == null && velocityLoss == null) {
            color = ContextCompat.getColor(this, R.color.silver_2);
            if (color != lastSquatAlertColor) {
                squatAlertText.setTextColor(color);
                lastSquatAlertColor = color;
            }
            String message = getString(R.string.camera_squat_status_calibrando, repCount);
            if (!message.contentEquals(squatAlertText.getText())) {
                squatAlertText.setText(message);
            }
            lastSquatAlertTextRes = -1;
            squatAlertText.setVisibility(View.VISIBLE);
            return;
        }

        // Severidad 1: Problemas críticos (profundidad + inclinación)
        if (result.getDepthInsufficient() && result.getTrunkLeanRisk()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_depth_and_trunk;
        }
        // Severidad 2: Solo profundidad insuficiente
        else if (result.getDepthInsufficient()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_depth;
        }
        // Severidad 3: Solo inclinación de tronco
        else if (result.getTrunkLeanRisk()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_trunk;
        }
        // Severidad 4: Fatiga significativa (prioridad sobre inestabilidad)
        else if (result.getFatigueDetected() || (velocityLoss != null && velocityLoss >= 20.0)) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_fatigue;
        }
        // Severidad 5: Inestabilidad severa (CVT > 10)
        else if (cvt != null && cvt > 10.0) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_instability;
        }
        // Advertencia: Variabilidad moderada (5-10)
        else if (cvt != null && cvt >= 5.0) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_squat_alert_variability;
        }
        // OK: Todo bien
        else {
            color = ContextCompat.getColor(this, R.color.improvement_green);
            messageRes = R.string.camera_squat_status_ok;
        }

        if (messageRes != lastSquatAlertTextRes || color != lastSquatAlertColor) {
            squatAlertText.setText(messageRes);
            squatAlertText.setTextColor(color);
            lastSquatAlertTextRes = messageRes;
            lastSquatAlertColor = color;
        }
        squatAlertText.setVisibility(View.VISIBLE);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Bench Press — panel de 5 reglas en tiempo real
    // ══════════════════════════════════════════════════════════════════════

    // Estados de severidad por regla. Se usan para decidir color del punto
    // indicador y del valor numerico en cada fila.
    private static final int STATUS_NEUTRAL = 0; // sin dato / no evaluable
    private static final int STATUS_OK      = 1; // verde
    private static final int STATUS_WARN    = 2; // ambar
    private static final int STATUS_RISK    = 3; // rojo

    private void resetBenchPanel() {
        // Alert banner en estado neutro (esperando readiness)
        benchAlertBanner.setText(R.string.camera_bench_status_ready);
        benchAlertBanner.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
        benchAlertBanner.setBackgroundResource(R.drawable.bg_alert_banner_neutral);

        benchRepCount.setText("0");
        benchElbowAngle.setText("--");
        updateReadinessPill(false, false);

        String dash = getString(R.string.bench_status_placeholder);
        setRuleStatus(benchRuleGripDot, benchRuleGripValue, dash, STATUS_NEUTRAL);
        setRuleStatus(benchRuleAbductionDot, benchRuleAbductionValue, dash, STATUS_NEUTRAL);
        setRuleStatus(benchRuleSymmetryDot, benchRuleSymmetryValue, dash, STATUS_NEUTRAL);
        setRuleStatus(benchRuleDepthDot, benchRuleDepthValue, dash, STATUS_NEUTRAL);
        setRuleStatus(benchRuleExtensionDot, benchRuleExtensionValue, dash, STATUS_NEUTRAL);
    }

    private void onBenchPressResult(AlgorithmResult result) {
        // Header: reps + angulo de codo actual + readiness
        benchRepCount.setText(String.valueOf(result.getRepCount()));

        Double elbowAngle = result.getElbowAngleDeg();
        if (elbowAngle != null) {
            benchElbowAngle.setText(String.format(Locale.US, "%.0f°", elbowAngle));
        } else {
            benchElbowAngle.setText("--");
        }
        boolean hasPose = elbowAngle != null;
        updateReadinessPill(result.getReadinessReady(), hasPose);

        // ── Regla 1: Ancho de agarre ─────────────────────────────────────
        // Calibracion con landmarks de muneca de MediaPipe:
        //  - < 1.3 : estrecho (ambar)
        //  - 1.3 - 2.1 : optimo (verde)
        //  - 2.1 - 2.5 : ancho moderado (ambar)
        //  - > 2.5 : excesivo / flag gripTooWide (rojo)
        // Ver BenchPressBiomechanicsAlgorithm.kt para el porque la tesis
        // cita 1.5 y aqui usamos 2.1 como techo del rango optimo.
        Double grip = result.getGripWidthRatio();
        if (grip != null) {
            String gripTxt = String.format(Locale.US, "%.2fx", grip);
            int status;
            if (result.getGripTooWide() || grip > 2.5) {
                status = STATUS_RISK;
            } else if (grip > 2.1 || grip < 1.3) {
                status = STATUS_WARN;
            } else {
                status = STATUS_OK;
            }
            setRuleStatus(benchRuleGripDot, benchRuleGripValue, gripTxt, status);
        } else {
            setRuleStatus(benchRuleGripDot, benchRuleGripValue, "--", STATUS_NEUTRAL);
        }

        // ── Regla 2: Abduccion de hombro ─────────────────────────────────
        Double abduction = result.getShoulderAbductionDeg();
        if (abduction != null) {
            String abTxt = String.format(Locale.US, "%.0f°", abduction);
            int status;
            if (abduction > 90.0) {
                status = STATUS_RISK;
            } else if (abduction > 45.0) {
                status = STATUS_WARN;
            } else {
                status = STATUS_OK;
            }
            setRuleStatus(benchRuleAbductionDot, benchRuleAbductionValue, abTxt, status);
        } else {
            setRuleStatus(benchRuleAbductionDot, benchRuleAbductionValue, "--", STATUS_NEUTRAL);
        }

        // ── Regla 3: Simetria bilateral ─────────────────────────────────
        Double asymmetry = result.getBilateralAsymmetryDeg();
        if (asymmetry != null) {
            String symTxt = String.format(Locale.US, "%.1f°", asymmetry);
            int status;
            if (asymmetry >= 8.0 || result.getBilateralAsymmetry()) {
                status = STATUS_RISK;
            } else if (asymmetry >= 2.75) {
                status = STATUS_WARN;
            } else {
                status = STATUS_OK;
            }
            setRuleStatus(benchRuleSymmetryDot, benchRuleSymmetryValue, symTxt, status);
        } else {
            setRuleStatus(benchRuleSymmetryDot, benchRuleSymmetryValue, "--", STATUS_NEUTRAL);
        }

        // ── Regla 4: Profundidad ────────────────────────────────────────
        // Durante la rep: mostrar minimo de codo alcanzado y si paso el torso.
        // Tras la rep: si la ultima fue insuficiente, marcar rojo hasta que
        // empiece una nueva rep (cuando minElbow se reinicie a live).
        Double minElbow = result.getCurrentRepMinElbowAngleDeg();
        Boolean belowTorso = result.getElbowBelowTorsoLive();
        String depthTxt;
        int depthStatus;
        if (result.getDepthInsufficientBench() && minElbow == null) {
            // No hay rep activa y la ultima fue insuficiente
            depthTxt = getString(R.string.camera_value_missing);
            depthStatus = STATUS_RISK;
        } else if (minElbow != null) {
            depthTxt = String.format(Locale.US, "%.0f° min", minElbow);
            if (Boolean.TRUE.equals(belowTorso)) {
                depthStatus = STATUS_OK;
            } else {
                depthStatus = STATUS_WARN;
            }
        } else {
            depthTxt = "--";
            depthStatus = STATUS_NEUTRAL;
        }
        setRuleStatus(benchRuleDepthDot, benchRuleDepthValue, depthTxt, depthStatus);

        // ── Regla 5: Extension ──────────────────────────────────────────
        Double maxElbow = result.getCurrentRepMaxElbowAngleDeg();
        String extTxt;
        int extStatus;
        if (result.getExtensionIncomplete() && maxElbow == null) {
            Double extMissDeg = result.getExtensionIncompleteDeg();
            if (extMissDeg != null) {
                extTxt = String.format(Locale.US, "-%.0f°", extMissDeg);
            } else {
                extTxt = getString(R.string.camera_value_missing);
            }
            extStatus = STATUS_RISK;
        } else if (maxElbow != null) {
            extTxt = String.format(Locale.US, "%.0f° pico", maxElbow);
            if (maxElbow >= 176.0) {
                extStatus = STATUS_OK;
            } else if (maxElbow >= 165.0) {
                extStatus = STATUS_WARN;
            } else {
                extStatus = STATUS_NEUTRAL;
            }
        } else {
            extTxt = "--";
            extStatus = STATUS_NEUTRAL;
        }
        setRuleStatus(benchRuleExtensionDot, benchRuleExtensionValue, extTxt, extStatus);

        // ── Alert banner prominente (texto y fondo grande) ──────────────
        updateBenchAlertBanner(result);
    }

    private void updateReadinessPill(boolean ready, boolean hasPose) {
        if (benchReadinessPill == null) return;
        if (!hasPose) {
            benchReadinessPill.setText(R.string.bench_readiness_not_ready);
            benchReadinessPill.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
            return;
        }
        if (ready) {
            benchReadinessPill.setText(R.string.bench_readiness_ready);
            benchReadinessPill.setTextColor(ContextCompat.getColor(this, R.color.improvement_green));
        } else {
            benchReadinessPill.setText(R.string.bench_readiness_stabilizing);
            benchReadinessPill.setTextColor(ContextCompat.getColor(this, R.color.toasted_almond));
        }
    }

    private void updateBenchAlertBanner(AlgorithmResult result) {
        Double abduction = result.getShoulderAbductionDeg();
        Double velocityLoss = result.getVelocityLossPercent();
        Double grip = result.getGripWidthRatio();
        Double symmetry = result.getBilateralAsymmetryDeg();

        int color;
        int messageRes;
        int bgRes;

        // Umbral de asimetria "visible" para banner: tambien se muestra en
        // ambar a partir del umbral biomecanico de 2.75 grados, aunque el
        // flag bilateralAsymmetry del algoritmo requiera mas frames y mayor
        // magnitud. Asi el usuario recibe feedback intermedio sin esperar a
        // que se dispare el error tecnico.
        boolean symmetryWarn = symmetry != null && symmetry >= 2.75;

        // Gate por "hay pose" en vez de "readiness READY": solo mostramos
        // el mensaje neutro de encuadre cuando no hay datos de codo. Si hay
        // pose, el usuario recibe las alertas aunque el pipeline aun este
        // estabilizando el filtro. La pildora de readiness sigue siendo la
        // indicacion honesta de calidad de senal.
        boolean hasPose = result.getElbowAngleDeg() != null;

        if (!hasPose) {
            color = ContextCompat.getColor(this, R.color.silver_2);
            messageRes = R.string.camera_bench_status_ready;
            bgRes = R.drawable.bg_alert_banner_neutral;
        } else if (abduction != null && abduction > 90.0) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_bench_alert_abduction_critical;
            bgRes = R.drawable.bg_alert_banner_red;
        } else if (result.getStickingPeriodDetected()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_bench_alert_sticking;
            bgRes = R.drawable.bg_alert_banner_red;
        } else if (velocityLoss != null && velocityLoss >= 25.0) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_bench_alert_fatigue_vl25;
            bgRes = R.drawable.bg_alert_banner_red;
        } else if (result.getGripTooWide() || (grip != null && grip > 2.5)) {
            // Agarre critico (>2.5x biacromial): riesgo estructural de
            // hombro — rojo directo, no ambar.
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_bench_alert_grip;
            bgRes = R.drawable.bg_alert_banner_red;
        } else if (result.getDepthInsufficientBench() && result.getExtensionIncomplete()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_bench_alert_depth_and_extension;
            bgRes = R.drawable.bg_alert_banner_red;
        } else if (result.getDepthInsufficientBench()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_bench_alert_depth;
            bgRes = R.drawable.bg_alert_banner_red;
        } else if (result.getExtensionIncomplete()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_bench_alert_extension;
            bgRes = R.drawable.bg_alert_banner_red;
        } else if (abduction != null && abduction > 45.0) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_bench_alert_abduction_warning;
            bgRes = R.drawable.bg_alert_banner_amber;
        } else if (result.getBilateralAsymmetry() || symmetryWarn) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_bench_alert_asymmetry;
            bgRes = R.drawable.bg_alert_banner_amber;
        } else if (velocityLoss != null && velocityLoss >= 15.0) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_bench_alert_fatigue_vl15;
            bgRes = R.drawable.bg_alert_banner_amber;
        } else if (grip != null && grip > 2.1) {
            // Ancho moderado (2.1-2.5): ambar, sugerencia pero sin flag rojo.
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_bench_alert_grip_wide;
            bgRes = R.drawable.bg_alert_banner_amber;
        } else if (grip != null && grip < 1.3) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_bench_alert_grip_narrow;
            bgRes = R.drawable.bg_alert_banner_amber;
        } else {
            color = ContextCompat.getColor(this, R.color.improvement_green);
            messageRes = R.string.camera_bench_status_ok;
            bgRes = R.drawable.bg_alert_banner_green;
        }

        benchAlertBanner.setText(messageRes);
        benchAlertBanner.setTextColor(color);
        benchAlertBanner.setBackgroundResource(bgRes);
    }

    /**
     * Pinta el punto indicador y el valor de una fila de regla segun severidad.
     * El punto usa el drawable {@code bg_status_dot} (forma oval) al que le
     * mutamos el color via GradientDrawable; asi un unico drawable sirve para
     * los cuatro estados.
     */
    private void setRuleStatus(View dot, TextView value, String text, int status) {
        if (dot == null || value == null) return;
        int dotColor;
        int textColor;
        switch (status) {
            case STATUS_OK:
                dotColor = ContextCompat.getColor(this, R.color.improvement_green);
                textColor = ContextCompat.getColor(this, R.color.improvement_green);
                break;
            case STATUS_WARN:
                dotColor = ContextCompat.getColor(this, R.color.toasted_almond);
                textColor = ContextCompat.getColor(this, R.color.toasted_almond);
                break;
            case STATUS_RISK:
                dotColor = ContextCompat.getColor(this, R.color.risk_red);
                textColor = ContextCompat.getColor(this, R.color.risk_red);
                break;
            case STATUS_NEUTRAL:
            default:
                dotColor = ContextCompat.getColor(this, R.color.silver_2);
                textColor = ContextCompat.getColor(this, R.color.silver_2);
                break;
        }
        value.setText(text);
        value.setTextColor(textColor);
        // Teñimos el drawable oval sin mutar el estado compartido
        android.graphics.drawable.Drawable bg = dot.getBackground();
        if (bg != null) {
            android.graphics.drawable.Drawable mutable = bg.mutate();
            if (mutable instanceof GradientDrawable) {
                ((GradientDrawable) mutable).setColor(dotColor);
            }
        }
    }

    // ── Resto sin cambios ─────────────────────────────────────────────────────

    private void setupToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finishSession());
        findViewById(R.id.btn_settings).setOnClickListener(v -> {
            if (cameraViewManager != null) {
                cameraViewManager.switchCamera();
            }
        });
    }

    private void setupBottomNav() {
        findViewById(R.id.nav_home).setOnClickListener(v -> finishSession());
        findViewById(R.id.nav_fab).setOnClickListener(v -> finishSession());
        findViewById(R.id.nav_stats).setOnClickListener(v -> finishSession());
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishSession();
            }
        });
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startCamera() {
        cameraViewManager = new CameraViewManager(this, this, cameraContainer);
        cameraViewManager.start();
        startTimer();
    }

    private void startTimer() {
        timerRunning = true;
        timerHandler.postDelayed(timerRunnable, 1000);
    }

    private void stopTimer() {
        timerRunning = false;
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void finishSession() {
        // Idempotente: si ya estamos cerrando la sesion (por ej. el usuario
        // pulsa el back de sistema y luego el del top bar), no abrir Summary
        // dos veces.
        if (sessionFinishing) return;
        sessionFinishing = true;
        stopTimer();

        int totalReps = pendingReps.size();
        boolean analyzed = isAnalyzedExercise;

        // Caso 0 reps (o ejercicio sin analisis): no hay nada que guardar ni
        // que mostrar en summary — vamos directo a home con un toast.
        if (!analyzed || totalReps == 0) {
            if (analyzed) {
                Toast.makeText(this, R.string.session_no_reps_toast, Toast.LENGTH_SHORT).show();
            }
            navigateHome();
            return;
        }

        String exerciseType = resolveExerciseType();
        AlgorithmResult finalResult = algorithms != null ? algorithms.getCurrentResult() : null;
        long endMs = System.currentTimeMillis();

        PendingSessionData data = PendingSessionBuilder.build(
                exerciseType,
                exerciseDisplayName,
                sessionStartMs,
                endMs,
                elapsedSeconds,
                autoSaveEnabled,
                finalResult,
                new ArrayList<>(pendingReps)
        );

        Intent intent = new Intent(this, SummaryActivity.class);
        intent.putExtra(EXTRA_EXERCISE_TYPE, exerciseType);
        intent.putExtra(EXTRA_EXERCISE_NAME, exerciseDisplayName);

        if (autoSaveEnabled) {
            // Persistimos en background. La nueva pantalla solo refleja que la
            // sesion ya quedo guardada — el usuario no decide nada.
            RizeApplication.get().getSessionRepository().saveSessionAsync(data);
            intent.putExtra(EXTRA_ALREADY_SAVED, true);
        } else {
            // Pasamos los datos via singleton in-memory. SummaryActivity decide.
            PendingSessionHolder.INSTANCE.set(data);
            intent.putExtra(EXTRA_ALREADY_SAVED, false);
        }

        startActivity(intent);
        finish();
    }

    private String resolveExerciseType() {
        if (isSquatExercise) return WorkoutSession.TYPE_SQUAT;
        if (isBenchPressExercise) return WorkoutSession.TYPE_BENCH;
        return WorkoutSession.TYPE_CURL;
    }

    private void navigateHome() {
        Intent intent = new Intent(this, HomepageActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTimer();
        if (isAnalyzedExercise) {
            PoseDataManager.INSTANCE.setPoseDataListener(null);
        }
        if (algorithms != null) {
            algorithms.reset();
        }
        if (cameraViewManager != null) {
            cameraViewManager.release();
        }
    }
}
