package com.rize.rizeandroid;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ExerciseAdapter extends RecyclerView.Adapter<ExerciseAdapter.ViewHolder> {

    public static class Exercise {
        public final String name;
        public final String category;
        public final String muscles;
        public final int imageRes;
        public final ExerciseType exerciseType;

        public Exercise(String name, String category, String muscles, int imageRes, ExerciseType exerciseType) {
            this.name = name;
            this.category = category;
            this.muscles = muscles;
            this.imageRes = imageRes;
            this.exerciseType = exerciseType;
        }
    }

    public interface OnExerciseClickListener {
        void onStartSets(Exercise exercise);
    }

    private final List<Exercise> exercises;
    private final OnExerciseClickListener listener;

    public ExerciseAdapter(List<Exercise> exercises, OnExerciseClickListener listener) {
        this.exercises = exercises;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_exercise, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Exercise exercise = exercises.get(position);
        holder.name.setText(exercise.name);
        holder.category.setText(exercise.category);
        holder.muscles.setText(exercise.muscles);

        if (exercise.imageRes != 0) {
            holder.image.setImageResource(exercise.imageRes);
        }

        holder.btnStartSets.setOnClickListener(v -> {
            if (listener != null) {
                listener.onStartSets(exercise);
            }
        });
    }

    @Override
    public int getItemCount() {
        return exercises.size();
    }

    public void updateList(List<Exercise> filteredList) {
        exercises.clear();
        exercises.addAll(filteredList);
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView category;
        final TextView muscles;
        final ImageView image;
        final TextView btnStartSets;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.exercise_name);
            category = itemView.findViewById(R.id.exercise_category);
            muscles = itemView.findViewById(R.id.exercise_muscles);
            image = itemView.findViewById(R.id.exercise_image);
            btnStartSets = itemView.findViewById(R.id.btn_start_sets);
        }
    }
}
