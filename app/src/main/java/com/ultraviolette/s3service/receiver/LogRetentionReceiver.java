package com.ultraviolette.s3service.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LogRetentionReceiver extends BroadcastReceiver {

    private static final String TAG = "LogRetentionReceiver";

    private static final String LOG_DIR = "/data/vendor/udp_logs";
    private static final int DELETE_COUNT_PER_TICK = 5;


    private static boolean isRunning = false;

    @Override
    public void onReceive(Context context, Intent intent) {


        UserManager um =
                (UserManager) context.getSystemService(Context.USER_SERVICE);

        if (um == null || !um.isSystemUser()) {
            Log.i(TAG, "Ignoring trigger for non-system user");
            return;
        }
        try {
            if (!"com.ultraviolette.s3service.ACTION_RETAIN_LOGS".equals(intent.getAction())) {
                Log.w(TAG, "Ignoring unknown action: " + intent.getAction());
                return;
            }

            Log.w(TAG, "========================================");
            Log.w(TAG, "LOG RETENTION BROADCAST RECEIVED");
            Log.w(TAG, "Will delete " + DELETE_COUNT_PER_TICK + " oldest logs this tick");
            Log.w(TAG, "========================================");

//            new Thread(() -> {
//                try {
//                    deleteOldestNLogs(new File(LOG_DIR));
//                } catch (Exception e) {
//                    Log.e(TAG, "Fatal error in retention thread", e);
//                }
//            }).start();


            synchronized (LogRetentionReceiver.class) {
                if (isRunning) {
                    Log.w(TAG, "⚠️ Already running → ignoring duplicate trigger");
                    return;
                }
                isRunning=true;
            }

            Log.w(TAG, " Retention STARTED");


            new Thread(() -> {
                try {
                    deleteOldestNLogs(new File(LOG_DIR));
                } finally {
                    isRunning=false;
                    Log.w(TAG, " Retention FINISHED");
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onReceive", e);
        }
    }

    private void deleteOldestNLogs(File dir) {
        try {
            if (!dir.exists()) {
                Log.e(TAG, "Directory does NOT exist: " + dir.getAbsolutePath());
                return;
            }

            if (!dir.canRead() || !dir.isDirectory()) {
                Log.e(TAG, "Directory NOT accessible: " + dir.getAbsolutePath());
                return;
            }

            List<File> allFiles = new ArrayList<>();
            collectFilesRecursively(dir, allFiles);

            Log.i(TAG, "Total files found: " + allFiles.size());

            if (allFiles.isEmpty()) {
                Log.w(TAG, "No files found — nothing to delete");
                return;
            }

            // Sort oldest first (smallest lastModified = oldest)
            Collections.sort(allFiles,
                    (f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified())
            );

            // Take only the first 5 (oldest)
            int toDeleteCount = Math.min(DELETE_COUNT_PER_TICK, allFiles.size());
            List<File> toDelete = allFiles.subList(0, toDeleteCount);

            int deleted = 0, failed = 0;

            for (File file : toDelete) {
                try {
                    long ageDays = (System.currentTimeMillis() - file.lastModified())
                            / (1000 * 60 * 60 * 24);

                    if (file.delete()) {
                        deleted++;
                        Log.w(TAG, "DELETED: " + file.getName() + " | age: " + ageDays + " days");
                    } else {
                        failed++;
                        Log.w(TAG, "FAILED TO DELETE: " + file.getName() + " | age: " + ageDays + " days");
                    }
                } catch (Exception e) {
                    failed++;
                    Log.e(TAG, "Exception deleting: " + file.getName(), e);
                }
            }

            Log.i(TAG, "-----------------------------");
            Log.i(TAG, "Total before : " + allFiles.size());
            Log.i(TAG, "Deleted      : " + deleted);
            Log.i(TAG, "Failed       : " + failed);
            Log.i(TAG, "Remaining    : " + (allFiles.size() - deleted));
            Log.i(TAG, "-----------------------------");

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in deleteOldestNLogs", e);
        }
    }

    private void collectFilesRecursively(File dir, List<File> out) {
        try {
            File[] files = dir.listFiles();

            if (files == null) {
                Log.w(TAG, "listFiles() returned null for: " + dir.getAbsolutePath());
                return;
            }

            for (File f : files) {
                try {
                    if (f.isDirectory()) {
                        collectFilesRecursively(f, out);
                    } else {
                        out.add(f);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing entry: " + f.getName(), e);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in collectFilesRecursively: " + dir.getAbsolutePath(), e);
        }
    }
}