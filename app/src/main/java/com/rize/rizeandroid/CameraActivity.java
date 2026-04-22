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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

public class CameraActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST = 100;

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

    // EMA (Exponential Moving Average) — reacciona rápido a cambios reales.
    // Alpha alto (~0.1) = muy reactivo, Alpha bajo (~0.02) = muy suave.
    // Estabilidad: alpha bajo porque queremos tendencia, no saltos frame a frame
    private static final double STABILITY_ALPHA    = 0.05;
    private double emaStability    = 100.0; // arranca en 100%

    // Consistencia: alpha más alto porque queremos ver el cambio entre reps
    private static final double CONSISTENCY_ALPHA  = 0.08;
    private double emaConsistency  = -1.0;  // -1 = sin datos aún (calibrando)

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private int elapsedSeconds = 0;
    private boolean timerRunning = false;

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
            cameraTitle.setText(exerciseName.toUpperCase(Locale.US) + " ANALYSIS");
        }

        setupToolbar();
        setupBottomNav();
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

        String normalizedName = exerciseName == null ? "" : exerciseName.toLowerCase(Locale.US);
        isCurlExercise = normalizedName.contains("curl");
        isSquatExercise = normalizedName.contains("squat");
        isBenchPressExercise = normalizedName.contains("bench");
        isAnalyzedExercise = isCurlExercise || isSquatExercise || isBenchPressExercise;

        if (!isAnalyzedExercise) {
            // Ocultar las métricas para ejercicios sin análisis
            findViewById(R.id.metric_peak_angle).setVisibility(View.GONE);
            findViewById(R.id.metric_stability).setVisibility(View.GONE);
            findViewById(R.id.progress_consistency).setVisibility(View.GONE);
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
            metricAngleLabel.setText(R.string.camera_knee_angle);
            metricStabilityLabel.setText(R.string.camera_cvt);
            metricConsistencyLabel.setText(R.string.camera_velocity_retention);
            metricConsistencyHint.setText(R.string.camera_vl20_hint);
            metricPeakAngle.setText("--");
            metricHipAngle.setText("Hip --");
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
            metricsStandardPanel.setVisibility(View.VISIBLE);
            metricsBenchPanel.setVisibility(View.GONE);
            metricAngleLabel.setText(R.string.camera_peak_angle);
            metricHipAngle.setVisibility(View.GONE);
            metricStabilityLabel.setText(R.string.camera_stability);
            metricConsistencyLabel.setText(R.string.camera_session_consistency);
            metricConsistencyHint.setText(R.string.camera_target_depth);
            squatAlertText.setVisibility(View.GONE);
        }

        algorithms = new Algorithms();
        algorithms.selectAlgorithm(exerciseName);

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
        if (isSquatExercise) {
            onSquatResult(result);
            return;
        }
        if (isBenchPressExercise) {
            onBenchPressResult(result);
            return;
        }

        // ── Ángulo articular → metric_peak_angle ──────────────────────────────
        // FIX: mostramos el ángulo ACTUAL en tiempo real, no solo el pico.
        // El pico lo guardamos internamente pero no bloqueamos la actualización.
        if (result.getAngleDeg() != null) {
            currentAngle = result.getAngleDeg();
            if (currentAngle > peakAngle) {
                peakAngle = currentAngle;
            }
            // Muestra el ángulo en tiempo real (no el pico fijo)
            metricPeakAngle.setText(
                    String.format(Locale.US, "%.0f°", currentAngle)
            );
        }

        // ── Estabilidad (continua 0-100) ──────────────────────────────────────
        // Combina pérdida de velocidad (§7) + desviación del pico (§8) + compensación
        // del hombro si disparó error severo. Todo continuo, sin saltos binarios.
        double penaltyVL = 0.0;
        if (result.getVelocityLossPercent() != null) {
            // VL 0% → 0 penalización; VL 40% → 80 penalización (proporcional a Rodríguez-Rosell 2023)
            penaltyVL = Math.min(80.0, result.getVelocityLossPercent() * 2.0);
        }

        double penaltyError = 0.0;
        if (result.getErrorMagnitude() != null) {
            // Error 0° → 0; Error 30° (≈ δ₂ típico) → 60 penalización
            penaltyError = Math.min(60.0, result.getErrorMagnitude() * 2.0);
        }

        // Extra si ya hay alerta severa por compensación de hombro (boost final)
        double penaltyShoulder = 0.0;
        if (result.getTechnicalError() == ErrorLevel.SEVERE
                && result.getFatigueReason() != null
                && result.getFatigueReason().contains("Compensación del hombro")) {
            penaltyShoulder = 30.0;
        }

        double targetStability = Math.max(0.0,
                100.0 - penaltyVL - penaltyError - penaltyShoulder);

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

        // ── Consistencia (continua, siempre que haya errorMagnitude) ──────────
        // Tras el fix en el algoritmo, errorMagnitude se reporta en TODAS las
        // reps calibradas (no solo cuando hay error). La barra sube si el usuario
        // mejora y baja si empeora.
        if (result.getErrorMagnitude() != null) {
            double error = result.getErrorMagnitude();
            // Mapa: error 0° → 100%; error 40° → 0%
            double targetConsistency = Math.max(0.0, 100.0 - (error / 40.0) * 100.0);

            if (emaConsistency < 0) {
                emaConsistency = targetConsistency;
            } else {
                emaConsistency = emaConsistency + CONSISTENCY_ALPHA * (targetConsistency - emaConsistency);
            }
            progressConsistency.setProgress((int) Math.round(emaConsistency));
        }
    }

    private void onSquatResult(AlgorithmResult result) {
        Double kneeAngle = result.getKneeAngleDeg();
        if (kneeAngle != null) {
            metricPeakAngle.setText(String.format(Locale.US, "%.0f°", kneeAngle));
        }

        metricHipAngle.setVisibility(View.VISIBLE);
        Double hipAngle = result.getHipAngleDeg();
        if (hipAngle != null) {
            metricHipAngle.setText(String.format(Locale.US, "Hip %.0f°", hipAngle));
        } else {
            metricHipAngle.setText("Hip --");
        }

        Double cvt = result.getCvtPercent();
        if (cvt != null) {
            metricStability.setText(String.format(Locale.US, "%.1f%%", cvt));
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
            progressConsistency.setProgress((int) Math.round(retention));
        }

        updateSquatAlert(result, cvt, velocityLoss);
    }

    private void updateSquatAlert(AlgorithmResult result, Double cvt, Double velocityLoss) {
        if (squatAlertText == null) {
            return;
        }

        int color = ContextCompat.getColor(this, R.color.improvement_green);
        int messageRes = R.string.camera_squat_status_ok;

        if (result.getDepthInsufficient() && result.getTrunkLeanRisk()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_depth_and_trunk;
        } else if (result.getDepthInsufficient()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_depth;
        } else if (result.getTrunkLeanRisk()) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_trunk;
        } else if (cvt != null && cvt > 10.0) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_instability;
        } else if (velocityLoss != null && velocityLoss >= 20.0) {
            color = ContextCompat.getColor(this, R.color.risk_red);
            messageRes = R.string.camera_squat_alert_fatigue;
        } else if (cvt != null && cvt >= 5.0) {
            color = ContextCompat.getColor(this, R.color.toasted_almond);
            messageRes = R.string.camera_squat_alert_variability;
        }

        squatAlertText.setText(messageRes);
        squatAlertText.setTextColor(color);
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
            depthTxt = "Falta";
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
                extTxt = "Falta";
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
        findViewById(R.id.nav_home).setOnClickListener(v -> {
            Intent intent = new Intent(this, HomepageActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        });
        findViewById(R.id.nav_fab).setOnClickListener(v -> {
            Intent intent = new Intent(this, SelectActivity.class);
            startActivity(intent);
        });
        findViewById(R.id.nav_stats).setOnClickListener(v -> {
            startActivity(new Intent(this, VideoAnalysisActivity.class));
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
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
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
        stopTimer();
        Intent intent = new Intent(this, SummaryActivity.class);
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