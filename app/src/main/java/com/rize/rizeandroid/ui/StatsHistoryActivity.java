package com.rize.rizeandroid.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.rize.rizeandroid.R;
import com.rize.rizeandroid.RizeApplication;
import com.rize.rizeandroid.data.SessionRepository;
import com.rize.rizeandroid.data.SessionRepository.ExerciseStats;
import com.rize.rizeandroid.data.SessionRepository.LocalSummary;
import com.rize.rizeandroid.data.entity.WorkoutSession;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
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
    private View filterRow;
    private MaterialButton filterSquat;
    private MaterialButton filterCurl;
    private MaterialButton filterBench;

    private final List<SessionCardModel> allSessionCards = new ArrayList<>();
    private final List<ExerciseStats> allExerciseStats = new ArrayList<>();
    private String selectedExerciseType = WorkoutSession.TYPE_SQUAT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats_history);

        sessionsListView = findViewById(R.id.stats_list);
        exerciseStatsListView = findViewById(R.id.exercise_stats_list);
        emptyView = findViewById(R.id.stats_empty);

        sessionAdapter = new SessionAdapter(sessionId -> {
            Intent i = new Intent(StatsHistoryActivity.this, SessionHistoryDetailActivity.class);
            i.putExtra(SessionHistoryDetailActivity.EXTRA_SESSION_ID, sessionId);
            startActivity(i);
        });
        exerciseStatsAdapter = new ExerciseStatsAdapter();

        sessionsListView.setLayoutManager(new LinearLayoutManager(this));
        sessionsListView.setAdapter(sessionAdapter);
        exerciseStatsListView.setLayoutManager(new LinearLayoutManager(this));
        exerciseStatsListView.setAdapter(exerciseStatsAdapter);
        // RecyclerViews dentro del NestedScrollView: sin scroll propio para que el padre pueda
        // medir todo el contenido y desplazarse por la lista completa de sesiones.
        ViewCompat.setNestedScrollingEnabled(sessionsListView, false);
        ViewCompat.setNestedScrollingEnabled(exerciseStatsListView, false);

        filterRow = findViewById(R.id.stats_filter_row);
        filterSquat = findViewById(R.id.filter_squat);
        filterCurl = findViewById(R.id.filter_curl);
        filterBench = findViewById(R.id.filter_bench);
        setupExerciseFilters();

        setupToolbar();
        setupBottomNav();
        loadStatistics();
    }

    private void setupExerciseFilters() {
        filterSquat.setOnClickListener(v -> setSelectedExerciseFilter(WorkoutSession.TYPE_SQUAT));
        filterCurl.setOnClickListener(v -> setSelectedExerciseFilter(WorkoutSession.TYPE_CURL));
        filterBench.setOnClickListener(v -> setSelectedExerciseFilter(WorkoutSession.TYPE_BENCH));
    }

    private void setSelectedExerciseFilter(String type) {
        if (type.equals(selectedExerciseType)) {
            return;
        }
        selectedExerciseType = type;
        refreshFilterButtonStyles();
        applyExerciseFilter();
    }

    private void refreshFilterButtonStyles() {
        styleFilterButton(filterSquat, WorkoutSession.TYPE_SQUAT.equals(selectedExerciseType));
        styleFilterButton(filterCurl, WorkoutSession.TYPE_CURL.equals(selectedExerciseType));
        styleFilterButton(filterBench, WorkoutSession.TYPE_BENCH.equals(selectedExerciseType));
    }

    private void styleFilterButton(MaterialButton b, boolean selected) {
        int almond = ContextCompat.getColor(this, R.color.toasted_almond);
        int panel = ContextCompat.getColor(this, R.color.shadow_grey_light);
        int border = ContextCompat.getColor(this, R.color.card_border);
        int white = ContextCompat.getColor(this, R.color.white);
        int black = ContextCompat.getColor(this, R.color.black);
        int strokePx = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 1f, getResources().getDisplayMetrics()));
        if (selected) {
            b.setBackgroundTintList(ColorStateList.valueOf(almond));
            b.setTextColor(black);
            b.setStrokeWidth(0);
            b.setStrokeColor(ColorStateList.valueOf(almond));
        } else {
            b.setBackgroundTintList(ColorStateList.valueOf(panel));
            b.setTextColor(white);
            b.setStrokeWidth(strokePx);
            b.setStrokeColor(ColorStateList.valueOf(border));
        }
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

            LocalSummary summary = repository.getLocalSummaryBlocking();
            List<ExerciseStats> exerciseStats = repository.getExerciseStatsBlocking();

            List<WorkoutSession> sessions = repository.getAllSessionsBlocking();
            List<SessionCardModel> rows = new ArrayList<>();
            for (WorkoutSession session : sessions) {
                rows.add(buildCardModel(repository, session));
            }

            runOnUiThread(() -> {
                updateLocalSummary(summary);
                allExerciseStats.clear();
                allExerciseStats.addAll(exerciseStats);
                allSessionCards.clear();
                allSessionCards.addAll(rows);

                boolean globalEmpty = allSessionCards.isEmpty();
                filterRow.setVisibility(globalEmpty ? View.GONE : View.VISIBLE);

                if (!globalEmpty) {
                    pickDefaultExerciseFilter();
                    refreshFilterButtonStyles();
                }

                applyExerciseFilter();
            });
        });
    }

    /** Primera pestaña que tenga al menos una sesión; si no, sentadilla. */
    private void pickDefaultExerciseFilter() {
        String[] order = {
                WorkoutSession.TYPE_SQUAT,
                WorkoutSession.TYPE_CURL,
                WorkoutSession.TYPE_BENCH
        };
        for (String type : order) {
            for (SessionCardModel m : allSessionCards) {
                if (type.equals(m.exerciseTypeKey)) {
                    selectedExerciseType = type;
                    return;
                }
            }
        }
        selectedExerciseType = WorkoutSession.TYPE_SQUAT;
    }

    private void applyExerciseFilter() {
        List<SessionCardModel> filtered = new ArrayList<>();
        for (SessionCardModel m : allSessionCards) {
            if (selectedExerciseType.equals(m.exerciseTypeKey)) {
                filtered.add(m);
            }
        }
        sessionAdapter.submit(filtered);

        ExerciseStats match = null;
        for (ExerciseStats e : allExerciseStats) {
            if (selectedExerciseType.equals(e.getType())) {
                match = e;
                break;
            }
        }
        if (match != null) {
            exerciseStatsAdapter.submit(Collections.singletonList(match));
        } else {
            exerciseStatsAdapter.submit(Collections.emptyList());
        }

        boolean globalEmpty = allSessionCards.isEmpty();
        boolean filteredEmpty = filtered.isEmpty();

        if (globalEmpty) {
            emptyView.setText(R.string.stats_empty);
            emptyView.setVisibility(View.VISIBLE);
            sessionsListView.setVisibility(View.GONE);
            exerciseStatsListView.setVisibility(View.GONE);
        } else if (filteredEmpty) {
            emptyView.setText(getString(R.string.stats_empty_filtered, mapExerciseType(selectedExerciseType)));
            emptyView.setVisibility(View.VISIBLE);
            sessionsListView.setVisibility(View.GONE);
            exerciseStatsListView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
            sessionsListView.setVisibility(View.VISIBLE);
            exerciseStatsListView.setVisibility(View.VISIBLE);
        }
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

    private SessionCardModel buildCardModel(SessionRepository repository, WorkoutSession session) {
        String title = session.getExerciseName();
        String date = buildDate(session);
        String repQualityLine = buildRepQualityLine(repository, session);
        String type = mapExerciseType(session.getExerciseType());
        String repsDuration = String.format(
                Locale.getDefault(),
                "%d reps • %s",
                session.getTotalReps(),
                formatDuration(session.getDurationSeconds())
        );
        return new SessionCardModel(
                session.getId(),
                session.getExerciseType(),
                title,
                date,
                repQualityLine,
                type,
                repsDuration,
                session.getAutoSaved());
    }

    /** Misma lógica que el resumen: % reps válidas vs intentos con mala técnica. */
    private String buildRepQualityLine(SessionRepository repository, WorkoutSession session) {
        if (WorkoutSession.TYPE_CURL.equals(session.getExerciseType())) {
            SessionRepository.CurlRepQualityCounts counts =
                    repository.getCurlRepQualityCountsBlocking(session.getId());
            int attempted = counts.getGood() + counts.getAlmost() + counts.getBad();
            if (attempted == 0) return getString(R.string.stats_session_rep_quality_line_curl, 0, 0, 0);
            int goodPct   = Math.round(counts.getGood()   * 100f / attempted);
            int almostPct = Math.round(counts.getAlmost() * 100f / attempted);
            int badPct    = Math.round(counts.getBad()    * 100f / attempted);
            return getString(R.string.stats_session_rep_quality_line_curl, goodPct, almostPct, badPct);
        }
        int qualityReps = Math.max(0, session.getTotalReps());
        int attempted = repository.getAttemptedRepCountBlocking(session);
        if (attempted < qualityReps) {
            attempted = qualityReps;
        }
        int badReps = Math.max(0, attempted - qualityReps);
        int goodPct = attempted > 0 ? Math.round(qualityReps * 100f / attempted) : 100;
        int badPct = attempted > 0 ? Math.round(badReps * 100f / attempted) : 0;
        return getString(R.string.stats_session_rep_quality_line, goodPct, badPct);
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
        final long sessionId;
        final String exerciseTypeKey;
        final String title;
        final String date;
        final String repQualityLine;
        final String type;
        final String repsDuration;
        final boolean autoSaved;

        SessionCardModel(
                long sessionId,
                String exerciseTypeKey,
                String title,
                String date,
                String repQualityLine,
                String type,
                String repsDuration,
                boolean autoSaved) {
            this.sessionId = sessionId;
            this.exerciseTypeKey = exerciseTypeKey;
            this.title = title;
            this.date = date;
            this.repQualityLine = repQualityLine;
            this.type = type;
            this.repsDuration = repsDuration;
            this.autoSaved = autoSaved;
        }
    }

    // ──── Adapter para sesiones recientes ───────────────────────────────────────

    private static class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.Holder> {
        interface OnSessionClickListener {
            void onSessionClick(long sessionId);
        }

        private final List<SessionCardModel> items = new ArrayList<>();
        private final OnSessionClickListener clickListener;

        SessionAdapter(OnSessionClickListener clickListener) {
            this.clickListener = clickListener;
        }

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
            holder.error.setText(item.repQualityLine);
            holder.type.setText(item.type);
            holder.repsDuration.setText(item.repsDuration);
            holder.autoBadge.setVisibility(item.autoSaved ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onSessionClick(item.sessionId);
                }
            });
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






