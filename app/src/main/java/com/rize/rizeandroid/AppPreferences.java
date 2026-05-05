package com.rize.rizeandroid;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppPreferences {

    private static final String PREFS_NAME = "rize_prefs";
    private static final String KEY_AUTO_SAVE       = "auto_save_enabled";
    private static final boolean DEFAULT_AUTO_SAVE  = true;
    private static final String KEY_FRONT_CAMERA    = "front_camera_preferred";
    private static final boolean DEFAULT_FRONT_CAMERA = true;

    private AppPreferences() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isAutoSaveEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_SAVE, DEFAULT_AUTO_SAVE);
    }

    public static void setAutoSaveEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_SAVE, enabled).apply();
    }

    public static boolean isFrontCameraPreferred(Context ctx) {
        return prefs(ctx).getBoolean(KEY_FRONT_CAMERA, DEFAULT_FRONT_CAMERA);
    }

    public static void setFrontCameraPreferred(Context ctx, boolean front) {
        prefs(ctx).edit().putBoolean(KEY_FRONT_CAMERA, front).apply();
    }
}
