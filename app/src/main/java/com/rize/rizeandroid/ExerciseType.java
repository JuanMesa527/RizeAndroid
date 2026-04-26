package com.rize.rizeandroid;

/**
 * Tipo de ejercicio independiente del idioma.
 * Se pasa como extra del Intent ("exercise_type") para que CameraActivity
 * no dependa del nombre localizado del ejercicio.
 */
public enum ExerciseType {
    SQUAT,
    BENCH_PRESS,
    CURL,
    UNKNOWN
}
