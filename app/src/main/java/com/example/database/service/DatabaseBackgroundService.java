

package com.example.database.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.database.db.AppDatabase;
import com.example.database.db.FileDownloadDao;
import com.example.database.db.FileDownloadRecord;
import com.ultraviolette.fotaservice.IFotaS3Callback;
import com.ultraviolette.fotaservice.IFotaS3Events;

import java.io.File;
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
    }

    private final IFotaS3Events.Stub binder = new IFotaS3Events.Stub() {
        @Override
        public void fotaDownloadRequest(String s3Key) throws RemoteException {
            Log.d(TAG, "Received FOTA download request for: " + s3Key);
            Log.i(TAG, "FOTA REQUEST: s3Key = " + s3Key);

            String fileName = "fota/fota.tar";
            triggerOtaDownloadDirectly(fileName);
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

    private void triggerOtaDownloadDirectly(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            Log.e(TAG, "triggerOtaDownloadDirectly: Invalid filename");
            notifyOtaAvailable("FAILED");
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            Log.i(TAG, "DIRECT OTA DOWNLOAD STARTED: " + fileName);
            updateNotification("Downloading OTA: " + fileName);

            File downloadDir = new File("/data/vendor/uv_fota/fota");
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

                File targetFile = new File(downloadDir, fileName);
                FileDownloadRecord record = dao.getRecordByFileName(fileName);

                if (record != null && "completed".equals(record.status) && targetFile.exists()) {
                    Log.i(TAG, "OTA ALREADY DOWNLOADED: " + fileName + " (" + targetFile.length() + " bytes)");
                    updateNotification("OTA ready: " + fileName);
                    notifyOtaReady(fileName);
                    notifyOtaAvailable("SUCCESS");  // Already exists → SUCCESS
                    return;
                }

                if (record == null) {
                    record = new FileDownloadRecord(fileName, 0, "pending", null, System.currentTimeMillis(), 0);
                    long id = dao.insert(record);
                    Log.d(TAG, "New download record: id=" + id + ", file=" + fileName);
                } else {
                    record.status = "pending";
                    record.failureReason = null;
                    record.timestamp = System.currentTimeMillis();
                    dao.update(record);
                    Log.d(TAG, "Record reset to pending: " + fileName);
                }

                Log.i(TAG, "DOWNLOADING OTA: " + fileName + " to " + targetFile.getAbsolutePath());
                String failureReason = downloader.downloadFile(record, targetFile);

                if ("completed".equals(record.status) && targetFile.exists()) {
                    Log.i(TAG, "OTA DOWNLOAD SUCCESS: " + fileName + " (" + targetFile.length() + " bytes)");
                    updateNotification("OTA downloaded: " + fileName);
                    notifyOtaReady(fileName);
                    notifyOtaAvailable("SUCCESS");  // ONLY ON SUCCESS
                } else {
                    String reason = failureReason != null ? failureReason : "Unknown";
                    Log.w(TAG, "OTA DOWNLOAD FAILED: " + fileName + " | Reason: " + reason);
                    updateNotification("OTA failed: " + fileName + " (" + reason + ")");

                    if ("FILE_NOT_FOUND".equals(reason)) {
                        Log.e(TAG, "FILE NOT FOUND ON S3 — Check Lambda key: " + fileName);
                    } else if ("NETWORK_ERROR".equals(reason)) {
                        Log.e(TAG, "NETWORK ISSUE — Retry in 30 min");
                    }
                    notifyOtaAvailable("FAILED");  // ON ANY FAILURE
                }

            } catch (Exception e) {
                Log.e(TAG, "FATAL: OTA download crashed for " + fileName, e);
                updateNotification("OTA error: " + e.getMessage());
                notifyOtaAvailable("FAILED");
            }
        });
    }

    private void notifyOtaReady(String fileName) {
        File file = new File("/data/vendor/uv_fota/fota", fileName);
        if (file.exists()) {
            Log.i(TAG, "OTA FILE READY: " + file.getAbsolutePath() + " (" + file.length() + " bytes)");

            Intent intent = new Intent("com.ultraviolette.OTA_READY");
            intent.putExtra("file_path", file.getAbsolutePath());
            intent.putExtra("file_name", fileName);
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
        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                performUploadDownload();
                workerHandler.postDelayed(this, INTERVAL_MS);
            }
        });
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
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "AIDL client binding to DatabaseBackgroundService");
        return binder;
    }
}