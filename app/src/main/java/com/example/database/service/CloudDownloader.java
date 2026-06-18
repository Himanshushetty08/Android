
package com.example.database.service;

import android.content.Context;
import android.util.Log;

import com.example.database.db.AppDatabase;
import com.example.database.db.FileUploadDao;
import com.example.database.db.FileUploadRecord;
import com.example.database.db.FileDownloadDao;
import com.example.database.db.FileDownloadRecord;
import com.example.database.utils.DeviceSession;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.*;

public class CloudDownloader {

    private static final String TAG = "CloudDownloader";
    private static final String REG_TAG = "RegistrationConfig";
    private static final String LAMBDA_URL = "https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3-dev";
    private static final String REGISTRATION_URL = "https://vac-apis.ultraviolette.com/dev/get-registration-details";
    private static final String CONFIG_PATH = "/data/vendor/uv_fota/fota/config.json";

    private final Context context;
    private final FileDownloadDao dao;
    private final OkHttpClient httpClient;

    public CloudDownloader(Context context, FileDownloadDao dao) {
        this.context = context;
        this.dao = dao;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        Log.d(TAG, "Created download HTTP client (5 min read timeout)");
    }

    public void listS3Files() {
        try {
            Log.d(TAG, "Discovering uploaded files for download...");
            AppDatabase db = AppDatabase.getInstance(context);
            FileUploadDao uploadDao = db.fileUploadDao();
            List<FileUploadRecord> successfulUploads = uploadDao.getSuccessfulRecords();

            if (successfulUploads.isEmpty()) {
                Log.i(TAG, "No successful uploads found - nothing to download");
                return;
            }

            Log.i(TAG, "Found " + successfulUploads.size() + " successfully uploaded files");
            int newDownloads = 0;

            for (FileUploadRecord upload : successfulUploads) {
                // BLOCK ALL fota/ FILES — THEY ARE TRIGGER-ONLY
                if (upload.fileName.startsWith("fota/")) {
                    Log.i(TAG, "FOTA PROTECTION: SKIPPED (trigger-only): " + upload.fileName);
                    continue;
                }

                if (addUploadForDownload(upload)) {
                    newDownloads++;
                }
            }

            Log.i(TAG, "Added " + newDownloads + " new files to download queue");

        } catch (Exception e) {
            Log.e(TAG, "Error discovering uploaded files for download", e);
        }
    }

