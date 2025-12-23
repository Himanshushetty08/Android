package com.example.database.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.content.Context;
import com.ultraviolette.uvmqtt.IMqttFileHandler;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.database.db.AppDatabase;
import com.example.database.db.FileDownloadDao;
import com.example.database.db.FileDownloadRecord;
import android.app.fota.aidl.IFotaS3Callback;
import android.app.fota.aidl.IFotaS3Events;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class DatabaseBackgroundService extends Service {

    private static final String TAG = "DatabaseBackgroundService";
    private Handler mainHandler;
    private HandlerThread workerThread;
    private Handler workerHandler;

    private static final int INTERVAL_MINUTES = 15;
    private static final long INTERVAL_MS = INTERVAL_MINUTES * 60 * 1000;

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "bike_data_service";
    private static final String CHANNEL_NAME = "Bike Data Service";

    private final List<IFotaS3Callback> callbacks = new ArrayList<>();
    private NotificationManager notificationManager;

    // FOTA Service binding
    private int fotaBindAttempts = 0;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long RETRY_INTERVAL_MS = 30_000; // retry every 30 seconds

    // INSTANT RESUME ON WIFI RETURN
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "DatabaseBackgroundService created");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("Service initializing..."));

        mainHandler = new Handler(Looper.getMainLooper());
        workerThread = new HandlerThread("DatabaseWorkerThread");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        scheduleRepeatingTask();

        // INSTANT OTA RESUME WHEN WIFI COMES BACK (NO 15 MIN WAIT)
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.w(TAG, "WIFI CONNECTED → INSTANT OTA RESUME CHECKkkkk");
                workerHandler.post(() -> {
                    AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                    FileDownloadDao dao = db.fileDownloadDao();
                    List<FileDownloadRecord> otaList = dao.getRecordsByPrefix("fota/");

                    for (FileDownloadRecord r : otaList) {
                        if (!"completed".equals(r.status)) {
                            Log.w(TAG, "AUTO-RESUME DETECTED → USING FULL RENAME FLOW FOR: " + r.fileName);
                            triggerOtaDownloadDirectly(r.fileName);
                            return; // Only process first incomplete FOTA
                        }
                    }
                });
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } else {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            connectivityManager.registerNetworkCallback(request, networkCallback);
        }
    }



    private final IFotaS3Events.Stub binder = new IFotaS3Events.Stub() {
        @Override
        public void fotaDownloadRequest(String fileName) throws RemoteException {
            Log.d(TAG, "Received FOTA download request for: " + fileName);
            Log.i(TAG, "FOTA REQUEST: s3Key = " + fileName);

            String s3fileName = fileName.startsWith("fota/") ? fileName : "fota/" + fileName;
            triggerOtaDownloadDirectly(s3fileName);
            // REMOVED: notifyOtaAvailable("SUCCESS") — now called only after success
        }

        @Override
        public void registerCallback(IFotaS3Callback callback) throws RemoteException {
            synchronized (callbacks) {
                if (!callbacks.contains(callback)) {
                    callbacks.add(callback);
                }
            }
            Log.d(TAG, "Callback registered");
        }

        @Override
        public void unregisterCallback(IFotaS3Callback callback) throws RemoteException {
            synchronized (callbacks) {
                callbacks.remove(callback);
            }
            Log.d(TAG, "Callback unregistered");
        }
    };

    private String extractFileName(String s3Key) {
        if (s3Key == null || s3Key.trim().isEmpty()) {
            Log.w(TAG, "s3Key is null or empty, using fallback");
            return "ota_fallback.bin";
        }
        int slash = s3Key.lastIndexOf('/');
        String name = slash >= 0 ? s3Key.substring(slash + 1) : s3Key;
        Log.d(TAG, "extractFileName: " + s3Key + " to " + name);
        return name;
    }

