//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//package com.example.database.service;
//
//import android.content.Context;
//import com.example.database.db.AppDatabase;
//import com.example.database.db.FileDownloadDao;
//import com.example.database.db.FileDownloadRecord;
//import com.example.database.utils.NetworkUtils;
//import timber.log.Timber;
//
//import java.io.File;
//import java.util.concurrent.Executors;
//
//public class CloudDownloadManager {
//
//    // FINAL PATH: /data/local/tmp/cloud_download/
//    private static final String DOWNLOAD_DIR = "/data/local/tmp/cloud_download";
//
//    public static void processDownloads(Context context) {
//        Executors.newSingleThreadExecutor().execute(() -> {
//            Timber.i("DOWNLOAD SERVICE: Starting scheduled check...");
//            Timber.d("Scheduled every 15 minutes");
//
//            if (!NetworkUtils.isWifiConnected(context)) {
//                Timber.i("WAITING FOR WIFI: Download paused");
//                return;
//            }
//
//            Timber.i("WIFI CONNECTED: Proceeding");
//
//            try {
//                fetchAvailableFiles(context);
//                downloadPendingFiles(context);
//                retryFailedDownloads(context);
//
//                Timber.i("DOWNLOAD SERVICE COMPLETE");
//
//            } catch (Exception e) {
//                Timber.e(e, "DOWNLOAD SERVICE ERROR");
//            }
//        });
//    }
//
//    private static void fetchAvailableFiles(Context context) {
//        try {
//            AppDatabase db = AppDatabase.getInstance(context);
//            FileDownloadDao dao = db.fileDownloadDao();
//            new CloudDownloader(context, dao).listS3Files();
//        } catch (Exception e) {
//            Timber.e(e, "Error fetching files");
//        }
//    }
//
//    private static void downloadPendingFiles(Context context) {
//        try {
//            File downloadDir = new File(DOWNLOAD_DIR);
//            if (!downloadDir.exists()) {
//                downloadDir.mkdirs();
//                downloadDir.setReadable(true, false);
//                downloadDir.setWritable(true, false);
//                Timber.i("CREATED: %s", downloadDir.getAbsolutePath());
//            }
//
//            AppDatabase db = AppDatabase.getInstance(context);
//            FileDownloadDao dao = db.fileDownloadDao();
//            CloudDownloader downloader = new CloudDownloader(context, dao);
//
//            var pending = dao.getPendingDownloads();
//            if (pending.isEmpty()) {
//                Timber.d("No pending downloads");
//                return;
//            }
//
//            Timber.i("Found %d files to download", pending.size());
//
//            for (FileDownloadRecord record : pending) {
//                File targetFile = new File(downloadDir, record.fileName);
//
//                if (targetFile.exists()) {
//                    record.status = "completed";
//                    dao.update(record);
//                    Timber.d("Already exists: %s", record.fileName);
//                    continue;
//                }
//
//                Timber.i("DOWNLOADING: %s", record.fileName);
//                downloader.downloadFile(record, targetFile);
//            }
//
//        } catch (Exception e) {
//            Timber.e(e, "Error downloading pending files");
//        }
//    }
//
//    private static void retryFailedDownloads(Context context) {
//        try {
//            AppDatabase db = AppDatabase.getInstance(context);
//            FileDownloadDao dao = db.fileDownloadDao();
//            CloudDownloader downloader = new CloudDownloader(context, dao);
//
//            long now = System.currentTimeMillis();
//            long thirtyMinAgo = now - (30 * 60 * 1000);
//            var retryList = dao.getFailedDownloadsReadyForRetry(thirtyMinAgo);
//
//            if (retryList.isEmpty()) {
//                var allFailed = dao.getFailedDownloads();
//                if (!allFailed.isEmpty()) {
//                    long oldest = allFailed.get(0).timestamp;
//                    long wait = (oldest + 30 * 60 * 1000) - now;
//                    if (wait > 0) {
//                        long mins = wait / (60 * 1000);
//                        Timber.i("WAITING: %d failed, retry in %d min", allFailed.size(), mins);
//                    }
//                } else {
//                    Timber.d("No failed downloads");
//                }
//                return;
//            }
//
//            Timber.i("RETRYING %d files (30+ min old)", retryList.size());
//
//            File downloadDir = new File(DOWNLOAD_DIR);
//
//            for (FileDownloadRecord record : retryList) {
//                File targetFile = new File(downloadDir, record.fileName);
//
//                Timber.i("RETRY: %s (attempt %d) - %s", record.fileName, record.retryCount + 1, record.failureReason);
//                downloader.downloadFile(record, targetFile);
//
//                if ("completed".equals(record.status)) {
//                    Timber.i("RETRY SUCCESS: %s", record.fileName);
//                } else {
//                    Timber.w("RETRY FAILED: %s - next in 30 min", record.fileName);
//                }
//            }
//
//        } catch (Exception e) {
//            Timber.e(e, "Error in retry");
//        }
//    }
//}


























