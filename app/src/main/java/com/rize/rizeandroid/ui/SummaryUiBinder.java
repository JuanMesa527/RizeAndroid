package com.rize.rizeandroid.ui;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.rize.rizeandroid.R;
import com.rize.rizeandroid.data.PendingRep;
import com.rize.rizeandroid.data.PendingSessionData;
import com.rize.rizeandroid.data.entity.BenchSessionDetails;
import com.rize.rizeandroid.data.entity.CurlSessionDetails;
import com.rize.rizeandroid.data.entity.SquatSessionDetails;
import com.rize.rizeandroid.data.entity.WorkoutSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Llena las vistas de {@link R.layout#activity_summary} a partir de un
 * {@link PendingSessionData}. Compartido por {@link SummaryActivity} y
 * {@link SessionHistoryDetailActivity}.
 */
public final class SummaryUiBinder {

    private SummaryUiBinder() {}

    public static void bindSummaryContent(AppCompatActivity activity, PendingSessionData data) {
        WorkoutSession s = data.getSession();
        boolean isCurl = WorkoutSession.TYPE_CURL.equals(s.getExerciseType());

        // Para curl, desglosamos en tres categorías usando formQuality:
        //   NONE     → bien hecha   (verde)
        //   MILD     → casi bien    (amarillo) — solo curl
        //   MODERATE → mal hecha    (rojo)
        // Para otros ejercicios: todas las reps completadas = "calidad".
        int goodReps;
        int almostReps = 0;
        int qualityReps; // bien + casi bien (para el banner de rendimiento)
        if (isCurl) {
            goodReps = 0;
            for (PendingRep r : data.getReps()) {
                String q = r.getRep().getFormQuality();
                if ("NONE".equals(q))      goodReps++;
                else if ("MILD".equals(q)) almostReps++;
            }
            qualityReps = goodReps + almostReps;
        } else {
            goodReps    = s.getTotalReps();
            qualityReps = goodReps;
        }

        int attempted = data.getAttemptedRepCount();
        if (attempted < s.getTotalReps()) {
            attempted = s.getTotalReps();
        }
        int badReps = Math.max(0, attempted - qualityReps);
        int goodPct   = attempted > 0 ? Math.round(goodReps   * 100f / attempted) : 100;
        int almostPct = attempted > 0 ? Math.round(almostReps * 100f / attempted) : 0;
        int badPct    = attempted > 0 ? Math.round(badReps    * 100f / attempted) : 0;

        ((TextView) activity.findViewById(R.id.tv_good_reps_pct)).setText(goodPct + "%");
        ((TextView) activity.findViewById(R.id.tv_bad_reps_pct)).setText(badPct + "%");

        // Columna "Casi bien" — solo curl
        View layoutAlmost = activity.findViewById(R.id.layout_almost_reps);
        if (isCurl) {
            layoutAlmost.setVisibility(View.VISIBLE);
            ((TextView) activity.findViewById(R.id.tv_almost_reps_pct)).setText(almostPct + "%");
        } else {
            layoutAlmost.setVisibility(View.GONE);
        }

        ((TextView) activity.findViewById(R.id.tv_attempts_summary)).setText(
                activity.getString(R.string.summary_attempts_format, qualityReps, attempted));

        // Para el banner de rendimiento usamos bien+casi bien combinados
        int qualityPct = attempted > 0 ? Math.round(qualityReps * 100f / attempted) : 100;

        ImageView bannerImage = activity.findViewById(R.id.summary_banner_image);
        TextView tvTitle = activity.findViewById(R.id.tv_performance_title);
        TextView tvSubtitle = activity.findViewById(R.id.tv_performance_subtitle);
        if (qualityPct >= 75) {
            bannerImage.setImageResource(R.drawable.sigue_asi);
            tvTitle.setText(R.string.summary_performance_title_high);
            tvSubtitle.setText(R.string.summary_performance_subtitle_high);
        } else if (qualityPct >= 50) {
            bannerImage.setImageResource(R.drawable.por_buen_camino);
            tvTitle.setText(R.string.summary_performance_title_mid);
            tvSubtitle.setText(R.string.summary_performance_subtitle_mid);
        } else {
            bannerImage.setImageResource(R.drawable.a_mejorar);
            tvTitle.setText(R.string.summary_performance_title_low);
            tvSubtitle.setText(R.string.summary_performance_subtitle_low);
        }

        ((TextView) activity.findViewById(R.id.tv_rep_count_value)).setText(String.valueOf(qualityReps));

        double velSum = 0;
        int velCount = 0;
        for (PendingRep r : data.getReps()) {
            Double v = r.getRep().getPeakVelocityDegS();
            if (v != null) {
                velSum += v;
                velCount++;
            }
        }
        TextView tvAvgVel = activity.findViewById(R.id.tv_avg_velocity_value);
        tvAvgVel.setText(velCount > 0
                ? String.format(Locale.getDefault(), "%.0f", velSum / velCount)
                : "—");

        populateCorrections(activity, data, s);
        populateRisk(activity, s);
    }

    private static void populateCorrections(
            AppCompatActivity activity,
            PendingSessionData data,
            WorkoutSession s
    ) {
        String exerciseType = s.getExerciseType();
        int qualityReps;
        if (WorkoutSession.TYPE_CURL.equals(exerciseType)) {
            qualityReps = 0;
            for (PendingRep r : data.getReps()) {
                String q = r.getRep().getFormQuality();
                if ("NONE".equals(q) || "MILD".equals(q)) qualityReps++;
            }
        } else {
            qualityReps = s.getTotalReps();
        }
        int attempted = data.getAttemptedRepCount();
        if (attempted < s.getTotalReps()) {
            attempted = s.getTotalReps();
        }
        int badPct = attempted > 0 ? Math.round((attempted - qualityReps) * 100f / attempted) : 0;

        List<String> corrections = new ArrayList<>();

        if (WorkoutSession.TYPE_SQUAT.equals(exerciseType)) {
            SquatSessionDetails squat = data.getSquatDetails();
            if (squat != null) {
                if (squat.getDepthInsufficientCount() > 0) {
                    corrections.add(activity.getString(R.string.summary_corr_squat_depth, squat.getDepthInsufficientCount()));
                }
                if (squat.getTrunkLeanRiskCount() > 0 && corrections.size() < 2) {
                    corrections.add(activity.getString(R.string.summary_corr_squat_trunk, squat.getTrunkLeanRiskCount()));
                }
            }
            Double loss = s.getVelocityLossPercent();
            if (loss != null && loss >= 20.0 && corrections.size() < 2) {
                corrections.add(activity.getString(R.string.summary_corr_squat_fatigue,
                        String.format(Locale.getDefault(), "%.0f%%", loss)));
            }
            if (corrections.isEmpty()) {
                corrections.add(activity.getString(R.string.summary_corr_squat_ok));
            }
            if (corrections.size() < 2) {
                // Alternar entre tip de movilidad y tip de rodillas según si hubo
                // algún error de tronco (sugiere que la movilidad es la raíz) o no.
                SquatSessionDetails squatForTip = data.getSquatDetails();
                boolean trunkIssue = squatForTip != null && squatForTip.getTrunkLeanRiskCount() > 0;
                corrections.add(trunkIssue
                        ? activity.getString(R.string.summary_corr_squat_mobility)
                        : activity.getString(R.string.summary_corr_squat_knees));
            }

        } else if (WorkoutSession.TYPE_CURL.equals(exerciseType)) {
            if (badPct > 30) {
                corrections.add(activity.getString(R.string.summary_corr_curl_quality, badPct));
            }
            CurlSessionDetails curl = data.getCurlDetails();
            if (curl != null) {
                Double shoulderComp = curl.getMaxShoulderCompensationDeg();
                if (shoulderComp != null && shoulderComp > 15.0 && corrections.size() < 2) {
                    corrections.add(activity.getString(R.string.summary_corr_curl_shoulder));
                }
                Double avgRom = curl.getAvgRomDeg();
                if (avgRom != null && avgRom < 110.0 && corrections.size() < 2) {
                    corrections.add(activity.getString(R.string.summary_corr_curl_rom, (int) avgRom.doubleValue()));
                }
            }
            if (corrections.isEmpty()) {
                corrections.add(activity.getString(R.string.summary_corr_curl_ok));
            }
            if (corrections.size() < 2) {
                // Si ya se dió feedback de hombro o ROM, el tip extra es respiración.
                // Si la sesión fue limpia, reforzamos la excéntrica (más impacto en hipertrofia).
                boolean hasFormIssue = corrections.stream().anyMatch(c ->
                        c.contains("hombro") || c.contains("Rango"));
                corrections.add(hasFormIssue
                        ? activity.getString(R.string.summary_corr_curl_breathing)
                        : activity.getString(R.string.summary_corr_curl_eccentric));
            }

        } else if (WorkoutSession.TYPE_BENCH.equals(exerciseType)) {
            BenchSessionDetails bench = data.getBenchDetails();
            if (bench != null) {
                if (bench.getDepthInsufficientCount() > 0) {
                    corrections.add(activity.getString(R.string.summary_corr_bench_depth, bench.getDepthInsufficientCount()));
                }
                if (bench.getBilateralAsymmetryCount() > 0 && corrections.size() < 2) {
                    corrections.add(activity.getString(R.string.summary_corr_bench_asymmetry, bench.getBilateralAsymmetryCount()));
                }
                if (bench.getExtensionIncompleteCount() > 0 && corrections.size() < 2) {
                    corrections.add(activity.getString(R.string.summary_corr_bench_extension, bench.getExtensionIncompleteCount()));
                }
                if (bench.getGripTooWideCount() > 0 && corrections.size() < 2) {
                    corrections.add(activity.getString(R.string.summary_corr_bench_grip, bench.getGripTooWideCount()));
                }
            }
            if (corrections.isEmpty()) {
                corrections.add(activity.getString(R.string.summary_corr_bench_ok));
            }
            if (corrections.size() < 2) {
                corrections.add(activity.getString(R.string.summary_corr_bench_lat));
            }

        } else {
            corrections.add(activity.getString(R.string.summary_corr_nodata));
            corrections.add(activity.getString(R.string.summary_corr_nodata));
        }

        ((TextView) activity.findViewById(R.id.tv_insight_efficiency_desc)).setText(corrections.get(0));
        ((TextView) activity.findViewById(R.id.tv_insight_tempo_desc)).setText(
                corrections.size() > 1 ? corrections.get(1) : activity.getString(R.string.summary_corr_nodata));
    }

    private static void populateRisk(AppCompatActivity activity, WorkoutSession s) {
        View sectionRisk = activity.findViewById(R.id.section_risk);
        String level = s.getTechnicalErrorLevel();
        if ("MODERATE".equals(level) || "SEVERE".equals(level)) {
            sectionRisk.setVisibility(View.VISIBLE);
            boolean severe = "SEVERE".equals(level);
            ((TextView) activity.findViewById(R.id.tv_risk_title)).setText(
                    severe ? R.string.summary_risk_severe_title : R.string.summary_risk_moderate_title);
            ((TextView) activity.findViewById(R.id.tv_risk_desc)).setText(
                    severe ? R.string.summary_risk_severe_desc : R.string.summary_risk_moderate_desc);
        } else {
            sectionRisk.setVisibility(View.GONE);
        }
    }
}
