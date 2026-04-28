package com.rize.rizeandroid;

import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

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

            // Cargar estadísticas por ejercicio
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
        TextView totalSessionsCount = findViewById(R.id.total_sessions_count);
        TextView squatCount = findViewById(R.id.squat_count);
        TextView curlCount = findViewById(R.id.curl_count);
        TextView benchCount = findViewById(R.id.bench_count);

        totalSessionsLabel.setText(String.format(Locale.getDefault(), "%d sesions guardadas", summary.getTotalSessions()));
        totalSessionsCount.setText(String.format(Locale.getDefault(), "%d reps", summary.getTotalReps()));

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
        String meta = buildMeta(session);
        String error = buildCommonErrorText(session.getTechnicalErrorLevel());
        String metrics = buildExerciseMetrics(repository, session);
        return new SessionCardModel(title, meta, error, metrics);
    }

    private String buildMeta(WorkoutSession session) {
        Date date = new Date(session.getStartedAt());
        String dateTxt = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(date);
        return String.format(
                Locale.getDefault(),
                "%s  |  %d reps  |  %ds",
                dateTxt,
                session.getTotalReps(),
                session.getDurationSeconds()
        );
    }

    private String buildCommonErrorText(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return getString(R.string.stats_common_error_none);
        }
        switch (raw) {
            case "NONE":
                return getString(R.string.stats_common_error_none);
            case "MILD":
            case "MODERATE":
            case "SEVERE":
                return getString(R.string.stats_common_error_legacy, raw);
            default:
                return getString(R.string.stats_common_error_format, raw);
        }
    }

    private String buildExerciseMetrics(SessionRepository repository, WorkoutSession session) {
        String type = session.getExerciseType();
        if (WorkoutSession.TYPE_SQUAT.equals(type)) {
            SquatSessionDetails details = repository.getSquatDetailsBlocking(session.getId());
            if (details == null) return getString(R.string.stats_metrics_unavailable);
            return String.format(
                    Locale.getDefault(),
                    "Profundidad insuficiente: %d reps  |  Tronco inclinado: %d reps",
                    details.getDepthInsufficientCount(),
                    details.getTrunkLeanRiskCount()
            );
        }
        if (WorkoutSession.TYPE_BENCH.equals(type)) {
            BenchSessionDetails details = repository.getBenchDetailsBlocking(session.getId());
            if (details == null) return getString(R.string.stats_metrics_unavailable);
            return String.format(
                    Locale.getDefault(),
                    "Profundidad: %d  |  Extension: %d  |  Asimetria: %d",
                    details.getDepthInsufficientCount(),
                    details.getExtensionIncompleteCount(),
                    details.getBilateralAsymmetryCount()
            );
        }

        CurlSessionDetails details = repository.getCurlDetailsBlocking(session.getId());
        if (details == null) return getString(R.string.stats_metrics_unavailable);
        Double avgRom = details.getAvgRomDeg();
        Double avgShoulder = details.getAvgShoulderCompensationDeg();
        String romTxt = avgRom != null ? String.format(Locale.getDefault(), "%.0f", avgRom) : "--";
        String shoulderTxt = avgShoulder != null ? String.format(Locale.getDefault(), "%.0f", avgShoulder) : "--";
        return String.format(
                Locale.getDefault(),
                "ROM promedio: %s deg  |  Compensacion hombro: %s deg",
                romTxt,
                shoulderTxt
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    // ──── Models & Adapters ────────────────────────────────────────────────────

    private static class SessionCardModel {
        final String title;
        final String meta;
        final String commonError;
        final String exerciseMetrics;

        SessionCardModel(String title, String meta, String commonError, String exerciseMetrics) {
            this.title = title;
            this.meta = meta;
            this.commonError = commonError;
            this.exerciseMetrics = exerciseMetrics;
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
            holder.meta.setText(item.meta);
            holder.error.setText(item.commonError);
            holder.metrics.setText(item.exerciseMetrics);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView meta;
            final TextView error;
            final TextView metrics;

            Holder(android.view.View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.item_title);
                meta = itemView.findViewById(R.id.item_meta);
                error = itemView.findViewById(R.id.item_error);
                metrics = itemView.findViewById(R.id.item_metrics);
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
            
            // Mostrar promedio de reps con un decimal
            if (item.getAvgReps() != null) {
                holder.avgReps.setText(String.format(Locale.getDefault(), "%.1f", item.getAvgReps()));
            } else {
                holder.avgReps.setText("--");
            }
            
            // Mostrar duración promedio en formato "Xxs"
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






