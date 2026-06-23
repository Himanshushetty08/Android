package com.ultraviolette.s3service.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.StatFs;
import android.os.UserManager;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LogCleanupReceiver extends BroadcastReceiver {

    private static final String TAG = "LogCleanupReceiver";

    private static final String[] LOG_DIRS = {
            "/data/vendor/udp_logs"
    };

    private static final long SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000;
    private static final double STORAGE_THRESHOLD = 80.0;

    @Override
    public void onReceive(Context context, Intent intent) {


        UserManager um =
                (UserManager) context.getSystemService(Context.USER_SERVICE);

        if (um == null || !um.isSystemUser()) {
            Log.i(TAG, "Ignoring trigger for non-system user");
            return;
        }
        try {
            if (!"com.ultraviolette.s3service.ACTION_CLEANUP_LOGS".equals(intent.getAction())) {
                Log.w(TAG, "Ignoring unknown action: " + intent.getAction());
                return;
            }

            double usedPct = intent.getDoubleExtra("used_pct", 0.0);
            Log.w(TAG, "========================================");
            Log.w(TAG, "CLEANUP BROADCAST RECEIVED");
            Log.w(TAG, "Storage used: " + usedPct + "%");
            Log.w(TAG, "========================================");

            new Thread(() -> {
                try {
                    Log.i(TAG, "Cleanup thread started");

                    // ── STEP 1: Delete files older than 7 days ──
                    Log.i(TAG, "STEP 1: Deleting files older than 7 days...");
                    for (String dirPath : LOG_DIRS) {
                        try {
                            Log.i(TAG, "Processing directory: " + dirPath);
                            deleteOldFiles(new File(dirPath));
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing directory: " + dirPath, e);
                        }
                    }

                    // ── STEP 2: Re-check storage after deleting old files ──
                    double afterStep1 = getUsedStoragePct();
                    Log.w(TAG, "Storage after 7-day cleanup: " + afterStep1 + "%");

                    if (afterStep1 >= STORAGE_THRESHOLD) {

                        // ── STEP 3: Still high → delete oldest files one by one ──
                        Log.w(TAG, "STEP 2: Still above " + STORAGE_THRESHOLD
                                + "% — deleting oldest files one by one until storage is free...");

                        deleteOldestUntilFree();

                    } else {
                        Log.i(TAG, "Storage now at " + afterStep1 + "% — no further cleanup needed");
                    }

                    Log.i(TAG, "========================================");
                    Log.i(TAG, "CLEANUP COMPLETE — final storage: " + getUsedStoragePct() + "%");
                    Log.i(TAG, "========================================");

                } catch (Exception e) {
                    Log.e(TAG, "Fatal error in cleanup thread", e);
                    Log.e(TAG, "Exception type: " + e.getClass().getName());
                    Log.e(TAG, "Exception message: " + e.getMessage());
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onReceive", e);
            Log.e(TAG, "Exception type: " + e.getClass().getName());
            Log.e(TAG, "Exception message: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // STEP 1 — Delete files older than 7 days
    // ─────────────────────────────────────────────
    private void deleteOldFiles(File dir) {
        try {
            if (!dir.exists()) {
                Log.e(TAG, "Directory does NOT exist: " + dir.getAbsolutePath());
                return;
            }

            if (!dir.canRead() || !dir.isDirectory()) {
                Log.e(TAG, "Directory NOT accessible: " + dir.getAbsolutePath());
                return;
            }

            Log.i(TAG, "Directory accessible: " + dir.getAbsolutePath());

            long cutoff = System.currentTimeMillis() - SEVEN_DAYS_MS;

            List<File> allFiles = new ArrayList<>();
            collectFilesRecursively(dir, allFiles);
            Log.i(TAG, "Total files found: " + allFiles.size());

            if (allFiles.isEmpty()) {
                Log.w(TAG, "No files found in: " + dir.getAbsolutePath());
                return;
            }

            int deleted = 0, kept = 0, failed = 0;

            for (File file : allFiles) {
                try {
                    long ageDays = (System.currentTimeMillis() - file.lastModified())
                            / (1000 * 60 * 60 * 24);

                    if (file.lastModified() < cutoff) {
                        try {
                            if (file.delete()) {
                                Log.d(TAG, "DELETED: " + file.getName()
                                        + " | age: " + ageDays + " days");
                                deleted++;
                            } else {
                                Log.w(TAG, "FAILED TO DELETE: " + file.getName()
                                        + " | age: " + ageDays + " days");
                                failed++;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception deleting: " + file.getName(), e);
                            failed++;
                        }
                    } else {
                        Log.d(TAG, "KEEPING: " + file.getName()
                                + " | age: " + ageDays + " days (within 7 days)");
                        kept++;
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error processing file: " + file.getName(), e);
                    failed++;
                }
            }

            Log.i(TAG, "-----------------------------");
            Log.i(TAG, "SUMMARY for: " + dir.getName());
            Log.i(TAG, "Total scanned : " + allFiles.size());
            Log.i(TAG, "Deleted       : " + deleted);
            Log.i(TAG, "Kept          : " + kept);
            Log.i(TAG, "Failed        : " + failed);
            Log.i(TAG, "-----------------------------");

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in deleteOldFiles: " + dir.getAbsolutePath(), e);
        }
    }

    // ─────────────────────────────────────────────
    // STEP 2 — Delete oldest files one by one until storage < 80%
    // ─────────────────────────────────────────────
    private void deleteOldestUntilFree() {
        try {
            // Collect ALL remaining files across all dirs
            List<File> allFiles = new ArrayList<>();
            for (String dirPath : LOG_DIRS) {
                try {
                    collectFilesRecursively(new File(dirPath), allFiles);
                } catch (Exception e) {
                    Log.e(TAG, "Error collecting files from: " + dirPath, e);
                }
            }

            if (allFiles.isEmpty()) {
                Log.w(TAG, "No files left to delete in aggressive cleanup");
                return;
            }

            // Sort oldest first (smallest lastModified = oldest)
            Collections.sort(allFiles,
                    (f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified())
            );

            Log.w(TAG, "Aggressive cleanup: " + allFiles.size()
                    + " files available, deleting oldest first...");

            int deleted = 0;

            for (File file : allFiles) {
                try {
                    double currentPct = getUsedStoragePct();

                    if (currentPct < STORAGE_THRESHOLD) {
                        Log.i(TAG, "Storage now at " + currentPct
                                + "% — stopping aggressive cleanup");
                        break;
                    }

                    long ageDays = (System.currentTimeMillis() - file.lastModified())
                            / (1000 * 60 * 60 * 24);

                    if (file.delete()) {
                        deleted++;
                        Log.w(TAG, "AGGRESSIVE DELETE: " + file.getName()
                                + " | age: " + ageDays + " days"
                                + " | storage now: " + getUsedStoragePct() + "%");
                    } else {
                        Log.w(TAG, "Failed aggressive delete: " + file.getName());
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error in aggressive delete for: " + file.getName(), e);
                }
            }

            Log.w(TAG, "Aggressive cleanup done — total deleted: " + deleted
                    + " | final storage: " + getUsedStoragePct() + "%");

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in deleteOldestUntilFree", e);
        }
    }

    // ─────────────────────────────────────────────
    // Get current storage used %
    // ─────────────────────────────────────────────
    private double getUsedStoragePct() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long total = stat.getTotalBytes();
            long available = stat.getAvailableBytes();
            return ((double)(total - available) / total) * 100.0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get storage stats", e);
            return 0.0;
        }
    }

    // ─────────────────────────────────────────────
    // Recursive file collector
    // ─────────────────────────────────────────────
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
                        Log.d(TAG, "Entering sub-directory: " + f.getName());
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