package com.example.database.service;

import android.content.Context;
import com.example.database.db.AppDatabase;
import com.example.database.db.FileDownloadDao;
import com.example.database.db.FileDownloadRecord;
import com.example.database.utils.NetworkUtils;
import timber.log.Timber;

import java.io.File;
import java.util.concurrent.Executors;

public class CloudDownloadManager {

    private static final String DOWNLOAD_DIR = "/data/local/tmp/cloud_download";

    public static void processDownloads(Context context) {
        Executors.newSingleThreadExecutor().execute(() -> {
            Timber.i("DOWNLOAD SERVICE: Starting scheduled check...");
            Timber.d("Scheduled every 15 minutes");

            if (!NetworkUtils.isWifiConnected(context)) {
                Timber.i("WAITING FOR WIFI: Download paused");
                return;
            }

            Timber.i("WIFI CONNECTED: Proceeding");

            try {
                fetchAvailableFiles(context);
                downloadPendingFiles(context);
                retryFailedDownloads(context);
                Timber.i("DOWNLOAD SERVICE COMPLETE");
            } catch (Exception e) {
                Timber.e(e, "DOWNLOAD SERVICE ERROR");
            }
        });
    }

    private static void fetchAvailableFiles(Context context) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            FileDownloadDao dao = db.fileDownloadDao();
            new CloudDownloader(context, dao).listS3Files();
        } catch (Exception e) {
            Timber.e(e, "Error fetching files");
        }
    }

    private static void downloadPendingFiles(Context context) {
        try {
            File downloadDir = new File(DOWNLOAD_DIR);
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
                downloadDir.setReadable(true, false);
                downloadDir.setWritable(true, false);
                Timber.i("CREATED: %s", downloadDir.getAbsolutePath());
            }

            AppDatabase db = AppDatabase.getInstance(context);
            FileDownloadDao dao = db.fileDownloadDao();
            CloudDownloader downloader = new CloudDownloader(context, dao);

            var pending = dao.getPendingDownloads();
            if (pending.isEmpty()) {
                Timber.d("No pending downloads");
                return;
            }

            Timber.i("Found %d files to download", pending.size());

            for (FileDownloadRecord record : pending) {
                File targetFile = new File(downloadDir, record.fileName);

                if (targetFile.exists()) {
                    record.status = "completed";
                    dao.update(record);
                    Timber.d("Already exists: %s", record.fileName);
                    continue;
                }

                Timber.i("DOWNLOADING: %s", record.fileName);
                downloader.downloadFile(record, targetFile);
            }

        } catch (Exception e) {
            Timber.e(e, "Error downloading pending files");
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
                        Timber.i("WAITING: %d failed, retry in %d min", allFailed.size(), mins);
                    }
                } else {
                    Timber.d("No failed downloads");
                }
                return;
            }

            Timber.i("RETRYING %d files (30+ min old)", retryList.size());

            File downloadDir = new File(DOWNLOAD_DIR);

            for (FileDownloadRecord record : retryList) {
                File targetFile = new File(downloadDir, record.fileName);

                Timber.i("RETRY: %s (attempt %d) - %s", record.fileName, record.retryCount + 1, record.failureReason);
                downloader.downloadFile(record, targetFile);

                if ("completed".equals(record.status)) {
                    Timber.i("RETRY SUCCESS: %s", record.fileName);
                } else {
                    Timber.w("RETRY FAILED: %s - next in 30 min", record.fileName);
                }
            }

        } catch (Exception e) {
            Timber.e(e, "Error in retry");
        }
    }
}