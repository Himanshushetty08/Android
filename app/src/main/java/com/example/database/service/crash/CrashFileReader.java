package com.example.database.service.crash;

import android.util.Log;

import java.io.File;
import java.nio.file.Files;

public class CrashFileReader {

    private static final String TAG = "CrashFileReader";

    public String readFile(String path) {

        try {

            File file = new File(path);

            if (!file.exists()) return null;

            return new String(Files.readAllBytes(file.toPath()));

        } catch (Exception e) {

            Log.e(TAG, "Read error", e);
            return null;

        }
    }
}