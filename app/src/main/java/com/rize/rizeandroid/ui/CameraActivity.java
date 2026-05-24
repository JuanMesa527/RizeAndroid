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

/**
 * Actividad principal para la cámara, responsable de gestionar la vista de la cámara y las métricas de ejercicio.
 */
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

    private View metricsStandardPanel;
    private TextView metricPeakAngle;
    private TextView metricStability;
    private ProgressBar progressConsistency;
    private TextView metricAngleLabel;
    private TextView metricHipAngle;
    private View squatHipMetricsColumn;
    private TextView metricSquatHipValue;
    private TextView metricStabilityLabel;
    private TextView metricConsistencyLabel;
    private TextView metricConsistencyHint;
    private TextView squatAlertText;
    private View squatLiveHeader;
    private TextView squatAlertBanner;
    private TextView squatRepCount;
    private View consistencyCard;
    private View curlMetricsRow;
    private TextView metricCurlVelocity;
    private TextView metricCurlFlex;
    private TextView curlAlertBanner;

    private View metricsBenchPanel;
    private TextView benchAlertBanner;
    private TextView benchRepCount;
    private TextView benchElbowAngle;
    private TextView benchReadinessPill;
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


    private double currentAngle = 0.0;
    private double peakAngle    = 0.0;

    private Double lastSquatCvtDisplay = null;
    private Double lastSquatRetentionDisplay = null;
    private int lastSquatAlertTextRes = -1;
    private int lastSquatAlertColor = -1;

    private static final double STABILITY_ALPHA    = 0.06;
    private static final double CURL_ANGLE_ALPHA   = 0.30;
    private static final double CURL_VEL_ALPHA     = 0.45;
    private double emaStability    = 100.0; 
    private Double emaCurlAngle    = null;  
    private Double emaCurlFlex     = null; 
    private Double emaCurlVelocity = null;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private int elapsedSeconds = 0;
    private boolean timerRunning = false;

    private View extremeAlertOverlay;
    private TextView tvAlertOverlayTitle;
    private TextView tvAlertOverlayMessage;

    private LinearLayout curlLiveOverlay;
    private TextView curlOverlayIcon;
    private TextView curlOverlayTitle;
    private TextView curlOverlayMessage;
    private int curlNoPoseFrames    = 0;
    private int curlHighAngleFrames = 0;
    private int curlWristHighFrames = 0;   
    private int curlShoulderAlertFrames = 0; 
    private int curlShoulderClearFrames = 0; 
    private boolean curlShoulderAlertActive = false;
    private int lastFeedbackRepCount     = 0;
    private int lastFeedbackPartialCount = 0;
    private static final long REP_FEEDBACK_MS = 2_500; 
    private boolean repFeedbackActive = false;
    private int curlAngleColorState = 0; 
    private int curlAngleColorFrames = 0;
    private static final int CURL_COLOR_CHANGE_FRAMES = 8; 
    private static final int CURL_NO_POSE_FRAMES    = 90; 
    private static final int CURL_HIGH_ANGLE_FRAMES = 60;  
    private static final int CURL_WRIST_HIGH_FRAMES = 15;  
    private static final int CURL_SHOULDER_TRIGGER_FRAMES = 20;
    private static final int CURL_SHOULDER_CLEAR_FRAMES   = 15; 
    private long curlOverlayShowUntilMs      = 0;  
    private long curlOverlayDismissedUntilMs = 0;   
    private static final long CURL_OVERLAY_MIN_SHOW_MS  = 6_000;  
    private static final long CURL_OVERLAY_COOLDOWN_MS  = 8_000;  
    private final Handler alertHandler = new Handler(Looper.getMainLooper());
    private Runnable alertDismissTask;
    private AlertVoiceManager voiceManager;

    private long lastValidPoseMs = 0;
    private boolean noPoseAlertActive = false;
    private long severeErrorStartMs = 0;
    private boolean severeFormAlertShown = false;
    private int consecutiveInjuryReps = 0;
    private int lastRepCountForInjury = 0;
    private boolean injuryAlertShown = false;
    private boolean benchStickingPopupShown = false;
    private boolean benchCalibrationCommitLogged = false;

    private static final long NO_POSE_THRESHOLD_MS  = 15_000;
    private static final long SEVERE_FORM_THRESHOLD_MS = 5_000;
    private static final int  INJURY_RISK_REPS      = 3;
    private static final long ALERT_AUTO_DISMISS_MS = 5_000;
    private boolean sessionFinishing = false;

    private boolean autoSaveEnabled   = false;
    private boolean startFrontCamera   = true;
    private long sessionStartMs = 0L;
    private String exerciseDisplayName = "";
    private final List<PendingRep> pendingReps = new ArrayList<>();
    private int lastSeenRepCount = 0;

    /**
     * Runnable para actualizar el temporizador cada segundo mientras está activo. Incrementa elapsedSeconds, formatea el 
     * tiempo en mm:ss y lo muestra en cameraTimer, y se auto-reprograma cada 1000 ms si timerRunning sigue siendo true.
     */
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

    /**
     * Método onCreate de la actividad, llamado al iniciar. Configura la vista, inicializa variables, configura algoritmos y 
     * verifica permisos de cámara.
     * 
     * @param savedInstanceState
     */
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
        voiceManager = new AlertVoiceManager(this);
    }

    private boolean isCurlExercise = false;
    private boolean isSquatExercise = false;
    private boolean isBenchPressExercise = false;
    private boolean isAnalyzedExercise = false;

    /**
     * Configura los algoritmos de análisis de ejercicio y las vistas asociadas según el tipo de ejercicio detectado. 
     * Inicializa los listeners para recibir datos de pose y resultados de los algoritmos, y ajusta la visibilidad y el 
     * contenido de las métricas en pantalla según si es un squat, bench press o curl.
     * 
     * @param exerciseName
     */
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
        consistencyCard        = findViewById(R.id.consistency_card);
        curlMetricsRow         = findViewById(R.id.curl_metrics_row);
        metricCurlVelocity     = findViewById(R.id.metric_curl_velocity);
        metricCurlFlex         = findViewById(R.id.metric_curl_flex);
        curlAlertBanner        = findViewById(R.id.curl_alert_banner);

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
            metricsStandardPanel.setVisibility(View.GONE);
            metricsBenchPanel.setVisibility(View.VISIBLE);
            squatLiveHeader.setVisibility(View.GONE);
            resetBenchPanel();
        } else {
            metricsStandardPanel.setVisibility(View.VISIBLE);
            metricsBenchPanel.setVisibility(View.GONE);
            squatLiveHeader.setVisibility(View.GONE);
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
            lastFeedbackPartialCount    = 0;
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

    /**
     * Handler general para los resultados de los algoritmos de análisis. Llama a funciones específicas según el tipo de ejercicio
     *  para actualizar las métricas en pantalla, detectar alertas extremas y capturar nuevas repeticiones cerradas.
     * 
     * @param result
     */
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
        if (isCurlExercise) {
            onCurlResult(result);
        }
    }

    /**
     * Captura una nueva repeticion si el contador de repeticiones ha aumentado.
     * @param result
     */
    private void captureRepIfClosed(AlgorithmResult result) {
        int currentRepCount = result.getRepCount();
        if (currentRepCount <= lastSeenRepCount) return;

        long timestampOffsetMs = System.currentTimeMillis() - sessionStartMs;
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
 * Handler específico para resultados de curl. Actualiza el ángulo en vivo con histéresis de color para compensación de hombro, 
 * calcula y muestra la estabilidad con penalizaciones por compensación, fatiga y error, actualiza la velocidad del curl, muestra 
 * feedback por repetición cerrada, y gestiona los overlays de aviso en vivo según la detección de pose, posición de muñeca, ángulo 
 * del curl y compensación de hombro.
 * 
 * @param result
 */
    private void onCurlResult(AlgorithmResult result) {
        Double curAngle = result.getAngleDeg();
        if (curAngle != null) {
            currentAngle = curAngle;
            if (currentAngle > peakAngle) peakAngle = currentAngle;
        }

        Double shoulderShift = result.getShoulderCompensationDeg();

        if (curAngle != null) {
            if (emaCurlAngle == null) emaCurlAngle = curAngle;
            else emaCurlAngle = emaCurlAngle + CURL_ANGLE_ALPHA * (curAngle - emaCurlAngle);

            metricPeakAngle.setText(String.format(Locale.US, "%.0f°", emaCurlAngle));

            int targetColorState; 
            if (shoulderShift == null || shoulderShift < 12.0) {
                targetColorState = 0;
            } else if (shoulderShift < 17.0) {
                targetColorState = 1; 
            } else {
                targetColorState = 2; 
            }

            if (targetColorState == curlAngleColorState) {
                curlAngleColorFrames = 0; 
            } else if (targetColorState < curlAngleColorState) {
                curlAngleColorState = targetColorState;
                curlAngleColorFrames = 0;
            } else {
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

        double penaltyShoulder = 0.0;
        if (shoulderShift != null) {
            penaltyShoulder = Math.min(100.0, (shoulderShift / 17.0) * 50.0);
        }

        double penaltyVL = 0.0;
        if (result.getVelocityLossPercent() != null && result.getRepCount() >= 3) {
            penaltyVL = Math.min(40.0, result.getVelocityLossPercent());
        }

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

        int displayRepCount = result.getAttemptedRepCount();
        metricCurlFlex.setText(String.valueOf(displayRepCount));
        metricCurlFlex.setTextColor(displayRepCount > 0
                ? ContextCompat.getColor(this, R.color.improvement_green)
                : ContextCompat.getColor(this, R.color.white));

        Double displayRom = result.getCurrentRepRomDeg();
        if (displayRom == null) displayRom = result.getLastRepRomDeg();

        int currentRepCount = result.getRepCount();
        if (currentRepCount > lastFeedbackRepCount && currentRepCount > 0) {
            lastFeedbackRepCount     = currentRepCount;
            lastFeedbackPartialCount = result.getPartialRepCount(); // sync so partial branch doesn't double-fire
            showRepFeedback(result, result.getLastRepRomDeg(), result.getLastRepShoulderCompensationDeg());
        } else if (result.getPartialRepCount() > lastFeedbackPartialCount) {
            lastFeedbackPartialCount = result.getPartialRepCount();
            showPartialRepFeedback();
        }

        updateCurlAlert(result, shoulderShift, displayRom, livePeak, curAngle != null);

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
     * Muestra un overlay de feedback específico para la repetición cerrada, evaluando el rango de movimiento (ROM) y 
     * la compensación de hombro.
     * @param result
     * @param rom
     * @param shoulderComp
     */
    private void showRepFeedback(AlgorithmResult result, Double rom, Double shoulderComp) {
        if (rom == null && shoulderComp == null) return;

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
     * Muestra un overlay de feedback para una repetición parcial (no completa).
     */
    private void showPartialRepFeedback() {
        repFeedbackActive = true;
        showCurlLiveOverlay("❌", "Mal hecha",
                "Recorrido muy corto\nSube el brazo hasta el pecho", true);
        alertHandler.postDelayed(() -> {
            repFeedbackActive = false;
            hideCurlLiveOverlay();
        }, REP_FEEDBACK_MS);
    }

  /**
   * Actualiza el banner de alertas de curl según la información disponible en el resultado del algoritmo, incluyendo compensación de hombro,
   * rango de movimiento, fatiga por pérdida de velocidad, y si se detecta pose o no. Cambia el texto, color y fondo del banner según 
   * la gravedad de la alerta, y también envía el mensaje actualizado al AlertVoiceManager para feedback por voz.
   * 
   * @param result
   * @param shoulderShift
   * @param displayRom
   * @param livePeak
   * @param hasPose
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
        if (voiceManager != null)
            voiceManager.onBannerUpdate(AlertVoiceManager.SLOT_CURL, text, bgToSeverity(bgRes));
    }

    /**
     * Handler específico para resultados de squat. Actualiza el contador de repeticiones, el ángulo de rodilla, el ángulo 
     * de cadera, el CVT y la retención de velocidad, y llama a updateSquatAlert para actualizar el banner de alertas de squat según 
     * la profundidad, inclinación y estabilidad.
     * 
     * @param result
     */
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

        metricConsistencyHint.setText(R.string.camera_vl20_hint);

        updateSquatAlert(result, cvt, velocityLoss, repCount);
    }

    /**
     * Actualiza el banner de alertas de squat según la información disponible en el resultado del algoritmo, incluyendo profundidad 
     * insuficiente, inclinación de tronco, fatiga por pérdida de velocidad, inestabilidad por CVT, y el número de repeticiones realizadas. 
     * Cambia el texto, color y fondo del banner según la gravedad de la alerta, y también envía el mensaje actualizado al AlertVoiceManager 
     * para feedback por voz.
     * 
     * @param result
     * @param cvt
     * @param velocityLoss
     * @param repCount
     */
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

        if (result.getDepthInsufficient() && result.getTrunkLeanRisk()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_depth_and_trunk;
            bgRes = R.drawable.bg_alert_banner_red;
        }
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
        else if (result.getFatigueDetected() || (velocityLoss != null && velocityLoss >= 20.0)) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_fatigue;
            bgRes = R.drawable.bg_alert_banner_red;
        }
        else if (cvt != null && cvt > 10.0) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_instability;
            bgRes = R.drawable.bg_alert_banner_red;
        }
        else if (cvt != null && cvt >= 5.0) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_squat_alert_variability;
            bgRes = R.drawable.bg_alert_banner_amber;
        }
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
        if (voiceManager != null)
            voiceManager.onBannerUpdate(AlertVoiceManager.SLOT_SQUAT, getString(messageRes), bgToSeverity(bgRes));
        if (squatAlertText != null) {
            squatAlertText.setVisibility(View.GONE);
        }
    }

    private static final int STATUS_NEUTRAL = 0; 
    private static final int STATUS_OK      = 1; 
    private static final int STATUS_WARN    = 2;
    private static final int STATUS_RISK    = 3; 

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

    /**Reinicia el panel de bench press */
    private void resetBenchPanel() {
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

    /**
     * Handler específico para resultados de bench press. Actualiza el contador de repeticiones, el ángulo de codo, 
     * la preparación, el ancho de agarre, la abducción de hombro, la simetría bilateral, la profundidad y la extensión, y llama a 
     * updateBenchAlertBanner para actualizar el banner de alertas de bench press según las reglas definidas para cada métrica.
     * 
     * @param result
     */
    private void onBenchPressResult(AlgorithmResult result) {
        if (!benchCalibrationCommitLogged && result.getCalibrationCommitted()) {
            benchCalibrationCommitLogged = true;
            java.util.Map<String, Double> dbg = result.getCalibrationDebug();
            android.util.Log.i("BenchCalib",
                    "Calibration committed. Thresholds: " + (dbg != null ? dbg.toString() : "(debug map disabled)"));
        }

        benchRepCount.setText(String.valueOf(result.getRepCount()));

        Double elbowAngle = result.getElbowAngleDeg();
        if (elbowAngle != null) {
            benchElbowAngle.setText(String.format(Locale.US, "%.0f°", elbowAngle));
        } else {
            benchElbowAngle.setText("--");
        }
        boolean hasPose = elbowAngle != null;
        updateReadinessPill(result.getReadinessReady(), hasPose);

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
        Boolean elbowAtDepth = result.getElbowBelowTorsoLive();
        String depthTxt = "--";
        int depthStatus = STATUS_NEUTRAL;
        if (Boolean.TRUE.equals(elbowAtDepth)) {
            depthTxt = "✓";
            depthStatus = STATUS_OK;
        }
        setRuleStatus(benchRuleDepthDot, benchRuleDepthValue, depthTxt, depthStatus);

        Boolean extensionComplete = result.getExtensionCompleteLive();
        String extTxt = "--";
        int extStatus = STATUS_NEUTRAL;
        if (Boolean.TRUE.equals(extensionComplete)) {
            extTxt = "✓";
            extStatus = STATUS_OK;
        }
        setRuleStatus(benchRuleExtensionDot, benchRuleExtensionValue, extTxt, extStatus);

        updateBenchAlertBanner(result);
    }

    /**
     * Actualiza la pildora de readiness del bench press según si el pipeline considera que la señal está estabilizada y si 
     * se detecta pose o no.
     * 
     * @param ready
     * @param hasPose
     */
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

    /**
     * Determina si la abducción de hombro está en zona de advertencia según el ángulo de codo, con un umbral dinámico que permite más 
     * abducción aceptable cuando el codo está más abierto (y por lo tanto es menos riesgoso).
     * @param result
     */
    private void updateBenchAlertBanner(AlgorithmResult result) {
        Double abduction = result.getShoulderAbductionDeg();
        Double elbowAngle = result.getElbowAngleDeg();
        Double velocityLoss = result.getVelocityLossPercent();
        Double grip = result.getGripWidthRatio();
        Double symmetry = result.getBilateralAsymmetryDeg();

        int color;
        int messageRes;
        int bgRes;

        boolean symmetryWarn = symmetry != null && symmetry >= BENCH_SYMMETRY_WARN_PERCENT;

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
        if (voiceManager != null)
            voiceManager.onBannerUpdate(AlertVoiceManager.SLOT_BENCH, getString(messageRes), bgToSeverity(bgRes));
    }

    /**
     * Determina si la abducción de hombro está en zona de advertencia según el ángulo de codo, con un umbral dinámico que permite más 
     * abducción aceptable cuando el codo está más abierto (y por lo tanto es menos riesgoso). Si el ángulo de codo es menor a 80°, 
     * se permite un máximo de 55° de abducción; si el ángulo de codo es mayor o igual a 80°, se aplican los umbrales generales de 45-85°.
     * 
     * @param abductionDeg
     * @param elbowAngleDeg
     * @return
     */
    private boolean isBenchAbductionWarning(double abductionDeg, Double elbowAngleDeg) {
        if (elbowAngleDeg != null && elbowAngleDeg < BENCH_ABDUCTION_DEEP_ELBOW_DEG) {
            return abductionDeg > BENCH_ABDUCTION_DEEP_MAX_OK_DEG;
        }
        return abductionDeg < BENCH_ABDUCTION_MIN_OK_DEG
                || abductionDeg > BENCH_ABDUCTION_MAX_OK_DEG;
    }

    /**
     * Actualiza el estado de una regla individual en el panel de bench press, cambiando el texto, color y el color del punto indicador 
     * según el estado (OK, WARN, RISK, NEUTRAL).
     * @param dot
     * @param value
     * @param text
     * @param status
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
        android.graphics.drawable.Drawable bg = dot.getBackground();
        if (bg != null) {
            android.graphics.drawable.Drawable mutable = bg.mutate();
            if (mutable instanceof GradientDrawable) {
                ((GradientDrawable) mutable).setColor(dotColor);
            }
        }
    }

    /**
     * Configura la barra de herramientas.
     */
    private void setupToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finishSession());
        findViewById(R.id.btn_settings).setOnClickListener(v -> {
            if (cameraViewManager != null) {
                cameraViewManager.switchCamera();
            }
        });
    }

    /**
     * Configura la navegación inferior.
     */
    private void setupBottomNav() {
        findViewById(R.id.nav_home).setOnClickListener(v -> finishSession());
        findViewById(R.id.nav_fab).setOnClickListener(v -> finishSession());
        findViewById(R.id.nav_stats).setOnClickListener(v -> finishSession());
    }

    /**
     * Configura la navegación hacia atrás.
     */
    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishSession();
            }
        });
    }

    /**
     * Verifica el permiso de la cámara.
     */
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

    /**
     * Maneja el resultado de la solicitud de permisos, iniciando la cámara si se concedió el permiso o mostrando un mensaje y 
     * cerrando la actividad si se denegó.
     * @param requestCode
     * @param permissions
     * @param grantResults
     */
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

    /**
     * Finaliza la sesión actual.
     */
    private void finishSession() {
        if (sessionFinishing) return;
        sessionFinishing = true;
        stopTimer();

        AlgorithmResult finalResult = algorithms != null ? algorithms.getCurrentResult() : null;
        if (finalResult != null) {
            captureRepIfClosed(finalResult);
        }
        int totalReps    = pendingReps.size();
        int attemptedReps = finalResult != null ? finalResult.getAttemptedRepCount() : totalReps;
        boolean analyzed = isAnalyzedExercise;

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

/**
 * Verifica los triggers de alertas extremas, incluyendo falta de pose detectada por más de 15 segundos, detección de sticking 
 * point en bench press, y riesgo de lesión por calidad SEVERE sostenida en 3 o más repeticiones consecutivas. Muestra un popup 
 * de alerta extrema correspondiente cuando se activa cada trigger, y utiliza variables de estado para evitar mostrar alertas repetidas 
 * innecesariamente.
 * @param result
 */
    private void checkExtremeAlerts(AlgorithmResult result) {
        long now = System.currentTimeMillis();
        boolean hasValidPose = result.getAngleDeg() != null
                || result.getKneeAngleDeg() != null
                || result.getElbowAngleDeg() != null;

        if (lastValidPoseMs == 0) {
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

        severeErrorStartMs = 0;
        severeFormAlertShown = false;

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

/**
 * Muestra un overlay específico para curl press con información de feedback en vivo, utilizando un diseño más compacto y llamativo que 
 * el banner de alertas, para comunicar información relevante durante la ejecución de cada repetición sin distraer demasiado al usuario. 
 * El overlay se puede configurar para que tenga un cooldown de taps para evitar que se oculte prematuramente, y también para que respete 
 * un tiempo mínimo de display para dar oportunidad al usuario de leer el mensaje incluso si se muestran múltiples mensajes en rápida 
 * sucesión durante la ejecución de una repetición.
 * @param icon
 * @param title
 * @param message
 */
    private void showCurlLiveOverlay(String icon, String title, String message) {
        showCurlLiveOverlay(icon, title, message, false);
    }
     
    /**
     * Muestra un overlay específico para curl press con información de feedback en vivo, utilizando un cooldown para 
     * taps que ocultan el overlay y un timer de duración mínima para evitar que se oculte prematuramente al mostrar un nuevo mensaje 
     * de feedback por rep mientras el usuario aún no ha tenido oportunidad de leerlo.
     * @param icon
     * @param title
     * @param message
     * @param isRepFeedback
     */
    private void showCurlLiveOverlay(String icon, String title, String message,
                                     boolean isRepFeedback) {
        if (curlLiveOverlay == null) return;
        long now = System.currentTimeMillis();
        if (!isRepFeedback && now < curlOverlayDismissedUntilMs) return;
        curlOverlayIcon.setText(icon);
        curlOverlayTitle.setText(title);
        curlOverlayMessage.setText(message);
        if (isRepFeedback) {
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

    /**
     * Oculta el overlay de feedback en vivo para curl press.
     */
    private void hideCurlLiveOverlay() {
        if (curlLiveOverlay == null) return;
        if (System.currentTimeMillis() < curlOverlayShowUntilMs) return;
        curlLiveOverlay.setVisibility(View.GONE);
    }

    /**
     * Muestra un popup de alerta extrema con un título y mensaje específicos, utilizado para comunicar situaciones críticas como 
     * falta de pose detectada, riesgo de lesión por calidad SEVERE sostenida, o detección de sticking point en bench press. 
     * El método maneja el estado interno para evitar mostrar alertas repetidas innecesariamente, y también envía el mensaje al 
     * AlertVoiceManager para feedback por voz adicional.
     * 
     * @param title
     * @param message
     */
    private void showExtremeAlert(String title, String message) {
        if (extremeAlertOverlay == null) return;
        if (alertDismissTask != null) alertHandler.removeCallbacks(alertDismissTask);
        tvAlertOverlayTitle.setText(title);
        tvAlertOverlayMessage.setText(message);
        extremeAlertOverlay.setVisibility(View.VISIBLE);
        alertDismissTask = this::dismissExtremeAlert;
        alertHandler.postDelayed(alertDismissTask, ALERT_AUTO_DISMISS_MS);
        if (voiceManager != null) voiceManager.onExtremeAlert(title, message);
    }

    /**
     * Oculta el overlay de alerta extrema.
     */
    private void dismissExtremeAlert() {
        if (extremeAlertOverlay != null) extremeAlertOverlay.setVisibility(View.GONE);
        if (alertDismissTask != null) {
            alertHandler.removeCallbacks(alertDismissTask);
            alertDismissTask = null;
        }
    }

    /**
     * Se llama cuando la actividad entra en pausa.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (voiceManager != null) voiceManager.onPause();
    }

    /**
     * Se llama cuando la actividad entra en resume.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (voiceManager != null) voiceManager.onResume();
    }

    /**
     * Se llama cuando la actividad se destruye.
     */
    @Override
    protected void onDestroy() {
        if (voiceManager != null) { voiceManager.destroy(); voiceManager = null; }
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

    /**
     * Convierte un recurso de fondo de banner en un nivel de severidad para el AlertVoiceManager, mapeando los fondos rojo, ámbar y verde 
     * a niveles de severidad correspondientes, y cualquier otro fondo a NONE.
     * 
     * @param bgRes
     * @return
     */
    private AlertVoiceManager.Severity bgToSeverity(int bgRes) {
        if (bgRes == R.drawable.bg_alert_banner_red)   return AlertVoiceManager.Severity.RED;
        if (bgRes == R.drawable.bg_alert_banner_amber) return AlertVoiceManager.Severity.AMBER;
        return AlertVoiceManager.Severity.NONE;
    }
}
