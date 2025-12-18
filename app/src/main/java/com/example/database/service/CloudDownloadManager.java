
package com.example.database.service;

import android.content.Context;
import android.util.Log;

import com.example.database.db.AppDatabase;
import com.example.database.db.FileDownloadDao;
import com.example.database.db.FileDownloadRecord;
import com.example.database.utils.NetworkUtils;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CloudDownloadManager {

    private static final String TAG = "CloudDownloadMgr";
    private static final String DOWNLOAD_DIR = "/data/local/tmp/cloud_download";

    // ONE THREAD FOR LIFE OF APP — NEVER DIES
    private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newSingleThreadExecutor();

    public static void processDownloads(Context context) {
        DOWNLOAD_EXECUTOR.execute(() -> {
            Log.i(TAG, "DOWNLOAD SERVICE: Starting scheduled check...");
            Log.d(TAG, "Scheduled every 15 minutes");

            if (!NetworkUtils.isWifiConnected(context)) {
                Log.i(TAG, "WAITING FOR WIFI: Download paused");
                return;
            }

            Log.i(TAG, "WIFI CONNECTED: Proceeding");

            try {
                fetchAvailableFiles(context);
                downloadPendingFiles(context);
                retryFailedDownloads(context);
                Log.i(TAG, "DOWNLOAD SERVICE COMPLETE");
            } catch (Exception e) {
                Log.e(TAG, "DOWNLOAD SERVICE ERROR", e);
            }
        });
    }

    private static void fetchAvailableFiles(Context context) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            FileDownloadDao dao = db.fileDownloadDao();
            new CloudDownloader(context, dao).listS3Files();
        } catch (Exception e) {
            Log.e(TAG, "Error fetching files", e);
        }
    }

    private static void downloadPendingFiles(Context context) {
        try {
            File downloadDir = new File(DOWNLOAD_DIR);
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
                downloadDir.setReadable(true, false);
                downloadDir.setWritable(true, false);
                Log.i(TAG, "CREATED: " + downloadDir.getAbsolutePath());
            }

            AppDatabase db = AppDatabase.getInstance(context);
            FileDownloadDao dao = db.fileDownloadDao();
            CloudDownloader downloader = new CloudDownloader(context, dao);

            var pending = dao.getPendingDownloads();
            if (pending.isEmpty()) {
                Log.d(TAG, "No pending downloads");
                return;
            }

            Log.i(TAG, "Found " + pending.size() + " files to download");

            for (FileDownloadRecord record : pending) {
                File targetFile = new File(downloadDir, record.fileName);

                if (targetFile.exists()) {
                    record.status = "completed";
                    dao.update(record);
                    Log.d(TAG, "Already exists: " + record.fileName);
                    continue;
                }

                Log.i(TAG, "DOWNLOADING: " + record.fileName);
                downloader.downloadFile(record, targetFile);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error downloading pending files", e);
        }
    }

    private static void retryFailedDownloads(Context context) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            FileDownloadDao dao = db.fileDownloadDao();
            CloudDownloader downloader = new CloudDownloader(context, dao);

            long now = System.currentTimeMillis();
            long thirtyMinAgo = now - (30 * 60 * 1000);
            var retryList = dao.getFailedDownloadsReadyForRetry(thirtyMinAgo);

            if (retryList.isEmpty()) {
                var allFailed = dao.getFailedDownloads();
                if (!allFailed.isEmpty()) {
                    long oldest = allFailed.get(0).timestamp;
                    long wait = (oldest + 30 * 60 * 1000) - now;
                    if (wait > 0) {
                        long mins = wait / (60 * 1000);
                        Log.i(TAG, "WAITING: " + allFailed.size() + " failed, retry in " + mins + " min");
                    }
                } else {
                    Log.d(TAG, "No failed downloads");
                }
                return;
            }

            Log.i(TAG, "RETRYING " + retryList.size() + " files (30+ min old)");

            File downloadDir = new File(DOWNLOAD_DIR);

            for (FileDownloadRecord record : retryList) {
                // NEVER LET 15-MIN SCHEDULER TOUCH OTA FILES
                if (record.fileName.startsWith("fota/")) {
                    Log.i(TAG, "SKIPPING OTA in retry (handled by direct trigger): " + record.fileName);
                    continue;
                }

                File targetFile = new File(downloadDir, record.fileName);
                Log.i(TAG, "RETRY: " + record.fileName + " (attempt " + (record.retryCount + 1) + ") - " + record.failureReason);
                downloader.downloadFile(record, targetFile);

                if ("completed".equals(record.status)) {
                    Log.i(TAG, "RETRY SUCCESS: " + record.fileName);
                } else {
                    Log.w(TAG, "RETRY FAILED: " + record.fileName + " - next in 30 min");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in retry", e);
        }
    }
}