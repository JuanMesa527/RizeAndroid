package com.rize.rizeandroid;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.rize.rizeandroid.data.PendingRep;
import com.rize.rizeandroid.data.PendingSessionData;
import com.rize.rizeandroid.data.PendingSessionHolder;
import com.rize.rizeandroid.data.entity.BenchSessionDetails;
import com.rize.rizeandroid.data.entity.CurlSessionDetails;
import com.rize.rizeandroid.data.entity.SquatSessionDetails;
import com.rize.rizeandroid.data.entity.WorkoutSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SummaryActivity extends AppCompatActivity {

    private boolean alreadySaved = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_summary);

        alreadySaved = getIntent().getBooleanExtra(CameraActivity.EXTRA_ALREADY_SAVED, false);

        setupToolbar();
        setupBadgeAndButtons();
        setupBottomNav();
        setupBackNavigation();
        populateData();
    }

    private void setupToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> onUserExit());
    }

    private void setupBadgeAndButtons() {
        TextView savedBadge = findViewById(R.id.summary_saved_badge);
        TextView btnLeft = findViewById(R.id.btn_go_home);
        TextView btnRight = findViewById(R.id.btn_save_workout);

        if (alreadySaved) {
            savedBadge.setVisibility(View.VISIBLE);
            btnLeft.setText(R.string.summary_done_btn);
            btnLeft.setOnClickListener(v -> navigateHome());
            // Cuando ya esta guardada no hay accion adicional util — colapsamos
            // el segundo boton para no inducir al usuario a re-guardar.
            btnRight.setVisibility(View.GONE);
        } else {
            savedBadge.setVisibility(View.GONE);
            btnLeft.setText(R.string.summary_discard_btn);
            btnLeft.setOnClickListener(v -> confirmDiscard(this::navigateHome));
            btnRight.setText(R.string.summary_save_btn);
            btnRight.setOnClickListener(v -> doSave());
        }
    }

    private void populateData() {
        PendingSessionData data = PendingSessionHolder.INSTANCE.peek();
        if (data == null) return;
        WorkoutSession s = data.getSession();

        // ── Calidad de ejecución ──────────────────────────────────────────────
        int qualityReps  = s.getTotalReps();
        int attempted    = data.getAttemptedRepCount();
        if (attempted < qualityReps) attempted = qualityReps; // fallback: never less than quality
        int badReps  = Math.max(0, attempted - qualityReps);
        int goodPct  = attempted > 0 ? Math.round(qualityReps * 100f / attempted) : 100;
        int badPct   = attempted > 0 ? Math.round(badReps * 100f / attempted)     : 0;

        ((TextView) findViewById(R.id.tv_good_reps_pct)).setText(goodPct + "%");
        ((TextView) findViewById(R.id.tv_bad_reps_pct)).setText(badPct + "%");
        ((TextView) findViewById(R.id.tv_attempts_summary)).setText(
                getString(R.string.summary_attempts_format, qualityReps, attempted));

        // ── Banner: imagen según % bien hechas ────────────────────────────────
        android.widget.ImageView bannerImage = findViewById(R.id.summary_banner_image);
        TextView tvTitle    = findViewById(R.id.tv_performance_title);
        TextView tvSubtitle = findViewById(R.id.tv_performance_subtitle);
        if (goodPct >= 75) {
            bannerImage.setImageResource(R.drawable.sigue_asi);
            tvTitle.setText(R.string.summary_performance_title_high);
            tvSubtitle.setText(R.string.summary_performance_subtitle_high);
        } else if (goodPct >= 50) {
            bannerImage.setImageResource(R.drawable.por_buen_camino);
            tvTitle.setText(R.string.summary_performance_title_mid);
            tvSubtitle.setText(R.string.summary_performance_subtitle_mid);
        } else {
            bannerImage.setImageResource(R.drawable.a_mejorar);
            tvTitle.setText(R.string.summary_performance_title_low);
            tvSubtitle.setText(R.string.summary_performance_subtitle_low);
        }

        // ── Conteo de repeticiones válidas ───────────────────────────────────
        ((TextView) findViewById(R.id.tv_rep_count_value)).setText(String.valueOf(qualityReps));

        // ── Velocidad promedio (media de peakVelocityDegS por rep) ───────────
        double velSum = 0;
        int velCount  = 0;
        for (PendingRep r : data.getReps()) {
            Double v = r.getRep().getPeakVelocityDegS();
            if (v != null) { velSum += v; velCount++; }
        }
        TextView tvAvgVel = findViewById(R.id.tv_avg_velocity_value);
        tvAvgVel.setText(velCount > 0
                ? String.format(Locale.getDefault(), "%.0f", velSum / velCount)
                : "—");

        populateCorrections(data, s);
        populateRisk(s);
    }

    private void populateCorrections(PendingSessionData data, WorkoutSession s) {
        String exerciseType = s.getExerciseType();
        int qualityReps = s.getTotalReps();
        int attempted = data.getAttemptedRepCount();
        if (attempted < qualityReps) attempted = qualityReps;
        int badPct = attempted > 0 ? Math.round((attempted - qualityReps) * 100f / attempted) : 0;

        List<String> corrections = new ArrayList<>();

        if (WorkoutSession.TYPE_SQUAT.equals(exerciseType)) {
            SquatSessionDetails squat = data.getSquatDetails();
            if (squat != null) {
                if (squat.getDepthInsufficientCount() > 0)
                    corrections.add(getString(R.string.summary_corr_squat_depth, squat.getDepthInsufficientCount()));
                if (squat.getTrunkLeanRiskCount() > 0 && corrections.size() < 2)
                    corrections.add(getString(R.string.summary_corr_squat_trunk, squat.getTrunkLeanRiskCount()));
            }
            Double loss = s.getVelocityLossPercent();
            if (loss != null && loss >= 20.0 && corrections.size() < 2)
                corrections.add(getString(R.string.summary_corr_squat_fatigue,
                        String.format(Locale.getDefault(), "%.0f%%", loss)));
            if (corrections.isEmpty())
                corrections.add(getString(R.string.summary_corr_squat_ok));
            if (corrections.size() < 2)
                corrections.add(getString(R.string.summary_corr_squat_mobility));

        } else if (WorkoutSession.TYPE_CURL.equals(exerciseType)) {
            if (badPct > 30)
                corrections.add(getString(R.string.summary_corr_curl_quality, badPct));
            CurlSessionDetails curl = data.getCurlDetails();
            if (curl != null) {
                Double shoulderComp = curl.getMaxShoulderCompensationDeg();
                if (shoulderComp != null && shoulderComp > 15.0 && corrections.size() < 2)
                    corrections.add(getString(R.string.summary_corr_curl_shoulder));
                Double avgRom = curl.getAvgRomDeg();
                if (avgRom != null && avgRom < 110.0 && corrections.size() < 2)
                    corrections.add(getString(R.string.summary_corr_curl_rom, (int) avgRom.doubleValue()));
            }
            if (corrections.isEmpty())
                corrections.add(getString(R.string.summary_corr_curl_ok));
            if (corrections.size() < 2)
                corrections.add(getString(R.string.summary_corr_curl_eccentric));

        } else if (WorkoutSession.TYPE_BENCH.equals(exerciseType)) {
            BenchSessionDetails bench = data.getBenchDetails();
            if (bench != null) {
                if (bench.getDepthInsufficientCount() > 0)
                    corrections.add(getString(R.string.summary_corr_bench_depth, bench.getDepthInsufficientCount()));
                if (bench.getBilateralAsymmetryCount() > 0 && corrections.size() < 2)
                    corrections.add(getString(R.string.summary_corr_bench_asymmetry, bench.getBilateralAsymmetryCount()));
                if (bench.getExtensionIncompleteCount() > 0 && corrections.size() < 2)
                    corrections.add(getString(R.string.summary_corr_bench_extension, bench.getExtensionIncompleteCount()));
                if (bench.getGripTooWideCount() > 0 && corrections.size() < 2)
                    corrections.add(getString(R.string.summary_corr_bench_grip, bench.getGripTooWideCount()));
            }
            if (corrections.isEmpty())
                corrections.add(getString(R.string.summary_corr_bench_ok));
            if (corrections.size() < 2)
                corrections.add(getString(R.string.summary_corr_bench_lat));

        } else {
            corrections.add(getString(R.string.summary_corr_nodata));
            corrections.add(getString(R.string.summary_corr_nodata));
        }

        ((TextView) findViewById(R.id.tv_insight_efficiency_desc)).setText(corrections.get(0));
        ((TextView) findViewById(R.id.tv_insight_tempo_desc)).setText(
                corrections.size() > 1 ? corrections.get(1) : getString(R.string.summary_corr_nodata));
    }

    private void populateRisk(WorkoutSession s) {
        View sectionRisk = findViewById(R.id.section_risk);
        String level = s.getTechnicalErrorLevel();
        if ("MODERATE".equals(level) || "SEVERE".equals(level)) {
            sectionRisk.setVisibility(View.VISIBLE);
            boolean severe = "SEVERE".equals(level);
            ((TextView) findViewById(R.id.tv_risk_title)).setText(
                    severe ? R.string.summary_risk_severe_title : R.string.summary_risk_moderate_title);
            ((TextView) findViewById(R.id.tv_risk_desc)).setText(
                    severe ? R.string.summary_risk_severe_desc : R.string.summary_risk_moderate_desc);
        } else {
            sectionRisk.setVisibility(View.GONE);
        }
    }

    private void doSave() {
        PendingSessionData data = PendingSessionHolder.INSTANCE.peek();
        if (data == null) {
            Toast.makeText(this, R.string.session_save_failed_toast, Toast.LENGTH_SHORT).show();
            navigateHome();
            return;
        }
        RizeApplication.get().getSessionRepository().saveSessionAsync(data, id -> {
            runOnUiThread(() -> {
                if (id >= 0) {
                    PendingSessionHolder.INSTANCE.consume();
                    Toast.makeText(this, R.string.session_saved_toast, Toast.LENGTH_SHORT).show();
                    navigateHome();
                } else {
                    Toast.makeText(this, R.string.session_save_failed_toast, Toast.LENGTH_SHORT).show();
                }
            });
            return kotlin.Unit.INSTANCE;
        });
    }

    private void confirmDiscard(Runnable afterDiscard) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.summary_discard_dialog_title)
                .setMessage(R.string.summary_discard_dialog_msg)
                .setNegativeButton(R.string.summary_discard_dialog_cancel, null)
                .setPositiveButton(R.string.summary_discard_dialog_confirm, (d, which) -> {
                    PendingSessionHolder.INSTANCE.clear();
                    afterDiscard.run();
                })
                .show();
    }

    /**
     * Salida sin commit (back del toolbar / sistema). Si la sesion estaba
     * pendiente de decisión, equivale a descartar — pero mostramos el dialogo
     * para que el usuario no pierda la sesion por accidente.
     */
    private void onUserExit() {
        if (alreadySaved) {
            navigateHome();
        } else {
            confirmDiscard(this::navigateHome);
        }
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onUserExit();
            }
        });
    }

    private void setupBottomNav() {
        findViewById(R.id.nav_home).setOnClickListener(v -> onUserExit());
        findViewById(R.id.nav_fab).setOnClickListener(v -> {
            if (alreadySaved) {
                navigateSelect();
            } else {
                confirmDiscard(this::navigateSelect);
            }
        });
        findViewById(R.id.nav_stats).setOnClickListener(v -> {
            if (alreadySaved) {
                navigateStats();
            } else {
                confirmDiscard(this::navigateStats);
            }
        });
    }

    private void navigateHome() {
        Intent intent = new Intent(this, HomepageActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void navigateSelect() {
        startActivity(new Intent(this, SelectActivity.class));
        finish();
    }

    private void navigateStats() {
        startActivity(new Intent(this, StatsHistoryActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            PendingSessionHolder.INSTANCE.clear();
        }
    }
}
