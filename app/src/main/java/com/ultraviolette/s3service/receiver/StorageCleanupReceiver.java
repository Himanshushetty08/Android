package com.ultraviolette.s3service.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.util.Log;

import com.ultraviolette.s3service.db.AppDatabase;
import com.ultraviolette.s3service.db.FileUploadDao;
import com.ultraviolette.s3service.db.FileUploadRecord;

import java.io.File;
import java.util.List;

public class StorageCleanupReceiver extends BroadcastReceiver {

    private static final String TAG = "StorageCleanupReceiver";

    private static final String LOG_DIR = "/data/vendor/udp_logs";
    private static final double STORAGE_THRESHOLD = 60.0;

    @Override
    public void onReceive(Context context, Intent intent) {


        UserManager um =
                (UserManager) context.getSystemService(Context.USER_SERVICE);

        if (um == null || !um.isSystemUser()) {
            Log.i(TAG, "Ignoring trigger for non-system user");
            return;
        }
        try {
            if (!"com.ultraviolette.s3service.ACTION_STORAGE_CLEANUP".equals(intent.getAction())) {
                Log.w(TAG, "Ignoring unknown action: " + intent.getAction());
                return;
            }

            double usedPct = intent.getDoubleExtra("used_pct", 0.0);

            Log.w(TAG, "========================================");
            Log.w(TAG, "STORAGE CLEANUP BROADCAST RECEIVED");
            Log.w(TAG, "Storage used: " + usedPct + "%");
            Log.w(TAG, "========================================");

            if (usedPct < STORAGE_THRESHOLD) {
                Log.i(TAG, "Storage at " + usedPct + "% — below " + STORAGE_THRESHOLD + "% threshold, no cleanup needed");
                return;
            }

            Log.w(TAG, "Storage at " + usedPct + "% — ABOVE " + STORAGE_THRESHOLD + "% threshold → starting cleanup");

            new Thread(() -> {
                try {
                    deleteUploadedFiles(context);
                } catch (Exception e) {
                    Log.e(TAG, "Fatal error in cleanup thread", e);
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onReceive", e);
        }
    }

    private void deleteUploadedFiles(Context context) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            FileUploadDao dao = db.fileUploadDao();

            List<FileUploadRecord> successRecords = dao.getSuccessfulRecords();

            Log.i(TAG, "Found " + successRecords.size() + " successfully uploaded record(s) in DB");

            if (successRecords.isEmpty()) {
                Log.w(TAG, "No uploaded files to clean up");
                return;
            }

            int deleted = 0;
            int notFound = 0;
            int failed = 0;

            for (FileUploadRecord record : successRecords) {
                try {
                    File file = findFileRecursively(new File(LOG_DIR), record.fileName);

                    if (file == null || !file.exists()) {
                        Log.d(TAG, "File not found on disk (already deleted?): " + record.fileName);
                        notFound++;
                        continue;
                    }

                    if (file.delete()) {
                        deleted++;
                        Log.w(TAG, "DELETED: " + record.fileName);
                    } else {
                        failed++;
                        Log.w(TAG, "FAILED TO DELETE: " + record.fileName);
                    }

                } catch (Exception e) {
                    failed++;
                    Log.e(TAG, "Exception deleting: " + record.fileName, e);
                }
            }

            Log.i(TAG, "-----------------------------");
            Log.i(TAG, "SUMMARY");
            Log.i(TAG, "Total DB records : " + successRecords.size());
            Log.i(TAG, "Deleted          : " + deleted);
            Log.i(TAG, "Not found        : " + notFound);
            Log.i(TAG, "Failed           : " + failed);
            Log.i(TAG, "-----------------------------");

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in deleteUploadedFiles", e);
        }
    }

    private File findFileRecursively(File dir, String filename) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return null;

            for (File f : files) {
                if (f.isDirectory()) {
                    File result = findFileRecursively(f, filename);
                    if (result != null) return result;
                } else if (f.getName().equals(filename)) {
                    return f;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in findFileRecursively: " + dir.getAbsolutePath(), e);
        }
        return null;
    }
}