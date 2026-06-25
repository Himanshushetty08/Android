package com.example.database.service.crash;

import android.os.FileObserver;
import android.util.Log;

public class CrashWatcher {

    private static final String TAG = "CrashWatcher";

    private final String path;
    private final CrashCallback callback;
    private FileObserver observer;

    public interface CrashCallback {
        void onCrashDetected(String filePath);
    }

    public CrashWatcher(String path, CrashCallback callback) {
        this.path = path;
        this.callback = callback;
    }

    public void startWatching() {

        observer = new FileObserver(path,
                FileObserver.CREATE |
                        FileObserver.CLOSE_WRITE |
                        FileObserver.MOVED_TO) {

            @Override
            public void onEvent(int event, String file) {

                if (file == null) return;

                String fullPath = path + "/" + file;

                Log.i(TAG, "Crash file detected: " + fullPath);

                if (callback != null) {
                    callback.onCrashDetected(fullPath);
                }
            }
        };

        observer.startWatching();

        Log.i(TAG, "Watching crash directory: " + path);
    }

    public void stopWatching() {
        if (observer != null) {
            observer.stopWatching();
        }
    }
}