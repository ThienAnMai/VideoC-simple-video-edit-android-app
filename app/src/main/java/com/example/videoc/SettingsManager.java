package com.example.videoc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;

public class SettingsManager {
    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_OUTPUT_PATH = "output_path";

    private final SharedPreferences sharedPreferences;

    public SettingsManager(Context context) {
        this.sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Saves the user-chosen output path.
     * @param path The absolute path to the directory.
     */
    public void setOutputPath(String path) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_OUTPUT_PATH, path);
        editor.apply();
    }

    /**
     * Retrieves the saved output path. If none is set, it returns the default path.
     * @return The absolute path to the output directory.
     */
    public String getOutputPath() {
        // Default path: /storage/emulated/0/DCIM/VideoC/
        File defaultDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "VideoC");
        return sharedPreferences.getString(KEY_OUTPUT_PATH, defaultDir.getAbsolutePath());
    }
}