    private boolean addUploadForDownload(FileUploadRecord upload) {
        try {
            // DOUBLE SAFETY — NEVER ADD fota/ even if somehow slipped through
            if (upload.fileName.startsWith("fota/")) {
                Log.w(TAG, "FOTA PROTECTION: BLOCKED ADDING TO QUEUE: " + upload.fileName);
                return false;
            }

            FileDownloadRecord existing = dao.getRecordByFileName(upload.fileName);
            if (existing == null) {
                FileDownloadRecord record = new FileDownloadRecord(
                        upload.fileName,
                        getFileSize(upload.fileName),
                        "pending",
                        null,
                        System.currentTimeMillis(),
                        0
                );
                dao.insert(record);
                Log.d(TAG, "Added to download queue: " + upload.fileName);
                return true;
            } else {
                Log.d(TAG, "File already in download queue: " + upload.fileName + " (status: " + existing.status + ")");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adding upload to download queue: " + upload.fileName, e);
            return false;
        }
    }

    private long getFileSize(String fileName) {
        try {
            File uploadQueueDir = new File(context.getFilesDir(), "upload_queue");
            File uploadFile = new File(uploadQueueDir, fileName);
            if (uploadFile.exists()) {
                long size = uploadFile.length();
                Log.d(TAG, "Got file size from upload queue: " + fileName + " = " + size + " bytes");
                return size;
            }

            File vendorFile = new File("/data/vendor/udp_socket", fileName);
            if (vendorFile.exists() && vendorFile.canRead()) {
                long size = vendorFile.length();
                Log.d(TAG, "Got file size from vendor directory: " + fileName + " = " + size + " bytes");
                return size;
            }

            if (fileName.contains("boot_session")) {
                return 10 * 1024 * 1024;
            } else {
                return 1024 * 1024;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not determine file size for: " + fileName + ", using 1MB estimate");
            return 1024 * 1024;
        }
    }

    // NORMAL BACKGROUND DOWNLOAD (fota/ blocked)
    public String downloadFile(FileDownloadRecord record, File targetFile) {
        return downloadFileInternal(record, targetFile, false);
    }

    // SPECIAL METHOD — ONLY FOR USER/SERVER TRIGGERED FOTA



    public String downloadFileFotaDirect(FileDownloadRecord record, File targetFile) {
        Log.w(TAG, "FOTA USER-TRIGGERED → BYPASSING ALL BACKGROUND PROTECTIONS");

        // CRITICAL FIX: Refresh record from DB to ensure we have the correct ID
        FileDownloadRecord freshRecord = dao.getRecordByFileName(record.fileName);
        if (freshRecord == null) {
            Log.e(TAG, "CRITICAL: Record not found in DB for: " + record.fileName);
            return "RECORD_NOT_FOUND";
        }

        Log.w(TAG, "FRESH RECORD FROM DB → ID: " + freshRecord.id + " | Status: " + freshRecord.status);
        return downloadFileInternal(freshRecord, targetFile, true);
    }

    private String downloadFileInternal(FileDownloadRecord record, File targetFile, boolean isFotaTrigger) {
        try {
            Log.w(TAG, "=== DOWNLOAD START ===");
            Log.w(TAG, "Record ID: " + record.id);
            Log.w(TAG, "Record Status: " + record.status);
            Log.w(TAG, "Record FileName: " + record.fileName);

            long expectedSize = record.fileSize;

            // BULLETPROOF RESUME — DISK IS THE SOURCE OF TRUTH
            long resumeFrom = 0;

            if (record.fileName.startsWith("fota/")) {
                File fotaDir = new File("/data/vendor/uv_fota/fota");
                File finalFile = new File(fotaDir, "fota.tar");

                File partialFile = null;
                File[] partialCandidates = fotaDir.listFiles(path ->
                        path.isFile() &&
                                !path.getName().equals("fota.tar") &&
                                path.getName().endsWith(".tar")
                );

                if (partialCandidates != null && partialCandidates.length > 0) {
                    partialFile = partialCandidates[0];
                }

                if (finalFile.exists() && finalFile.length() > 0) {
                    targetFile = finalFile;
                    resumeFrom = finalFile.length();
                    Log.w(TAG, "FOTA RESUME: Using final renamed file → fota.tar | Resume = " + resumeFrom);
                } else if (partialFile != null && partialFile.exists()) {
                    targetFile = partialFile;
                    resumeFrom = partialFile.length();
                    Log.w(TAG, "FOTA RESUME: Using partial file → " + partialFile.getName() +
                            " | Resume = " + resumeFrom);
                }
            }

            // Final fallback — always trust the file on disk
            if (targetFile.exists()) {
                resumeFrom = targetFile.length();
            }

            // FORCE DB and memory to match reality
            if (resumeFrom > record.downloadedBytes || record.downloadedBytes == 0) {
                record.downloadedBytes = resumeFrom;
                record.fileSize = Math.max(record.fileSize, resumeFrom);
                Log.w(TAG, "BEFORE UPDATE #1 → Record ID: " + record.id + " | Status: " + record.status);
                dao.update(record);
                Log.w(TAG, "AFTER UPDATE #1 → Synced downloadedBytes to " + formatSize(resumeFrom));
            }

            Log.i(TAG, String.format("STARTING DOWNLOAD: %s | Resume: %s | Total: %s",
                    record.fileName,
                    formatSize(record.downloadedBytes),
                    expectedSize > 0 ? formatSize(expectedSize) : "unknown"));

            Log.d(TAG, "Download attempt #" + (record.retryCount + 1));

            String presignedUrl = getPresignedDownloadUrl(record.fileName, isFotaTrigger);
            boolean success = downloadFromS3WithResume(presignedUrl, targetFile, expectedSize, record);

            Log.w(TAG, "=== DOWNLOAD RESULT ===");
            Log.w(TAG, "Success: " + success);
            Log.w(TAG, "Record ID: " + record.id);
            Log.w(TAG, "Record Status (before update): " + record.status);

            if (success) {
                long actualSize = targetFile.length();
                record.status = "completed";
                record.failureReason = null;
                record.fileSize = actualSize;
                record.downloadedBytes = actualSize;
                record.timestamp = System.currentTimeMillis();

                Log.w(TAG, "BEFORE FINAL UPDATE → Record ID: " + record.id + " | Setting status to: completed");
                dao.update(record);
                Log.w(TAG, "AFTER FINAL UPDATE → dao.update() called successfully");

                // VERIFY IT STUCK
                FileDownloadRecord verification = dao.getRecordByFileName(record.fileName);
                Log.w(TAG, "VERIFICATION FROM DB → Status: " + (verification != null ? verification.status : "NULL"));

                targetFile.setReadable(true, false);
                targetFile.setWritable(true, false);
                if (isFotaTrigger) targetFile.setExecutable(true, false);

                Log.i(TAG, String.format("DOWNLOAD SUCCESS: %s (%s)", record.fileName, formatSize(actualSize)));
                Log.i(TAG, "EXTERNAL SERVICE CAN ACCESS: " + targetFile.getAbsolutePath());

                return null;
            } else {
                String reason = getFailureReason(record.failureReason);
                updateRecordFailure(record, reason);
                return reason;
            }
        } catch (Exception e) {
            String reason = "UNKNOWN_ERROR: " + e.getMessage();
            updateRecordFailure(record, reason);
            Log.w(TAG, "DOWNLOAD FAILED: " + e.getMessage());
            return reason;
        }
    }
//    public String downloadFileFotaDirect(FileDownloadRecord record, File targetFile) {
//        Log.w(TAG, "FOTA USER-TRIGGERED → BYPASSING ALL BACKGROUND PROTECTIONS");
//        return downloadFileInternal(record, targetFile, true);
//    }
//
//    private String downloadFileInternal(FileDownloadRecord record, File targetFile, boolean isFotaTrigger) {
//        try {
//            long expectedSize = record.fileSize;
//
//
//            // BULLETPROOF RESUME — DISK IS THE SOURCE OF TRUTH
//            long resumeFrom = 0;
//
//            if (record.fileName.startsWith("fota/")) {
//                File fotaDir = new File("/data/vendor/uv_fota/fota");
//                File finalFile = new File(fotaDir, "fota.tar");
//
//                File partialFile = null;
//                File[] partialCandidates = fotaDir.listFiles(path ->
//                        path.isFile() &&
//                                !path.getName().equals("fota.tar") &&
//                                path.getName().endsWith(".tar")
//                );
//
//                if (partialCandidates != null && partialCandidates.length > 0) {
//                    partialFile = partialCandidates[0];
//                }
//
//                if (finalFile.exists() && finalFile.length() > 0) {
//                    targetFile = finalFile;
//                    resumeFrom = finalFile.length();
//                    Log.w(TAG, "FOTA RESUME: Using final renamed file → fota.tar | Resume = " + resumeFrom);
//                } else if (partialFile != null && partialFile.exists()) {
//                    targetFile = partialFile;
//                    resumeFrom = partialFile.length();
//                    Log.w(TAG, "FOTA RESUME: Using partial file → " + partialFile.getName() +
//                            " | Resume = " + resumeFrom);
//                }
//            }
//
//            // Final fallback — always trust the file on disk
//            if (targetFile.exists()) {
//                resumeFrom = targetFile.length();
//            }
//
//            // FORCE DB and memory to match reality
//            if (resumeFrom > record.downloadedBytes || record.downloadedBytes == 0) {
//                record.downloadedBytes = resumeFrom;
//                record.fileSize = Math.max(record.fileSize, resumeFrom);
//                dao.update(record);
//                Log.w(TAG, "FORCED RESUME SYNC → DB now says " + formatSize(resumeFrom) + " downloaded");
//            }
//
//            Log.i(TAG, String.format("STARTING DOWNLOAD: %s | Resume: %s | Total: %s",
//                    record.fileName,
//                    formatSize(record.downloadedBytes),
//                    expectedSize > 0 ? formatSize(expectedSize) : "unknown"));
//
//            Log.d(TAG, "Download attempt #" + (record.retryCount + 1));
//
//            String presignedUrl = getPresignedDownloadUrl(record.fileName, isFotaTrigger);
//            boolean success = downloadFromS3WithResume(presignedUrl, targetFile, expectedSize, record);
//
//            if (success) {
//                long actualSize = targetFile.length();
//                record.status = "completed";
//                record.failureReason = null;
//                record.fileSize = actualSize;
//                record.downloadedBytes = actualSize;
//                record.timestamp = System.currentTimeMillis();
//                dao.update(record);
//
//                targetFile.setReadable(true, false);
//                targetFile.setWritable(true, false);
//                if (isFotaTrigger) targetFile.setExecutable(true, false);
//
//                Log.i(TAG, String.format("DOWNLOAD SUCCESS: %s (%s)", record.fileName, formatSize(actualSize)));
//                Log.i(TAG, "EXTERNAL SERVICE CAN ACCESS: " + targetFile.getAbsolutePath());
//
//                // CRITICAL: Only for FOTA — delete upload record so 15-min service never sees it again
////                if (isFotaTrigger && record.fileName.startsWith("fota/")) {
////                    try {
////                        AppDatabase.getInstance(context).fileUploadDao().deleteByFileName(record.fileName);
////                        Log.w(TAG, "FOTA CLEANUP → Upload record deleted from DB (no more auto-trigger)");
////                    } catch (Exception e) {
////                        Log.w(TAG, "Failed to delete upload record", e);
////                    }
////                }
//
//                return null;
//            } else {
//                String reason = getFailureReason(record.failureReason);
//                updateRecordFailure(record, reason);
//                return reason;
//            }
//        } catch (Exception e) {
//            String reason = "UNKNOWN_ERROR: " + e.getMessage();
//            updateRecordFailure(record, reason);
//            Log.w(TAG, "DOWNLOAD FAILED: " + e.getMessage());
//            return reason;
//        }
//    }

    private String getFailureReason(String rawReason) {
        if (rawReason == null) return "UNKNOWN_ERROR";
        if (rawReason.contains("HTTP 404") || rawReason.contains("NoSuchKey")) return "FILE_NOT_FOUND";
        if (rawReason.contains("HTTP 403") || rawReason.contains("AccessDenied")) return "FILE_NOT_FOUND";
        if (rawReason.contains("timeout") || rawReason.contains("network") || rawReason.contains("connect")) return "NETWORK_ERROR";
        if (rawReason.contains("HTTP 500") || rawReason.contains("InternalError")) return "SERVER_ERROR";
        return "UNKNOWN_ERROR";
    }

    // UPDATED: Now with bypass for FOTA
    private String getPresignedDownloadUrl(String fileName, boolean bypassStrip) throws Exception {
        // String cleanFileName = bypassStrip ? fileName : fileName.replace("fota/", "").replace("test/", "");
//
////        String cleanFileName = fileName.replace("fota/", "").replace("test/", "");

        String cleanFileName = bypassStrip
                ? fileName.replace("fota/", "").replace("test/", "")
                : fileName.replace("fota/", "").replace("test/", "");

        String jsonBody = "{\n" +
                " \"operation\": \"DL_FILE_EX\",\n" +
                " \"fileName\": \"" + cleanFileName + "\",\n" +
                " \"fileType\": \"application/octet-stream\"\n" +
                "}";

        Log.d(TAG, "Download Request JSON: " + jsonBody);

        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request request = new Request.Builder().url(LAMBDA_URL).post(body).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("Lambda failed: HTTP " + response.code());
            }

            String responseBody = response.body().string();
            JSONObject json = new JSONObject(responseBody);
            String url = json.getString("url");

            if (!url.startsWith("https://")) {
                throw new RuntimeException("Invalid URL");
            }

            return url;
        }
    }

