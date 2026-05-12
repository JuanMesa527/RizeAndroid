package com.rize.rizeandroid.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.rize.rizeandroid.R;
import com.rize.rizeandroid.biomechanics.AlgorithmResult;
import com.rize.rizeandroid.biomechanics.Algorithms;
import com.rize.rizeandroid.biomechanics.ErrorLevel;
import com.rize.rizeandroid.biomechanics.ExerciseType;
import com.rize.rizeandroid.biomechanics.SquatDepthCategory;
import com.rize.rizeandroid.biomechanics.SquatTrunkCategory;
import com.rize.rizeandroid.camera.CameraViewManager;
import com.rize.rizeandroid.data.PendingRep;
import com.rize.rizeandroid.data.PendingSessionBuilder;
import com.rize.rizeandroid.data.PendingSessionData;
import com.rize.rizeandroid.data.PendingSessionHolder;
import com.rize.rizeandroid.data.entity.WorkoutSession;
import com.rize.rizeandroid.pose.PoseDataManager;
import com.rize.rizeandroid.RizeApplication;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CameraActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST = 100;
    public static final String EXTRA_AUTO_SAVE     = "auto_save";
    public static final String EXTRA_EXERCISE_TYPE = "exercise_type";
    public static final String EXTRA_EXERCISE_NAME = "exercise_name_display";
    public static final String EXTRA_ALREADY_SAVED = "already_saved";
    public static final String EXTRA_FRONT_CAMERA  = "front_camera";

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
    /** Squat-only: large hip angle in squat_hip_metrics_column; curl keeps metric_hip_angle inline. */
    private View squatHipMetricsColumn;
    private TextView metricSquatHipValue;
    private TextView metricStabilityLabel;
    private TextView metricConsistencyLabel;
    private TextView metricConsistencyHint;
    private TextView squatAlertText;
    private View squatLiveHeader;
    private TextView squatAlertBanner;
    private TextView squatRepCount;
    // Curl-specific: card de Velocity + Live Flex que sustituye al
    // progress_consistency cuando el ejercicio activo es curl. consistencyCard
    // es el contenedor del progress bar para poder ocultarlo en curl.
    private View consistencyCard;
    private View curlMetricsRow;
    private TextView metricCurlVelocity;
    private TextView metricCurlFlex;
    /** Banner de estado del curl — análogo al squat_alert_banner. */
    private TextView curlAlertBanner;

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
    private Double emaCurlFlex     = null; // unused after flex→reps change, kept for reset guard
    private Double emaCurlVelocity = null;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private int elapsedSeconds = 0;
    private boolean timerRunning = false;

    // ── Extreme alert overlay ─────────────────────────────────────────────────
    private View extremeAlertOverlay;
    private TextView tvAlertOverlayTitle;
    private TextView tvAlertOverlayMessage;

    // ── Curl live overlay (flotante semitransparente en tiempo real) ──────────
    private LinearLayout curlLiveOverlay;
    private TextView curlOverlayIcon;
    private TextView curlOverlayTitle;
    private TextView curlOverlayMessage;
    // Contadores de frames para condiciones que requieren persistencia mínima
    private int curlNoPoseFrames    = 0;
    private int curlHighAngleFrames = 0;
    private int curlWristHighFrames = 0;   // muñeca sobre el hombro
    private int curlShoulderAlertFrames = 0; // histeresis del hombro — trigger
    private int curlShoulderClearFrames = 0; // histeresis del hombro — clear
    private boolean curlShoulderAlertActive = false; // estado mostrado en UI
    // Aviso por rep: se muestra al cerrar cada rep y dura REP_FEEDBACK_MS ms
    private int lastFeedbackRepCount = 0;
    private static final long REP_FEEDBACK_MS = 2_500; // 2.5 s en pantalla
    private boolean repFeedbackActive = false;
    // Histéresis del color del ángulo (evita parpadeos por jitter del hombro)
    private int curlAngleColorState = 0; // 0=verde, 1=amarillo, 2=rojo
    private int curlAngleColorFrames = 0;
    private static final int CURL_COLOR_CHANGE_FRAMES = 8; // ~0.27s para cambiar color
    private static final int CURL_NO_POSE_FRAMES    = 90;  // 3 s @ 30 Hz
    private static final int CURL_HIGH_ANGLE_FRAMES = 60;  // 2 s @ 30 Hz
    private static final int CURL_WRIST_HIGH_FRAMES = 15;  // 0.5 s @ 30 Hz
    private static final int CURL_SHOULDER_TRIGGER_FRAMES = 20; // 0.67 s antes de mostrar
    private static final int CURL_SHOULDER_CLEAR_FRAMES   = 15; // 0.5 s quieto antes de ocultar
    // Control de duración mínima y dismiss por tap
    private long curlOverlayShowUntilMs      = 0;   // el overlay no se oculta antes de este ts
    private long curlOverlayDismissedUntilMs = 0;   // cooldown tras tap del usuario
    private static final long CURL_OVERLAY_MIN_SHOW_MS  = 6_000;  // mínimo 6 s en pantalla
    private static final long CURL_OVERLAY_COOLDOWN_MS  = 8_000;  // 8 s de cooldown post-tap
    private final Handler alertHandler = new Handler(Looper.getMainLooper());
    private Runnable alertDismissTask;

    private long lastValidPoseMs = 0;
    private boolean noPoseAlertActive = false;
    private long severeErrorStartMs = 0;
    private boolean severeFormAlertShown = false;
    private int consecutiveInjuryReps = 0;
    private int lastRepCountForInjury = 0;
    private boolean injuryAlertShown = false;
    private boolean benchStickingPopupShown = false;

    private static final long NO_POSE_THRESHOLD_MS  = 15_000;
    private static final long SEVERE_FORM_THRESHOLD_MS = 5_000;
    private static final int  INJURY_RISK_REPS      = 3;
    private static final long ALERT_AUTO_DISMISS_MS = 5_000;
    // Guard de idempotencia para finishSession(). El back fisico, el gesto
    // de borde y el boton del top bar comparten el mismo flujo, por lo que
    // necesitamos asegurar que SummaryActivity solo se abre una vez por sesion.
    private boolean sessionFinishing = false;

    // ── Persistencia de sesion ────────────────────────────────────────────────
    private boolean autoSaveEnabled   = false;
    private boolean startFrontCamera   = true;
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
        autoSaveEnabled   = getIntent().getBooleanExtra(EXTRA_AUTO_SAVE, true);
        startFrontCamera  = getIntent().getBooleanExtra(EXTRA_FRONT_CAMERA, true);
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
        squatHipMetricsColumn  = findViewById(R.id.squat_hip_metrics_column);
        metricSquatHipValue    = findViewById(R.id.metric_squat_hip_value);
        metricStabilityLabel   = findViewById(R.id.metric_stability_label);
        metricConsistencyLabel = findViewById(R.id.metric_consistency_label);
        metricConsistencyHint  = findViewById(R.id.metric_consistency_hint);
        squatAlertText         = findViewById(R.id.squat_alert_text);
        squatLiveHeader        = findViewById(R.id.squat_live_header);
        squatAlertBanner       = findViewById(R.id.squat_alert_banner);
        squatRepCount          = findViewById(R.id.squat_rep_count);
        // Curl-specific
        consistencyCard        = findViewById(R.id.consistency_card);
        curlMetricsRow         = findViewById(R.id.curl_metrics_row);
        metricCurlVelocity     = findViewById(R.id.metric_curl_velocity);
        metricCurlFlex         = findViewById(R.id.metric_curl_flex);
        curlAlertBanner        = findViewById(R.id.curl_alert_banner);

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

        extremeAlertOverlay   = findViewById(R.id.extreme_alert_overlay);
        tvAlertOverlayTitle   = findViewById(R.id.tv_alert_overlay_title);
        tvAlertOverlayMessage = findViewById(R.id.tv_alert_overlay_message);
        if (extremeAlertOverlay != null) {
            extremeAlertOverlay.setOnClickListener(v -> dismissExtremeAlert());
        }

        curlLiveOverlay  = findViewById(R.id.curl_live_overlay);
        curlOverlayIcon  = findViewById(R.id.curl_overlay_icon);
        curlOverlayTitle = findViewById(R.id.curl_overlay_title);
        curlOverlayMessage = findViewById(R.id.curl_overlay_message);

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
            if (squatHipMetricsColumn != null) {
                squatHipMetricsColumn.setVisibility(View.GONE);
            }
            if (squatAlertText != null) {
                squatAlertText.setVisibility(View.GONE);
            }
            if (squatLiveHeader != null) {
                squatLiveHeader.setVisibility(View.GONE);
            }
            if (metricsBenchPanel != null) {
                metricsBenchPanel.setVisibility(View.GONE);
            }
            return;
        }

        if (isSquatExercise) {
            metricsStandardPanel.setVisibility(View.VISIBLE);
            metricsBenchPanel.setVisibility(View.GONE);
            squatLiveHeader.setVisibility(View.VISIBLE);
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
            if (squatHipMetricsColumn != null) {
                squatHipMetricsColumn.setVisibility(View.VISIBLE);
            }
            if (metricHipAngle != null) {
                metricHipAngle.setVisibility(View.GONE);
            }
            if (metricSquatHipValue != null) {
                metricSquatHipValue.setText("--");
                metricSquatHipValue.setTextColor(ContextCompat.getColor(this, R.color.white));
            }
            metricStability.setText("--");
            progressConsistency.setProgress(0);
            squatAlertText.setVisibility(View.VISIBLE);
            squatAlertText.setText(R.string.camera_squat_status_ready);
            squatAlertText.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
            squatAlertBanner.setText(R.string.camera_squat_status_ready);
            squatAlertBanner.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
            squatAlertBanner.setBackgroundResource(R.drawable.bg_alert_banner_neutral);
            squatRepCount.setText("0");
        } else if (isBenchPressExercise) {
            // Press banca usa su propio panel con las 5 reglas visibles
            metricsStandardPanel.setVisibility(View.GONE);
            metricsBenchPanel.setVisibility(View.VISIBLE);
            squatLiveHeader.setVisibility(View.GONE);
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
            squatLiveHeader.setVisibility(View.GONE);
            // Ocultamos el card del progress bar y mostramos las dos tarjetas
            // nuevas en su lugar.
            consistencyCard.setVisibility(View.GONE);
            curlMetricsRow.setVisibility(View.VISIBLE);
            metricAngleLabel.setText(R.string.camera_curl_peak_label);
            if (squatHipMetricsColumn != null) {
                squatHipMetricsColumn.setVisibility(View.GONE);
            }
            metricHipAngle.setVisibility(View.VISIBLE);
            metricHipAngle.setText(R.string.bench_status_placeholder);
            metricHipAngle.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
            metricStabilityLabel.setText(R.string.camera_curl_stability_label);
            metricPeakAngle.setText("--");
            metricStability.setText("--");
            metricCurlVelocity.setText("--");
            metricCurlFlex.setText("0");
            squatAlertText.setVisibility(View.GONE);
            emaStability    = 100.0;
            emaCurlAngle    = null;
            emaCurlFlex     = null;
            emaCurlVelocity = null;
            curlNoPoseFrames            = 0;
            curlHighAngleFrames         = 0;
            curlWristHighFrames         = 0;
            curlShoulderAlertFrames     = 0;
            curlShoulderClearFrames     = 0;
            curlShoulderAlertActive     = false;
            curlOverlayShowUntilMs      = 0;
            curlOverlayDismissedUntilMs = 0;
            lastFeedbackRepCount        = 0;
            repFeedbackActive           = false;
            curlAngleColorState         = 0;
            curlAngleColorFrames        = 0;
            hideCurlLiveOverlay();
            if (curlAlertBanner != null) {
                curlAlertBanner.setVisibility(View.VISIBLE);
                curlAlertBanner.setText(R.string.camera_curl_status_ready);
                curlAlertBanner.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
                curlAlertBanner.setBackgroundResource(R.drawable.bg_alert_banner_neutral);
            }
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
        checkExtremeAlerts(result);
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
        // Pueden haberse cerrado varias reps entre callbacks o justo antes de
        // salir de camara. Rellenamos cualquier gap para que totalReps no quede
        // en 0 aunque la UI ya haya mostrado reps.
        while (lastSeenRepCount < currentRepCount) {
            int repNumber = lastSeenRepCount + 1;
            PendingRep pendingRep;
            if (isSquatExercise) {
                pendingRep = PendingSessionBuilder.buildSquatRep(repNumber, timestampOffsetMs, result);
            } else if (isBenchPressExercise) {
                pendingRep = PendingSessionBuilder.buildBenchRep(repNumber, timestampOffsetMs, result);
            } else {
                pendingRep = PendingSessionBuilder.buildCurlRep(repNumber, timestampOffsetMs, result);
            }
            pendingReps.add(pendingRep);
            lastSeenRepCount = repNumber;
        }
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

        // ── Panel 1: ÁNGULO — theta(t) en VIVO con color estabilizado ────────
        // Color con HISTÉRESIS para evitar parpadeos por ruido de landmarks:
        // el color solo cambia tras CURL_COLOR_CHANGE_FRAMES frames consecutivos
        // en el nuevo estado. Así el usuario no ve parpadeos verdes/amarillos
        // mientras hace una buena repetición.
        //
        // Solo aplicamos color de error cuando ya tenemos referencia de reposo
        // (thetaShoulderRest establecida, ~0.5 s de inicio). Antes de eso todo verde.
        if (curAngle != null) {
            if (emaCurlAngle == null) emaCurlAngle = curAngle;
            else emaCurlAngle = emaCurlAngle + CURL_ANGLE_ALPHA * (curAngle - emaCurlAngle);

            metricPeakAngle.setText(String.format(Locale.US, "%.0f°", emaCurlAngle));

            // Calcular estado de color objetivo para este frame
            int targetColorState; // 0=verde, 1=amarillo, 2=rojo
            if (shoulderShift == null || shoulderShift < 12.0) {
                targetColorState = 0; // verde — sin compensacion
            } else if (shoulderShift < 17.0) {
                targetColorState = 1; // amarillo — zona de aviso
            } else {
                targetColorState = 2; // rojo — compensacion confirmada
            }

            // Aplicar histéresis: solo cambiar si el nuevo estado se mantiene
            // suficientes frames consecutivos, o si el nuevo estado es MEJOR
            // (de rojo a verde/amarillo cambia igualmente rapido para no confundir).
            if (targetColorState == curlAngleColorState) {
                curlAngleColorFrames = 0; // sigue igual, resetear contador
            } else if (targetColorState < curlAngleColorState) {
                // Mejorando: cambiar inmediatamente (el usuario debe saber que lo hizo bien)
                curlAngleColorState = targetColorState;
                curlAngleColorFrames = 0;
            } else {
                // Empeorando: esperar CURL_COLOR_CHANGE_FRAMES frames para confirmar
                curlAngleColorFrames++;
                if (curlAngleColorFrames >= CURL_COLOR_CHANGE_FRAMES) {
                    curlAngleColorState = targetColorState;
                    curlAngleColorFrames = 0;
                }
            }

            int peakColor;
            switch (curlAngleColorState) {
                case 2:  peakColor = ContextCompat.getColor(this, R.color.risk_red); break;
                case 1:  peakColor = ContextCompat.getColor(this, R.color.toasted_almond); break;
                default: peakColor = ContextCompat.getColor(this, R.color.improvement_green); break;
            }
            metricPeakAngle.setTextColor(peakColor);
        } else {
            metricPeakAngle.setText("--");
            metricPeakAngle.setTextColor(ContextCompat.getColor(this, R.color.white));
            curlAngleColorState  = 0;
            curlAngleColorFrames = 0;
        }

        // Sub-texto: pico de flexion (min θ) de la rep en curso o ultima cerrada.
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
        // Penalizacion CONTINUA por compensacion de hombro (Liu 2024).
        // Solo penalizar cuando ya hay referencia de reposo (shoulderShift != null).
        // Antes de eso, mantener 100% para no asustar al usuario con 50% desde el inicio.
        double penaltyShoulder = 0.0;
        if (shoulderShift != null) {
            penaltyShoulder = Math.min(100.0, (shoulderShift / 17.0) * 50.0);
        }

        // Penalizacion por fatiga (Sanchez-Medina 2011) — VL40% → 40 puntos.
        // Solo activa cuando hay referencia calibrada (min 3 reps).
        double penaltyVL = 0.0;
        if (result.getVelocityLossPercent() != null && result.getRepCount() >= 3) {
            penaltyVL = Math.min(40.0, result.getVelocityLossPercent());
        }

        // Penalizacion por desviacion del pico (E1) — solo cuando hay referencia.
        double penaltyError = 0.0;
        if (result.getErrorMagnitude() != null && result.getRepCount() >= 3) {
            penaltyError = Math.min(25.0, result.getErrorMagnitude() * 1.25);
        }

        double targetStability = Math.max(0.0,
                100.0 - penaltyShoulder - penaltyVL - penaltyError);

        emaStability = emaStability + STABILITY_ALPHA * (targetStability - emaStability);
        int displayStability = (int) Math.round(emaStability);
        metricStability.setText(String.format(Locale.US, "%d%%", displayStability));

        int stabilityColor;
        if (displayStability > 75) {
            stabilityColor = ContextCompat.getColor(this, R.color.improvement_green);
        } else if (displayStability > 45) {
            stabilityColor = ContextCompat.getColor(this, R.color.toasted_almond);
        } else {
            stabilityColor = ContextCompat.getColor(this, R.color.risk_red);
        }
        metricStability.setTextColor(stabilityColor);

        // ── Panel 3: VELOCIDAD (°/s) — instantanea con EMA ligera ───────────
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
            if (vl != null && vl >= 40.0 && result.getRepCount() >= 3) {
                velColor = ContextCompat.getColor(this, R.color.risk_red);
            } else if (vl != null && vl >= 30.0 && result.getRepCount() >= 3) {
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

        // ── Panel 4: FLEXIONES — todos los intentos, incluso con mala forma ──
        int displayRepCount = result.getAttemptedRepCount();
        metricCurlFlex.setText(String.valueOf(displayRepCount));
        metricCurlFlex.setTextColor(displayRepCount > 0
                ? ContextCompat.getColor(this, R.color.improvement_green)
                : ContextCompat.getColor(this, R.color.white));

        Double displayRom = result.getCurrentRepRomDeg();
        if (displayRom == null) displayRom = result.getLastRepRomDeg();

        // ── Aviso por rep: se muestra al cerrar cada repetición ───────────────
        // Usamos los valores snapshot de la rep cerrada (lastRep*) en lugar de
        // los valores en vivo (displayRom / shoulderShift), que son ruidosos
        // justo en el frame de cierre de rep.
        int currentRepCount = result.getRepCount();
        if (currentRepCount > lastFeedbackRepCount && currentRepCount > 0) {
            lastFeedbackRepCount = currentRepCount;
            showRepFeedback(result, result.getLastRepRomDeg(), result.getLastRepShoulderCompensationDeg());
        }

        // ── Panel 5: banner de estado del curl ───────────────────────────────
        updateCurlAlert(result, shoulderShift, displayRom, livePeak, curAngle != null);

        // ── Overlay central semitransparente ─────────────────────────────────
        // Prioridad: sin pose → muñeca muy alta → brazo muy bajo → hombro → fatiga
        // El aviso por rep tiene prioridad máxima cuando está activo.
        boolean hasPoseCurl = curAngle != null;
        if (!hasPoseCurl) {
            curlNoPoseFrames++;
            curlHighAngleFrames  = 0;
            curlWristHighFrames  = 0;
            curlShoulderAlertFrames = 0;
            curlShoulderClearFrames = 0;
        } else {
            curlNoPoseFrames = 0;

            Double wristAbove = result.getWristAboveShoulderRatio();
            boolean wristTooHigh = wristAbove != null && wristAbove > 0.0;
            curlWristHighFrames = wristTooHigh ? curlWristHighFrames + 1 : 0;

            curlHighAngleFrames = 0;

            boolean shoulderOverThreshold = shoulderShift != null && shoulderShift > 17.0;
            if (shoulderOverThreshold) {
                curlShoulderAlertFrames++;
                curlShoulderClearFrames = 0;
            } else {
                curlShoulderClearFrames++;
                curlShoulderAlertFrames = 0;
            }
            if (!curlShoulderAlertActive && curlShoulderAlertFrames >= CURL_SHOULDER_TRIGGER_FRAMES) {
                curlShoulderAlertActive = true;
            }
            if (curlShoulderAlertActive && curlShoulderClearFrames >= CURL_SHOULDER_CLEAR_FRAMES) {
                curlShoulderAlertActive = false;
            }
        }

        // Si el aviso de rep está activo, no sobreescribir con otros overlays
        if (repFeedbackActive) return;

        if (curlNoPoseFrames >= CURL_NO_POSE_FRAMES) {
            showCurlLiveOverlay("📷", "Ajusta el encuadre",
                    "No se detecta el brazo\nAcércate o centra la cámara");
        } else if (curlWristHighFrames >= CURL_WRIST_HIGH_FRAMES) {
            showCurlLiveOverlay("⬆", "Demasiado arriba",
                    "La muñeca subió sobre el hombro\nBaja el brazo al terminar el curl");
        } else if (curlHighAngleFrames >= CURL_HIGH_ANGLE_FRAMES) {
            showCurlLiveOverlay("⬇", "Sube el peso",
                    "Completa el recorrido\nSube la muñeca hasta el pecho");
        } else if (curlShoulderAlertActive) {
            showCurlLiveOverlay("⚠", "Hombro hacia adelante",
                    String.format(Locale.US,
                            "%.0f° de compensación\nMantén el codo pegado al costado",
                            shoulderShift != null ? shoulderShift : 0.0));
        } else if (result.getVelocityLossPercent() != null
                && result.getVelocityLossPercent() >= 40.0
                && result.getRepCount() >= 3) {
            showCurlLiveOverlay("🔴", "Fatiga detectada",
                    String.format(Locale.US,
                            "Pérdida de velocidad %.0f%%\nConsidera descansar",
                            result.getVelocityLossPercent()));
        } else {
            hideCurlLiveOverlay();
        }
    }

    /**
     * Muestra un overlay de feedback inmediatamente al cerrar una repetición.
     * Califica la rep como BIEN / MEDIA / MAL basándose en:
     *   - ROM de la rep (objetivo ≥110°, Pinto 2012)
     *   - Compensación de hombro (umbral 15°, Liu 2024)
     * El overlay se muestra REP_FEEDBACK_MS ms y luego desaparece.
     */
    private void showRepFeedback(AlgorithmResult result, Double rom, Double shoulderComp) {
        // Sin datos todavía — no mostrar nada para evitar "mal hecha" falso
        if (rom == null && shoulderComp == null) return;

        // null = sin dato = no penalizar ese criterio
        boolean romOk      = rom == null || rom >= 85.0;
        boolean romPartial = rom == null || rom >= 65.0;
        boolean shoulderOk   = shoulderComp == null || shoulderComp < 12.0;
        boolean shoulderWarn = shoulderComp != null  && shoulderComp < 17.0;

        String icon, title, message;
        if (romOk && shoulderOk) {
            icon    = "✅";
            title   = "¡Bien hecha!";
            message = rom != null
                    ? String.format(Locale.US, "%.0f° de recorrido · hombro estable", rom)
                    : "Hombro estable";
        } else if ((romOk || romPartial) && (shoulderOk || shoulderWarn)) {
            icon    = "⚡";
            title   = "Casi bien";
            if (!romOk && rom != null) {
                message = String.format(Locale.US,
                        "Sube el peso más y baja el brazo\ncompletamente (%.0f° de recorrido)",
                        rom);
            } else {
                message = "El hombro se fue un poco\nMantén el codo quieto al costado";
            }
        } else {
            icon    = "❌";
            title   = "Mal hecha";
            if (rom != null && !romPartial) {
                message = "Sube y baja más el brazo\nNo llegaste al recorrido completo";
            } else {
                message = "El hombro se fue hacia adelante\nBaja el peso y pega el codo al costado";
            }
        }

        repFeedbackActive = true;
        showCurlLiveOverlay(icon, title, message, true);

        alertHandler.postDelayed(() -> {
            repFeedbackActive = false;
            hideCurlLiveOverlay();
        }, REP_FEEDBACK_MS);
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
    /**
     * Actualiza el banner de estado del curl en tiempo real.
     *
     * Prioridad (de mayor a menor):
     *   (1) Sin pose             → gris   "Levanta el peso para iniciar…"
     *   (2) Calibrando (<3 reps) → gris   "Calibrando… rep X/3"
     *   (3) Compensación hombro  → rojo   "Codo separado X°, pégalo al costado"
     *   (4) Fatiga VL≥20%        → rojo   "Fatiga X%, considera cerrar la serie"
     *   (5) ROM parcial          → ámbar  "ROM Xgr (obj. ≥110°), sube y baja más"
     *   (6) Todo OK              → verde  "Técnica estable · Rep X"
     */
    private void updateCurlAlert(AlgorithmResult result,
                                 Double shoulderShift,
                                 Double displayRom,
                                 Double livePeak,
                                 boolean hasPose) {
        if (curlAlertBanner == null) return;

        int repCount = result.getRepCount();
        Double vl    = result.getVelocityLossPercent();

        String text;
        int textColor;
        int bgRes;

        if (!hasPose || repCount == 0) {
            if (curlAlertBanner != null) curlAlertBanner.setVisibility(View.GONE);
            return;
        } else if (repCount < 3) {
            text      = getString(R.string.camera_curl_calibrando, repCount);
            textColor = ContextCompat.getColor(this, R.color.silver_2);
            bgRes     = R.drawable.bg_alert_banner_neutral;
        } else if (curlShoulderAlertActive && shoulderShift != null) {
            // Compensación de hombro confirmada (con histéresis)
            text      = getString(R.string.camera_curl_alert_shoulder, shoulderShift);
            textColor = ContextCompat.getColor(this, R.color.risk_red);
            bgRes     = R.drawable.bg_alert_banner_red;
        } else if (vl != null && vl >= 40.0) {
            // Fatiga técnica (Sánchez-Medina 2011: VL≥40% = umbral zona 2, más conservador)
            text      = getString(R.string.camera_curl_alert_fatigue, vl);
            textColor = ContextCompat.getColor(this, R.color.risk_red);
            bgRes     = R.drawable.bg_alert_banner_red;
        } else if (displayRom != null && displayRom < 90.0) {
            // ROM parcial de la última rep cerrada
            text      = getString(R.string.camera_curl_alert_partial_rom, displayRom);
            textColor = ContextCompat.getColor(this, R.color.toasted_almond);
            bgRes     = R.drawable.bg_alert_banner_amber;
        } else {
            // Todo correcto
            if (repCount > 0) {
                text = getString(R.string.camera_curl_status_ok_with_rep, repCount);
            } else {
                text = getString(R.string.camera_curl_status_ok);
            }
            textColor = ContextCompat.getColor(this, R.color.improvement_green);
            bgRes     = R.drawable.bg_alert_banner_green;
        }

        if (curlAlertBanner.getVisibility() != View.VISIBLE) {
            curlAlertBanner.setVisibility(View.VISIBLE);
        }
        curlAlertBanner.setText(text);
        curlAlertBanner.setTextColor(textColor);
        curlAlertBanner.setBackgroundResource(bgRes);
    }

    private void onSquatResult(AlgorithmResult result) {
        int repCount = result.getRepCount();
        if (squatRepCount != null) {
            squatRepCount.setText(String.valueOf(repCount));
        }

        Double kneeAngle = result.getKneeAngleDeg();
        if (kneeAngle != null) {
            metricPeakAngle.setText(String.format(Locale.US, "%.0f°", kneeAngle));
        }

        Double hipAngle = result.getHipAngleDeg();
        if (metricSquatHipValue != null) {
            if (hipAngle != null) {
                metricSquatHipValue.setText(String.format(Locale.US, "%.0f°", hipAngle));
                metricSquatHipValue.setTextColor(ContextCompat.getColor(this, R.color.white));
            } else {
                metricSquatHipValue.setText("--");
                metricSquatHipValue.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
            }
        }

        Double cvt = result.getCvtPercent();
        if (cvt != null) {
            lastSquatCvtDisplay = cvt;
            metricStability.setText(String.format(Locale.US, "%.1f%%", cvt));
        } else if (lastSquatCvtDisplay != null) {
            metricStability.setText(String.format(Locale.US, "%.1f%%", lastSquatCvtDisplay));
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
        if (velocityLoss != null) {
            double retention = Math.max(0.0, 100.0 - velocityLoss);
            lastSquatRetentionDisplay = retention;
            progressConsistency.setProgress((int) Math.round(retention));
        } else if (lastSquatRetentionDisplay != null) {
            progressConsistency.setProgress((int) Math.round(lastSquatRetentionDisplay));
        } else if (repCount > 0) {
            progressConsistency.setProgress(100);
        }

        // Solo hint de retención (VL); reps y CVT ya están en sus paneles.
        metricConsistencyHint.setText(R.string.camera_vl20_hint);

        updateSquatAlert(result, cvt, velocityLoss, repCount);
    }

    private void updateSquatAlert(AlgorithmResult result, Double cvt, Double velocityLoss, int repCount) {
        if (squatAlertBanner == null) {
            return;
        }

        int color = ContextCompat.getColor(this, R.color.improvement_green);
        int messageRes = R.string.camera_squat_status_ready;
        int bgRes = R.drawable.bg_alert_banner_neutral;

        if (repCount <= 0) {
            if (messageRes != lastSquatAlertTextRes || color != lastSquatAlertColor) {
                lastSquatAlertTextRes = messageRes;
                lastSquatAlertColor = color;
            }
            squatAlertBanner.setText(messageRes);
            squatAlertBanner.setTextColor(ContextCompat.getColor(this, R.color.silver_2));
            squatAlertBanner.setBackgroundResource(R.drawable.bg_alert_banner_neutral);
            if (squatAlertText != null) {
                squatAlertText.setVisibility(View.GONE);
            }
            return;
        }

        if (repCount < 2 && cvt == null && velocityLoss == null) {
            color = ContextCompat.getColor(this, R.color.silver_2);
            if (color != lastSquatAlertColor) {
                lastSquatAlertColor = color;
            }
            String message = getString(R.string.camera_squat_status_calibrando, repCount);
            squatAlertBanner.setText(message);
            squatAlertBanner.setTextColor(color);
            squatAlertBanner.setBackgroundResource(R.drawable.bg_alert_banner_neutral);
            lastSquatAlertTextRes = -1;
            if (squatAlertText != null) {
                squatAlertText.setVisibility(View.GONE);
            }
            return;
        }

        // Severidad 1: Problemas críticos (profundidad + inclinación)
        if (result.getDepthInsufficient() && result.getTrunkLeanRisk()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_depth_and_trunk;
            bgRes = R.drawable.bg_alert_banner_red;
        }
        // Severidad 2: Solo profundidad insuficiente
        else if (result.getDepthInsufficient()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            SquatDepthCategory depthCategory = result.getSquatDepthCategory();
            if (depthCategory == SquatDepthCategory.PARTIAL) {
                messageRes = R.string.camera_squat_alert_depth_partial;
            } else if (depthCategory == SquatDepthCategory.MEDIUM) {
                messageRes = R.string.camera_squat_alert_depth_medium;
            } else {
                messageRes = R.string.camera_squat_alert_depth;
            }
            bgRes = R.drawable.bg_alert_banner_red;
        }
        // Severidad 3: Solo inclinación de tronco
        else if (result.getTrunkLeanRisk()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            SquatTrunkCategory trunkCategory = result.getSquatTrunkCategory();
            if (trunkCategory == SquatTrunkCategory.TOO_INCLINED) {
                messageRes = R.string.camera_squat_alert_trunk_too_inclined;
            } else if (trunkCategory == SquatTrunkCategory.TOO_UPRIGHT) {
                messageRes = R.string.camera_squat_alert_trunk_too_upright;
            } else {
                messageRes = R.string.camera_squat_alert_trunk;
            }
            bgRes = R.drawable.bg_alert_banner_red;
        }
        // Severidad 4: Fatiga significativa (prioridad sobre inestabilidad)
        else if (result.getFatigueDetected() || (velocityLoss != null && velocityLoss >= 20.0)) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_fatigue;
            bgRes = R.drawable.bg_alert_banner_red;
        }
        // Severidad 5: Inestabilidad severa (CVT > 10)
        else if (cvt != null && cvt > 10.0) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_instability;
            bgRes = R.drawable.bg_alert_banner_red;
        }
        // Advertencia: Variabilidad moderada (5-10)
        else if (cvt != null && cvt >= 5.0) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_squat_alert_variability;
            bgRes = R.drawable.bg_alert_banner_amber;
        }
        // OK: Todo bien
        else {
            color = ContextCompat.getColor(this, R.color.improvement_green);
            messageRes = R.string.camera_squat_status_ok;
            bgRes = R.drawable.bg_alert_banner_green;
        }

        if (messageRes != lastSquatAlertTextRes || color != lastSquatAlertColor) {
            lastSquatAlertTextRes = messageRes;
            lastSquatAlertColor = color;
        }
        squatAlertBanner.setText(messageRes);
        squatAlertBanner.setTextColor(color);
        squatAlertBanner.setBackgroundResource(bgRes);
        if (squatAlertText != null) {
            squatAlertText.setVisibility(View.GONE);
        }
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

    private static final double BENCH_GRIP_RATIO_MIN = 1.25;
    private static final double BENCH_GRIP_RATIO_MAX = 1.75;
    private static final double BENCH_GRIP_RATIO_CRITICAL = 2.0;
    private static final double BENCH_SYMMETRY_WARN_PERCENT = 8.0;
    private static final double BENCH_SYMMETRY_RISK_PERCENT = 15.0;
    private static final double BENCH_ABDUCTION_MIN_OK_DEG = 45.0;
    private static final double BENCH_ABDUCTION_MAX_OK_DEG = 85.0;
    private static final double BENCH_ABDUCTION_DEEP_ELBOW_DEG = 80.0;
    private static final double BENCH_ABDUCTION_DEEP_MAX_OK_DEG = 55.0;
    private static final double BENCH_ABDUCTION_CRITICAL_DEG = 90.0;

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
        // Optimo biomecanico: munecas a ~1.5x del ancho biacromial.
        // El algoritmo reporta ese ratio calculado con apertura horizontal.
        Double grip = result.getGripWidthRatio();
        if (grip != null) {
            String gripTxt = String.format(Locale.US, "%.2fx", grip);
            int status;
            if (result.getGripTooWide() || grip > BENCH_GRIP_RATIO_CRITICAL) {
                status = STATUS_RISK;
            } else if (grip > BENCH_GRIP_RATIO_MAX || grip < BENCH_GRIP_RATIO_MIN) {
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
            if (abduction > BENCH_ABDUCTION_CRITICAL_DEG) {
                status = STATUS_RISK;
            } else if (isBenchAbductionWarning(abduction, elbowAngle)) {
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
            String symTxt = String.format(Locale.US, "%.0f%%", asymmetry);
            int status;
            if (asymmetry >= BENCH_SYMMETRY_RISK_PERCENT || result.getBilateralAsymmetry()) {
                status = STATUS_RISK;
            } else if (asymmetry >= BENCH_SYMMETRY_WARN_PERCENT) {
                status = STATUS_WARN;
            } else {
                status = STATUS_OK;
            }
            setRuleStatus(benchRuleSymmetryDot, benchRuleSymmetryValue, symTxt, status);
        } else {
            setRuleStatus(benchRuleSymmetryDot, benchRuleSymmetryValue, "--", STATUS_NEUTRAL);
        }

        // ── Regla 4: Profundidad ────────────────────────────────────────
        // Se mantiene neutro y muestra check solo al alcanzar el fondo valido.
        Boolean elbowAtDepth = result.getElbowBelowTorsoLive();
        String depthTxt = "--";
        int depthStatus = STATUS_NEUTRAL;
        if (Boolean.TRUE.equals(elbowAtDepth)) {
            depthTxt = "✓";
            depthStatus = STATUS_OK;
        }
        setRuleStatus(benchRuleDepthDot, benchRuleDepthValue, depthTxt, depthStatus);

        // ── Regla 5: Extension ──────────────────────────────────────────
        // Misma logica que profundidad: check al llegar arriba y reset visual
        // cuando el codo vuelve 10° en la direccion opuesta.
        Boolean extensionComplete = result.getExtensionCompleteLive();
        String extTxt = "--";
        int extStatus = STATUS_NEUTRAL;
        if (Boolean.TRUE.equals(extensionComplete)) {
            extTxt = "✓";
            extStatus = STATUS_OK;
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
        Double elbowAngle = result.getElbowAngleDeg();
        Double velocityLoss = result.getVelocityLossPercent();
        Double grip = result.getGripWidthRatio();
        Double symmetry = result.getBilateralAsymmetryDeg();

        int color;
        int messageRes;
        int bgRes;

        // Umbral de asimetria "visible" para banner: diferencia vertical de
        // munecas como porcentaje del ancho biacromial.
        boolean symmetryWarn = symmetry != null && symmetry >= BENCH_SYMMETRY_WARN_PERCENT;

        // Gate por "hay pose" en vez de "readiness READY": solo mostramos
        // el mensaje neutro de encuadre cuando no hay datos de codo. Si hay
        // pose, el usuario recibe las alertas aunque el pipeline aun este
        // estabilizando el filtro. La pildora de readiness sigue siendo la
        // indicacion honesta de calidad de senal.
        boolean hasPose = elbowAngle != null;

        if (!hasPose) {
            color = ContextCompat.getColor(this, R.color.silver_2);
            messageRes = R.string.camera_bench_status_ready;
            bgRes = R.drawable.bg_alert_banner_neutral;
        } else if (abduction != null && abduction > BENCH_ABDUCTION_CRITICAL_DEG) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_bench_alert_abduction_critical;
            bgRes = R.drawable.bg_alert_banner_red;
        } else if (result.getStickingPeriodDetected()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_bench_alert_sticking;
            bgRes = R.drawable.bg_alert_banner_red;
        } else if (velocityLoss != null && velocityLoss >= 35.0) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_bench_alert_fatigue_vl25;
            bgRes = R.drawable.bg_alert_banner_red;
        } else if (result.getGripTooWide() || (grip != null && grip > BENCH_GRIP_RATIO_CRITICAL)) {
            // Agarre critico: muy por encima del objetivo 1.5x biacromial.
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_bench_alert_grip;
            bgRes = R.drawable.bg_alert_banner_red;
        } else if (result.getDepthInsufficientBench() && result.getExtensionIncomplete()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_bench_alert_depth_and_extension;
            bgRes = R.drawable.bg_alert_banner_red;
        } else if (velocityLoss != null && velocityLoss >= 25.0) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_bench_alert_fatigue_vl15;
            bgRes = R.drawable.bg_alert_banner_amber;
        } else if (result.getDepthInsufficientBench()) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_bench_alert_depth;
            bgRes = R.drawable.bg_alert_banner_amber;
        } else if (result.getExtensionIncomplete()) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_bench_alert_extension;
            bgRes = R.drawable.bg_alert_banner_amber;
        } else if (abduction != null
                && isBenchAbductionWarning(abduction, elbowAngle)) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_bench_alert_abduction_warning;
            bgRes = R.drawable.bg_alert_banner_amber;
        } else if (result.getBilateralAsymmetry() || symmetryWarn) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_bench_alert_asymmetry;
            bgRes = R.drawable.bg_alert_banner_amber;
        } else if (grip != null && grip > BENCH_GRIP_RATIO_MAX) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_bench_alert_grip_wide;
            bgRes = R.drawable.bg_alert_banner_amber;
        } else if (grip != null && grip < BENCH_GRIP_RATIO_MIN) {
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

    private boolean isBenchAbductionWarning(double abductionDeg, Double elbowAngleDeg) {
        if (elbowAngleDeg != null && elbowAngleDeg < BENCH_ABDUCTION_DEEP_ELBOW_DEG) {
            return abductionDeg > BENCH_ABDUCTION_DEEP_MAX_OK_DEG;
        }
        return abductionDeg < BENCH_ABDUCTION_MIN_OK_DEG
                || abductionDeg > BENCH_ABDUCTION_MAX_OK_DEG;
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
        cameraViewManager = new CameraViewManager(this, this, cameraContainer, startFrontCamera);
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

        AlgorithmResult finalResult = algorithms != null ? algorithms.getCurrentResult() : null;
        if (finalResult != null) {
            captureRepIfClosed(finalResult);
        }
        int totalReps    = pendingReps.size();
        // Mismo fallback que PendingSessionBuilder: si no hay resultado final,
        // usar reps.size para no descartar sesiones con datos guardados.
        int attemptedReps = finalResult != null ? finalResult.getAttemptedRepCount() : totalReps;
        boolean analyzed = isAnalyzedExercise;

        // Sin ejercicio analizado → home.
        // Con ejercicio analizado: mostrar summary si al menos 1 intento detectado,
        // aunque ninguno cumpla los criterios de calidad.
        if (!analyzed || (totalReps == 0 && attemptedReps == 0)) {
            if (analyzed) {
                Toast.makeText(this, R.string.session_no_reps_toast, Toast.LENGTH_SHORT).show();
            }
            navigateHome();
            return;
        }

        String exerciseType = resolveExerciseType();
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

        // Siempre poblamos el holder para que SummaryActivity pueda mostrar metricas.
        // SummaryActivity lo limpia en onDestroy.
        PendingSessionHolder.INSTANCE.set(data);

        if (autoSaveEnabled) {
            RizeApplication.get().getSessionRepository().saveSessionAsync(data, id -> {
                if (id < 0) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(
                                    getApplicationContext(),
                                    R.string.session_save_failed_toast,
                                    Toast.LENGTH_SHORT
                            ).show()
                    );
                }
                return kotlin.Unit.INSTANCE;
            });
            intent.putExtra(EXTRA_ALREADY_SAVED, true);
        } else {
            intent.putExtra(EXTRA_ALREADY_SAVED, false);
        }

        // Liberar MediaPipe ANTES de navegar para evitar el crash
        // "task graph hasn't been started" que ocurre cuando el background
        // executor entrega un frame justo después de que la Activity destruye
        // el PoseLandmarker en onDestroy. Al cerrar aquí el flag isClosed
        // ya está activo cuando llegan los últimos frames del executor.
        // Nullificamos la referencia para que onDestroy no haga doble-release.
        if (cameraViewManager != null) {
            cameraViewManager.release();
            cameraViewManager = null;
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

    // ── Extreme alerts ────────────────────────────────────────────────────────

    private void checkExtremeAlerts(AlgorithmResult result) {
        long now = System.currentTimeMillis();
        boolean hasValidPose = result.getAngleDeg() != null
                || result.getKneeAngleDeg() != null
                || result.getElbowAngleDeg() != null;

        // Trigger 1: no pose for 15 s
        if (lastValidPoseMs == 0) {
            // Inicializa la referencia temporal para que el trigger funcione
            // incluso si la sesion arranca sin pose detectable.
            lastValidPoseMs = now;
        }
        if (hasValidPose) {
            lastValidPoseMs = now;
            noPoseAlertActive = false;
        } else {
            if (!noPoseAlertActive && (now - lastValidPoseMs) > NO_POSE_THRESHOLD_MS) {
                noPoseAlertActive = true;
                showExtremeAlert(getString(R.string.alert_no_pose_title),
                        getString(R.string.alert_no_pose_message));
                return;
            }
        }
        if (!hasValidPose) return;

        // Trigger 2: sticking point en bench -> popup preventivo.
        if (isBenchPressExercise && result.getStickingPeriodDetected()) {
            if (!benchStickingPopupShown) {
                benchStickingPopupShown = true;
                showExtremeAlert(
                        getString(R.string.alert_sticking_title),
                        getString(R.string.camera_bench_alert_sticking)
                );
            }
        } else {
            benchStickingPopupShown = false;
        }

        // Trigger 2 desactivado por requerimiento: popup de mala tecnica por
        // severidad sostenida 5s.
        severeErrorStartMs = 0;
        severeFormAlertShown = false;

        // Trigger 3: injury risk in 3+ consecutive reps (SEVERE quality)
        int repCount = result.getRepCount();
        if (repCount > lastRepCountForInjury) {
            lastRepCountForInjury = repCount;
            ErrorLevel lastQuality = result.getLastRepFormQuality();
            if (lastQuality == ErrorLevel.SEVERE) {
                consecutiveInjuryReps++;
            } else {
                consecutiveInjuryReps = 0;
                injuryAlertShown = false;
            }
            if (!injuryAlertShown && consecutiveInjuryReps >= INJURY_RISK_REPS) {
                injuryAlertShown = true;
                showExtremeAlert(getString(R.string.alert_injury_risk_title),
                        getString(R.string.alert_injury_risk_message));
            }
        }
    }

    // ── Curl live overlay ─────────────────────────────────────────────────────

    private void showCurlLiveOverlay(String icon, String title, String message) {
        showCurlLiveOverlay(icon, title, message, false);
    }

    /**
     * @param isRepFeedback true cuando el origen es el aviso por rep — en ese
     *                      caso ignoramos el cooldown de tap y usamos duración
     *                      corta (REP_FEEDBACK_MS controla la duración en el caller).
     */
    private void showCurlLiveOverlay(String icon, String title, String message,
                                     boolean isRepFeedback) {
        if (curlLiveOverlay == null) return;
        long now = System.currentTimeMillis();
        // El cooldown de tap NO aplica a los avisos por rep
        if (!isRepFeedback && now < curlOverlayDismissedUntilMs) return;
        curlOverlayIcon.setText(icon);
        curlOverlayTitle.setText(title);
        curlOverlayMessage.setText(message);
        if (isRepFeedback) {
            // Rep feedback: resetear el timer de duración mínima para que su propio
            // callback pueda ocultar el overlay aunque hubiera una alerta previa activa.
            curlOverlayShowUntilMs = 0L;
        } else if (curlLiveOverlay.getVisibility() != View.VISIBLE) {
            curlOverlayShowUntilMs = now + CURL_OVERLAY_MIN_SHOW_MS;
            curlLiveOverlay.setOnClickListener(v -> {
                if (!repFeedbackActive) {
                    curlOverlayDismissedUntilMs = System.currentTimeMillis() + CURL_OVERLAY_COOLDOWN_MS;
                    curlLiveOverlay.setVisibility(View.GONE);
                }
            });
        }
        curlLiveOverlay.setVisibility(View.VISIBLE);
    }

    private void hideCurlLiveOverlay() {
        if (curlLiveOverlay == null) return;
        // Respetar el tiempo mínimo de display — no ocultar antes de que expire
        if (System.currentTimeMillis() < curlOverlayShowUntilMs) return;
        curlLiveOverlay.setVisibility(View.GONE);
    }

    // ── Extreme alert overlay ─────────────────────────────────────────────────

    private void showExtremeAlert(String title, String message) {
        if (extremeAlertOverlay == null) return;
        if (alertDismissTask != null) alertHandler.removeCallbacks(alertDismissTask);
        tvAlertOverlayTitle.setText(title);
        tvAlertOverlayMessage.setText(message);
        extremeAlertOverlay.setVisibility(View.VISIBLE);
        alertDismissTask = this::dismissExtremeAlert;
        alertHandler.postDelayed(alertDismissTask, ALERT_AUTO_DISMISS_MS);
    }

    private void dismissExtremeAlert() {
        if (extremeAlertOverlay != null) extremeAlertOverlay.setVisibility(View.GONE);
        if (alertDismissTask != null) {
            alertHandler.removeCallbacks(alertDismissTask);
            alertDismissTask = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        alertHandler.removeCallbacksAndMessages(null);
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
