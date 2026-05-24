package com.rize.rizeandroid.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.rize.rizeandroid.R;
import com.rize.rizeandroid.biomechanics.ExerciseType;

import java.util.List;

/**
 * Adaptador para mostrar ejercicios en un RecyclerView.
 */
public class ExerciseAdapter extends RecyclerView.Adapter<ExerciseAdapter.ViewHolder> {

    /**
     * Clase que representa un ejercicio.
     */
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

    /**
     * Interfaz para manejar clics en los ejercicios.
     */
    public interface OnExerciseClickListener {
        void onStartSets(Exercise exercise);
    }

    private final List<Exercise> exercises;
    private final OnExerciseClickListener listener;

    /**
     * Constructor del adaptador.
     * 
     * @param exercises
     * @param listener
     */
    public ExerciseAdapter(List<Exercise> exercises, OnExerciseClickListener listener) {
        this.exercises = exercises;
        this.listener = listener;
    }

    /**
     * Crea un nuevo ViewHolder para un ejercicio.
     * 
     * @param parent
     * @param viewType
     * @return
     */
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_exercise, parent, false);
        return new ViewHolder(view);
    }

    /**
     * Vincula los datos de un ejercicio a un ViewHolder.
     * 
     * @param holder
     * @param position
     */
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

    /**
     * Devuelve el número de ejercicios en la lista.
     * 
     * @return
     */
    @Override
    public int getItemCount() {
        return exercises.size();
    }

    /**
     * Actualiza la lista de ejercicios y notifica al adaptador.
     * 
     * @param filteredList
     */
    public void updateList(List<Exercise> filteredList) {
        exercises.clear();
        exercises.addAll(filteredList);
        notifyDataSetChanged();
    }

    /**
     * Clase que representa un ViewHolder para un ejercicio.
     */
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