//    private void triggerOtaDownloadDirectly(String fileName) {
//        if (fileName == null || fileName.trim().isEmpty()) {
//            Log.e(TAG, "triggerOtaDownloadDirectly: Invalid filename");
//            notifyOtaAvailable("FAILED");
//            return;
//        }
//
//        Executors.newSingleThreadExecutor().execute(() -> {
//            Log.i(TAG, "DIRECT OTA DOWNLOAD STARTED: " + fileName);
//            updateNotification("Preparing OTA download...");
//
//            File downloadDir = new File("/data/vendor/uv_fota/fota");
//            String realName = extractFileName(fileName);
//            File partialFile = new File(downloadDir, realName);   // during download
//            File finalFile = new File(downloadDir, "fota.tar");        // final name
//
//            try {
//                if (!downloadDir.exists()) {
//                    boolean created = downloadDir.mkdirs();
//                    downloadDir.setReadable(true, false);
//                    downloadDir.setWritable(true, false);
//                    Log.i(TAG, "Download dir created: " + downloadDir.getAbsolutePath() + " (created=" + created + ")");
//                }
//
//                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
//                FileDownloadDao dao = db.fileDownloadDao();
//                CloudDownloader downloader = new CloudDownloader(getApplicationContext(), dao);
//
//                FileDownloadRecord record = dao.getRecordByFileName(fileName);
//
//                // AUTO DELETE OLD fota.tar + partial → ALWAYS FRESH DOWNLOAD
//                if (finalFile.exists()) {
//                    boolean deleted = finalFile.delete();
//                    Log.w(TAG, "AUTO DELETED OLD fota.tar (success=" + deleted + ") → STARTING FRESH DOWNLOAD");
//                }
//                if (partialFile.exists()) {
//                    boolean deleted = partialFile.delete();
//                    Log.w(TAG, "DELETED OLD PARTIAL: " + realName + " (success=" + deleted + ")");
//                }
//
//                long existing = 0;
//
//                if (record == null) {
//                    record = new FileDownloadRecord(fileName, existing, "pending", null, System.currentTimeMillis(), 0);
//                    dao.insert(record);
//                } else {
//                    record.status = "pending";
//                    record.downloadedBytes = existing;
//                    record.timestamp = System.currentTimeMillis();
//                    dao.update(record);
//                }
//
//                Log.i(TAG, "DOWNLOADING FRESH OTA: " + fileName + " → saving as " + realName);
//                String failureReason = downloader.downloadFileFotaDirect(record, partialFile);
//
//                if ("completed".equals(record.status)) {
//                    if (finalFile.exists()) finalFile.delete();
//                    boolean renamed = partialFile.renameTo(finalFile);
//                    Log.w(TAG, "RENAMED " + realName + " → fota.tar (success=" + renamed + ")");
//
//                    Log.i(TAG, "OTA DOWNLOAD SUCCESS → FINAL FILE: fota.tar (" + finalFile.length() + " bytes)");
//                    updateNotification("OTA downloaded: fota.tar");
//                    notifyOtaReady();
//                    notifyOtaAvailable("SUCCESS");
//                } else {
//                    String reason = failureReason != null ? failureReason : "Unknown";
//                    Log.w(TAG, "OTA DOWNLOAD FAILED: " + fileName + " | Reason: " + reason);
//                    updateNotification("OTA failed: " + realName + " (" + reason + ")");
//                    notifyOtaAvailable("FAILED");
//                }
//
//            } catch (Exception e) {
//                Log.e(TAG, "FATAL: OTA download crashed for " + fileName, e);
//                updateNotification("OTA error: " + e.getMessage());
//                notifyOtaAvailable("FAILED");
//            }
//        });
//    }

    private void triggerOtaDownloadDirectly(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            Log.e(TAG, "triggerOtaDownloadDirectly: Invalid filename");
            notifyOtaAvailable("FAILED");
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            Log.i(TAG, "DIRECT OTA DOWNLOAD STARTED: " + fileName);
            updateNotification("Preparing OTA download...");

            File downloadDir = new File("/data/vendor/uv_fota/fota");
            String realName = extractFileName(fileName);
            File partialFile = new File(downloadDir, realName);
            File finalFile = new File(downloadDir, "fota.tar");

            try {
                if (!downloadDir.exists()) {
                    boolean created = downloadDir.mkdirs();
                    downloadDir.setReadable(true, false);
                    downloadDir.setWritable(true, false);
                    Log.i(TAG, "Download dir created: " + downloadDir.getAbsolutePath() + " (created=" + created + ")");
                }

                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                FileDownloadDao dao = db.fileDownloadDao();
                CloudDownloader downloader = new CloudDownloader(getApplicationContext(), dao);

                FileDownloadRecord record = dao.getRecordByFileName(fileName);

                // USER TRIGGERED = ALWAYS FRESH DOWNLOAD — NO MATTER WHAT
                if (finalFile.exists()) {
                    boolean deleted = finalFile.delete();
                    Log.w(TAG, "USER TRIGGER → DELETED EXISTING fota.tar (success=" + deleted + ")");
                }
                if (partialFile.exists()) {
                    boolean deleted = partialFile.delete();
                    Log.w(TAG, "USER TRIGGER → DELETED EXISTING PARTIAL: " + realName + " (success=" + deleted + ")");
                }

                // SMART RESUME: Only delete files if we're starting from 0 bytes
//                if (record == null || record.downloadedBytes == 0) {
//                    if (finalFile.exists()) finalFile.delete();
//                    if (partialFile.exists()) partialFile.delete();
//                    Log.w(TAG, "STARTING FRESH OTA DOWNLOAD — CLEARED OLD FILES");
//                } else {
//                    Log.w(TAG, "RESUMING OTA — KEEPING " + formatBytes(record.downloadedBytes) + " ALREADY DOWNLOADED");
//                }

                // REMOVED conflicting resume logic — CloudDownloader now handles it perfectly
                if (record == null) {
                    record = new FileDownloadRecord(fileName, 0, "pending", null, System.currentTimeMillis(), 0);
                    dao.insert(record);
                } else {
//                    record.status = "pending";
//                    dao.update(record);
                    record.timestamp = System.currentTimeMillis();
                    dao.update(record);
                }

                Log.i(TAG, "DOWNLOADING FRESH OTA: " + fileName + " → saving as " + realName);
                String failureReason = downloader.downloadFileFotaDirect(record, partialFile);

                record = dao.getRecordByFileName(fileName);  // ← REFRESH IMMEDIATELY
                if (record == null) {
                    Log.e(TAG, "CRITICAL: Record disappeared from DB!");
                    notifyOtaAvailable("FAILED");
                    return;
                }

                Log.w(TAG, "=== POST-DOWNLOAD CHECK ===");
                Log.w(TAG, "failureReason: " + failureReason);
                Log.w(TAG, "record.status: " + record.status);
                Log.w(TAG, "partialFile.exists(): " + partialFile.exists());
                Log.w(TAG, "partialFile.path: " + partialFile.getAbsolutePath());
                if (partialFile.exists()) {
                    Log.w(TAG, "partialFile.length(): " + partialFile.length());
                }

//                record = dao.getRecordByFileName(fileName);
//                if (record == null) {
//                    Log.e(TAG, "CRITICAL: Record disappeared from DB!");
//                    notifyOtaAvailable("FAILED");
//                    return;
//                }
//                Log.w(TAG, "DB refreshed - status: " + record.status);

//                System.gc();
//                try { Thread.sleep(300); } catch (InterruptedException e) { }

                if (failureReason == null && "completed".equals(record.status) && partialFile.exists()) {
                    Log.w(TAG, "ENTERING RENAME BLOCK");

                    if (finalFile.exists()) finalFile.delete();

                    boolean renamed = partialFile.renameTo(finalFile);
                    Log.w(TAG, "renameTo result: " + renamed);

                    if (renamed) {
                        Log.w(TAG, "RENAMED " + realName + " → fota.tar (success=true)");
                    } else {
                        Log.w(TAG, "renameTo FAILED → USING MANUAL COPY (bypasses SELinux)");
                        try (InputStream in = new FileInputStream(partialFile);
                             OutputStream out = new FileOutputStream(finalFile)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = in.read(buffer)) > 0) {
                                out.write(buffer, 0, len);
                            }
                            out.flush();
                        }
                        partialFile.delete();
                        Log.w(TAG, "MANUAL COPY+DELETE DONE → fota.tar CREATED SUCCESSFULLY");
                    }

                    if (finalFile.exists() && finalFile.length() > 0) {
                        Log.i(TAG, "OTA DOWNLOAD SUCCESS → FINAL FILE: fota.tar (" + finalFile.length() + " bytes)");
                        updateNotification("OTA downloaded: fota.tar");
                        notifyOtaReady();
                        notifyOtaAvailable("SUCCESS");
                    } else {
                        Log.e(TAG, "RENAME/COPY SUCCEEDED BUT FILE MISSING!");
                        notifyOtaAvailable("FAILED");
                    }
                } else {
                    Log.e(TAG, "RENAME CONDITION FAILED - CHECK LOGS ABOVE");
                    String reason = failureReason != null ? failureReason : "Status: " + record.status;
                    Log.w(TAG, "OTA DOWNLOAD FAILED: " + fileName + " | Reason: " + reason);
                    updateNotification("OTA failed: " + realName + " (" + reason + ")");
                    notifyOtaAvailable("FAILED");
                }

            } catch (Exception e) {
                Log.e(TAG, "FATAL: OTA download crashed for " + fileName, e);
                updateNotification("OTA error: " + e.getMessage());
                notifyOtaAvailable("FAILED");
            }
        });
    }


    private void notifyOtaReady() {
        File file = new File("/data/vendor/uv_fota/fota/fota.tar");
        if (file.exists()) {
            Log.i(TAG, "OTA FILE READY: " + file.getAbsolutePath() + " (" + file.length() + " bytes)");

            Intent intent = new Intent("com.ultraviolette.OTA_READY");
            intent.putExtra("file_path", file.getAbsolutePath());
            intent.putExtra("file_name", "fota.tar");
            sendBroadcast(intent);
            Log.d(TAG, "Broadcast: com.ultraviolette.OTA_READY | " + file.getAbsolutePath());
        }
    }

    private void notifyOtaAvailable(String payloadUrl) {
        synchronized (callbacks) {
            for (IFotaS3Callback cb : callbacks) {
                try {
                    cb.onOtaAvailable(payloadUrl);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error notifying callback", e);
                }
            }
        }
    }

    private void scheduleRepeatingTask() {
        Log.i(TAG, "UploadManager: Waiting 30 seconds before first upload/download run");
        workerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "UploadManager: 30 seconds elapsed, starting upload/download.");
                performUploadDownload();
                Log.i(TAG, "UploadManager: Scheduling next run in 15 minutes");
                workerHandler.postDelayed(this, INTERVAL_MS);
            }
        }, 20_000);
    }

    private void performUploadDownload() {
        try {
            Log.i(TAG, "Performing upload/download cycle...");
            updateNotification("Uploading bike data...");
            UploadManager.processFiles(getApplicationContext());

            updateNotification("Downloading files...");
            CloudDownloadManager.processDownloads(getApplicationContext());

            updateNotification("Cycle complete - Next in 15 min");
        } catch (Exception e) {
            Log.e(TAG, "Error in upload/download cycle", e);
            updateNotification("Error - retrying in 15 minutes");
        }
    }

    private void processOtaData(String version, String payloadUrl) {
        Log.i(TAG, "Processing OTA v" + version + " (" + payloadUrl + ")");
        updateNotification("Processing OTA v" + version + "...");
        try {
            Log.i(TAG, "Starting OTA download...");
            updateNotification("OTA v" + version + " processed");
        } catch (Exception e) {
            Log.e(TAG, "OTA processing failed", e);
            updateNotification("OTA failed");
        }
    }

    private void createNotificationChannel() {
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Bike data upload/download with OTA binding");
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String message) {
        String title = "Bike Data Service";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String message) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, createNotification(message));
        }
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        } else {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand triggered");
        updateNotification("Running periodic tasks...");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "DatabaseBackgroundService destroyed");
        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
        if (workerThread != null) workerThread.quitSafely();
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "AIDlllTTluu client binding to DatabaseBackgroundService");
        return binder;
    }
}