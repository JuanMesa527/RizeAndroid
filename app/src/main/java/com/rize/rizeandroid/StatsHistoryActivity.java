package com.rize.rizeandroid;

import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.rize.rizeandroid.data.SessionRepository;
import com.rize.rizeandroid.data.SessionRepository.ExerciseStats;
import com.rize.rizeandroid.data.SessionRepository.LocalSummary;
import com.rize.rizeandroid.data.entity.BenchSessionDetails;
import com.rize.rizeandroid.data.entity.CurlSessionDetails;
import com.rize.rizeandroid.data.entity.SquatSessionDetails;
import com.rize.rizeandroid.data.entity.WorkoutSession;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StatsHistoryActivity extends AppCompatActivity {

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private SessionAdapter sessionAdapter;
    private ExerciseStatsAdapter exerciseStatsAdapter;
    private RecyclerView sessionsListView;
    private RecyclerView exerciseStatsListView;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats_history);

        sessionsListView = findViewById(R.id.stats_list);
        exerciseStatsListView = findViewById(R.id.exercise_stats_list);
        emptyView = findViewById(R.id.stats_empty);

        sessionAdapter = new SessionAdapter();
        exerciseStatsAdapter = new ExerciseStatsAdapter();

        sessionsListView.setLayoutManager(new LinearLayoutManager(this));
        sessionsListView.setAdapter(sessionAdapter);
        exerciseStatsListView.setLayoutManager(new LinearLayoutManager(this));
        exerciseStatsListView.setAdapter(exerciseStatsAdapter);

        setupToolbar();
        setupBottomNav();
        loadStatistics();
    }

    private void setupToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    private void setupBottomNav() {
        ImageView statsIcon = findViewById(R.id.nav_stats_icon);
        TextView statsLabel = findViewById(R.id.nav_stats_label);
        statsIcon.setColorFilter(ContextCompat.getColor(this, R.color.toasted_almond), PorterDuff.Mode.SRC_IN);
        statsLabel.setTextColor(ContextCompat.getColor(this, R.color.toasted_almond));

        findViewById(R.id.nav_home).setOnClickListener(v -> {
            Intent intent = new Intent(this, HomepageActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });
        findViewById(R.id.nav_fab).setOnClickListener(v -> {
            startActivity(new Intent(this, SelectActivity.class));
            finish();
        });
        findViewById(R.id.nav_stats).setOnClickListener(v -> {
            // Ya estamos aqui.
        });
    }

    private void loadStatistics() {
        ioExecutor.execute(() -> {
            SessionRepository repository = RizeApplication.get().getSessionRepository();

            // Cargar resumen local
            LocalSummary summary = repository.getLocalSummaryBlocking();
            List<ExerciseStats> exerciseStats = repository.getExerciseStatsBlocking();

            // Cargar sesiones
            List<WorkoutSession> sessions = repository.getAllSessionsBlocking();
            List<SessionCardModel> rows = new ArrayList<>();
            for (WorkoutSession session : sessions) {
                rows.add(buildCardModel(repository, session));
            }

            runOnUiThread(() -> {
                updateLocalSummary(summary);
                exerciseStatsAdapter.submit(exerciseStats);
                sessionAdapter.submit(rows);

                boolean isEmpty = rows.isEmpty();
                emptyView.setVisibility(isEmpty ? TextView.VISIBLE : TextView.GONE);
                sessionsListView.setVisibility(isEmpty ? RecyclerView.GONE : RecyclerView.VISIBLE);
            });
        });
    }

    private void updateLocalSummary(LocalSummary summary) {
        TextView totalSessionsLabel = findViewById(R.id.total_sessions_label);
        TextView squatCount = findViewById(R.id.squat_count);
        TextView curlCount = findViewById(R.id.curl_count);
        TextView benchCount = findViewById(R.id.bench_count);

        totalSessionsLabel.setText(String.format(Locale.getDefault(), "%d sesiones guardadas", summary.getTotalSessions()));

        squatCount.setText(String.valueOf(summary.getSessionsByType().get("squat")));
        curlCount.setText(String.valueOf(summary.getSessionsByType().get("curl")));
        benchCount.setText(String.valueOf(summary.getSessionsByType().get("bench")));
    }

    private void loadSessions() {
        ioExecutor.execute(() -> {
            SessionRepository repository = RizeApplication.get().getSessionRepository();
            List<WorkoutSession> sessions = repository.getAllSessionsBlocking();
            List<SessionCardModel> rows = new ArrayList<>();
            for (WorkoutSession session : sessions) {
                rows.add(buildCardModel(repository, session));
            }

            runOnUiThread(() -> {
                sessionAdapter.submit(rows);
                boolean isEmpty = rows.isEmpty();
                emptyView.setVisibility(isEmpty ? TextView.VISIBLE : TextView.GONE);
                sessionsListView.setVisibility(isEmpty ? RecyclerView.GONE : RecyclerView.VISIBLE);
            });
        });
    }

    private SessionCardModel buildCardModel(SessionRepository repository, WorkoutSession session) {
        String title = session.getExerciseName();
        String date = buildDate(session);
        String commonError = buildCommonErrorText(repository, session);
        String type = mapExerciseType(session.getExerciseType());
        String repsDuration = String.format(
                Locale.getDefault(),
                "%d reps • %s",
                session.getTotalReps(),
                formatDuration(session.getDurationSeconds())
        );
        return new SessionCardModel(title, date, commonError, type, repsDuration, session.getAutoSaved());
    }

    private String buildDate(WorkoutSession session) {
        Date date = new Date(session.getStartedAt());
        return new SimpleDateFormat("dd MMM. yyyy HH:mm", Locale.getDefault()).format(date);
    }

    private String mapExerciseType(String type) {
        if (WorkoutSession.TYPE_SQUAT.equals(type)) {
            return "Sentadilla";
        }
        if (WorkoutSession.TYPE_CURL.equals(type)) {
            return "Curl";
        }
        if (WorkoutSession.TYPE_BENCH.equals(type)) {
            return "Banca";
        }
        return "Otro";
    }

    private String buildCommonErrorText(SessionRepository repository, WorkoutSession session) {
        String dominant = buildDominantExerciseError(repository, session);
        String severity = mapSeverityLabel(session.getTechnicalErrorLevel());

        if (dominant == null) {
            return getString(R.string.stats_common_error_legacy, severity);
        }

        if ("NONE".equals(session.getTechnicalErrorLevel()) || session.getTechnicalErrorLevel() == null) {
            return getString(R.string.stats_common_error_legacy, dominant);
        }
        return getString(R.string.stats_common_error_legacy, dominant + " · Nivel " + severity);
    }

    private String mapSeverityLabel(String raw) {
        if (raw == null || raw.trim().isEmpty() || "NONE".equals(raw)) {
            return getString(R.string.stats_error_level_none);
        }
        switch (raw) {
            case "MILD":
                return getString(R.string.stats_error_level_mild);
            case "MODERATE":
                return getString(R.string.stats_error_level_moderate);
            case "SEVERE":
                return getString(R.string.stats_error_level_severe);
            default:
                return raw;
        }
    }

    private String buildDominantExerciseError(SessionRepository repository, WorkoutSession session) {
        int totalReps = Math.max(0, session.getTotalReps());
        String type = session.getExerciseType();

        if (WorkoutSession.TYPE_SQUAT.equals(type)) {
            SquatSessionDetails d = repository.getSquatDetailsBlocking(session.getId());
            if (d == null || totalReps == 0) return null;
            int depth = d.getDepthInsufficientCount();
            int trunk = d.getTrunkLeanRiskCount();
            if (depth <= 0 && trunk <= 0) return null;
            if (depth >= trunk) return formatDominant("Profundidad insuficiente", depth, totalReps);
            return formatDominant("Tronco inclinado", trunk, totalReps);
        }

        if (WorkoutSession.TYPE_BENCH.equals(type)) {
            BenchSessionDetails d = repository.getBenchDetailsBlocking(session.getId());
            if (d == null || totalReps == 0) return null;
            int depth = d.getDepthInsufficientCount();
            int ext = d.getExtensionIncompleteCount();
            int asym = d.getBilateralAsymmetryCount();
            int grip = d.getGripTooWideCount();
            int stick = d.getStickingPeriodCount();
            int best = Math.max(depth, Math.max(ext, Math.max(asym, Math.max(grip, stick))));
            if (best <= 0) return null;
            if (best == depth) return formatDominant("Profundidad insuficiente", depth, totalReps);
            if (best == ext) return formatDominant("Extensión incompleta", ext, totalReps);
            if (best == asym) return formatDominant("Asimetría bilateral", asym, totalReps);
            if (best == grip) return formatDominant("Agarre demasiado ancho", grip, totalReps);
            return formatDominant("Sticking period", stick, totalReps);
        }

        if (WorkoutSession.TYPE_CURL.equals(type)) {
            CurlSessionDetails d = repository.getCurlDetailsBlocking(session.getId());
            if (d == null) return null;
            Double avgRom = d.getAvgRomDeg();
            Double shoulder = d.getAvgShoulderCompensationDeg();
            if (avgRom != null && avgRom < 110.0) return "Rango de movimiento bajo (<110°)";
            if (shoulder != null && shoulder > 15.0) return "Compensación de hombro elevada";
        }
        return null;
    }

    private String formatDominant(String label, int count, int totalReps) {
        int pct = (int) Math.round((count * 100.0) / Math.max(1, totalReps));
        return String.format(Locale.getDefault(), "%s (%d reps, %d%%)", label, count, pct);
    }

    private String formatDuration(int seconds) {
        int min = seconds / 60;
        int sec = seconds % 60;
        if (min > 0) {
            return String.format(Locale.getDefault(), "%d min %d s", min, sec);
        }
        return String.format(Locale.getDefault(), "%d s", sec);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    // ──── Models & Adapters ────────────────────────────────────────────────────

    private static class SessionCardModel {
        final String title;
        final String date;
        final String commonError;
        final String type;
        final String repsDuration;
        final boolean autoSaved;

        SessionCardModel(String title, String date, String commonError, String type, String repsDuration, boolean autoSaved) {
            this.title = title;
            this.date = date;
            this.commonError = commonError;
            this.type = type;
            this.repsDuration = repsDuration;
            this.autoSaved = autoSaved;
        }
    }

    // ──── Adapter para sesiones recientes ───────────────────────────────────────

    private static class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.Holder> {
        private final List<SessionCardModel> items = new ArrayList<>();

        void submit(List<SessionCardModel> values) {
            items.clear();
            items.addAll(values);
            notifyDataSetChanged();
        }

        @Override
        public Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            android.view.View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_stats_session, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            SessionCardModel item = items.get(position);
            holder.title.setText(item.title);
            holder.date.setText(item.date);
            holder.error.setText(item.commonError);
            holder.type.setText(item.type);
            holder.repsDuration.setText(item.repsDuration);
            holder.autoBadge.setVisibility(item.autoSaved ? View.VISIBLE : View.GONE);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView date;
            final TextView error;
            final TextView type;
            final TextView repsDuration;
            final TextView autoBadge;

            Holder(android.view.View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.item_title);
                date = itemView.findViewById(R.id.item_date);
                error = itemView.findViewById(R.id.item_error);
                type = itemView.findViewById(R.id.item_type);
                repsDuration = itemView.findViewById(R.id.item_reps_duration);
                autoBadge = itemView.findViewById(R.id.item_auto);
            }
        }
    }

    // ──── Adapter para estadísticas por ejercicio ────────────────────────────────
    private static class ExerciseStatsAdapter extends RecyclerView.Adapter<ExerciseStatsAdapter.Holder> {
        private final List<ExerciseStats> items = new ArrayList<>();

        void submit(List<ExerciseStats> values) {
            items.clear();
            items.addAll(values);
            notifyDataSetChanged();
        }

        @Override
        public Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            android.view.View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_exercise_stats_card, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            ExerciseStats item = items.get(position);
            holder.exerciseName.setText(item.getDisplayName());
            holder.sessionCount.setText(String.valueOf(item.getSessionCount()));

            if (item.getAvgReps() != null) {
                holder.avgReps.setText(String.format(Locale.getDefault(), "%.1f", item.getAvgReps()));
            } else {
                holder.avgReps.setText("--");
            }

            if (item.getAvgDurationSec() != null) {
                holder.avgDuration.setText(String.format(Locale.getDefault(), "~%ds",
                        Math.round(item.getAvgDurationSec())));
            } else {
                holder.avgDuration.setText("--");
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView exerciseName;
            final TextView sessionCount;
            final TextView avgReps;
            final TextView avgDuration;

            Holder(android.view.View itemView) {
                super(itemView);
                exerciseName = itemView.findViewById(R.id.exercise_name);
                sessionCount = itemView.findViewById(R.id.session_count);
                avgReps = itemView.findViewById(R.id.avg_reps);
                avgDuration = itemView.findViewById(R.id.avg_duration);
            }
        }
    }
}