    // Keep old method for backward compatibility
    private String getPresignedDownloadUrl(String fileName) throws Exception {
        return getPresignedDownloadUrl(fileName, false);
    }

    // FULL RESUME + REAL % + TOTAL SIZE (NO LAMBDA CHANGE)
    private boolean downloadFromS3WithResume(String presignedUrl, File targetFile, long expectedSize, FileDownloadRecord record) {
        try {
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                if (created) {
                    parentDir.setReadable(true, false);
                    parentDir.setWritable(true, false);
                    Log.i(TAG, "CREATED DIR: " + parentDir.getAbsolutePath());
                } else {
                    Log.e(TAG, "FAILED TO CREATE DIR: " + parentDir.getAbsolutePath());
                    return false;
                }
            }

            long realTotalSize = expectedSize;
            if (realTotalSize <= 0 || realTotalSize == 1024*1024) {
                Request head = new Request.Builder().url(presignedUrl).head().build();
                try (Response r = httpClient.newCall(head).execute()) {
                    if (r.isSuccessful()) {
                        String len = r.header("Content-Length");
                        if (len != null && !len.isEmpty()) {
                            realTotalSize = Long.parseLong(len);
                            record.fileSize = realTotalSize;
                            dao.update(record);
                            Log.w(TAG, "REAL TOTAL SIZE FROM S3 HEAD: " + formatSize(realTotalSize));
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "HEAD request failed, will show ??% until first chunk", e);
                }
            }

            // CRITICAL FIX: Use actual file size, not possibly stale DB value
            long startFrom = targetFile.length();
            record.downloadedBytes = startFrom;

            Request.Builder requestBuilder = new Request.Builder().url(presignedUrl).get();
            if (startFrom > 0) {
                requestBuilder.header("Range", "bytes=" + startFrom + "-");
                Log.w(TAG, "RESUMING FROM " + formatSize(startFrom) + " → Range: bytes=" + startFrom + "-");
            }

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {

                if (response.code() == 416) {
                    Log.w(TAG, "HTTP 416 → FILE ALREADY 100% DOWNLOADED (size matches)");
                    record.status = "completed";
                    record.downloadedBytes = record.fileSize;
                    dao.update(record);
                    return true;
                }

                if (!response.isSuccessful() && response.code() != 206) {
                    String error = response.body() != null ? response.body().string() : "No body";
                    Log.e(TAG, "S3 failed: HTTP " + response.code() + " | " + error);
                    record.failureReason = "HTTP " + response.code() + " - " + error;
                    return false;
                }

                boolean isPartial = response.code() == 206;
                Log.i(TAG, "S3 → HTTP " + response.code() + (isPartial ? " (206 Partial → RESUMING)" : ""));

                try (InputStream in = response.body().byteStream();
                     RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {

                    if (startFrom > 0) raf.seek(startFrom);

                    byte[] buffer = new byte[8192];
                    long total = startFrom;
                    int read;
                    long lastLog = System.currentTimeMillis();

                    while ((read = in.read(buffer)) != -1) {
                        raf.write(buffer, 0, read);
                        total += read;
                        record.downloadedBytes = total;

                        if (System.currentTimeMillis() - lastLog > 1000) {
                            String progressText;
                            if (realTotalSize > 0) {
                                double pct = total * 100.0 / realTotalSize;
                                progressText = String.format("ProgggsssLeeLLineww: %.1f%% (%s / %s)", pct,
                                        formatSize(total), formatSize(realTotalSize));
                            } else {
                                progressText = "ProgreggsssssLLLeeeLiiiooneww: ??% (" + formatSize(total) + " downloaded)";
                            }
                            Log.d(TAG, progressText);
                            lastLog = System.currentTimeMillis();
                        }
                    }

                    // ========== CRITICAL FIX: FORCE DISK SYNC ==========
                    raf.getFD().sync();  // Force OS to flush all buffers to disk
                    Log.w(TAG, "DOWNLOAD COMPLETE + SYNCED: " + formatSize(total));
                    // ===================================================

                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Download exception", e);
            record.failureReason = "EXCEPTION: " + e.getMessage();
            return false;
        }
    }

    private void updateRecordFailure(FileDownloadRecord record, String reason) {
        record.status = "failed";
        record.failureReason = reason;
        record.timestamp = System.currentTimeMillis();
        record.retryCount++;
        dao.update(record);

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String now = sdf.format(new Date(record.timestamp));
        String retry = sdf.format(new Date(System.currentTimeMillis() + 30 * 60 * 1000));

        Log.w(TAG, "FAILED: " + record.fileName + " at " + now);
        Log.w(TAG, "Reason: " + reason);
        Log.i(TAG, "RETRY IN 30 MIN: " + retry);
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        } else {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
    }

    public void fetchRegistrationConfig() {
        String imei = DeviceSession.getImei();
        if (imei == null || imei.isEmpty()) {
            Log.w(REG_TAG, "IMEI not set, skipping registration config fetch");
            return;
        }

        try {
            Log.i(REG_TAG, "Fetching registration config for IMEI: " + imei);
            String jsonBody = "{\"imei\": \"" + imei + "\"}";
            RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(REGISTRATION_URL)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(REG_TAG, "Fetch failed: HTTP " + response.code());
                    return;
                }

                String responseBody = response.body().string();
                Log.d(REG_TAG, "Response received, saving to " + CONFIG_PATH);

                File configFile = new File(CONFIG_PATH);
                File configDir = configFile.getParentFile();
                if (configDir != null && !configDir.exists()) {
                    boolean created = configDir.mkdirs();
                    if (!created) {
                        Log.e(REG_TAG, "Failed to create config dir: " + configDir.getAbsolutePath() + " (SELinux/permission denied?)");
                        return;
                    }
                    configDir.setReadable(true, false);
                    configDir.setWritable(true, false);
                }

                try (FileOutputStream fos = new FileOutputStream(configFile)) {
                    fos.write(responseBody.getBytes("UTF-8"));
                    fos.flush();
                }

                configFile.setReadable(true, false);
                Log.i(REG_TAG, "config.json saved successfully: " + CONFIG_PATH);
            }
        } catch (Exception e) {
            Log.e(REG_TAG, "Error fetching registration config", e);
        }
    }
}