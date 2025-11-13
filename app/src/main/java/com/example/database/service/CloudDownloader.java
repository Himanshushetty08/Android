















package com.example.database.service;

import android.content.Context;
import android.util.Log;

import com.example.database.db.AppDatabase;
import com.example.database.db.FileUploadDao;
import com.example.database.db.FileUploadRecord;
import com.example.database.db.FileDownloadDao;
import com.example.database.db.FileDownloadRecord;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.*;

public class CloudDownloader {

    private static final String TAG = "CloudDownloader";
    private static final String LAMBDA_URL = "https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3-dev";

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

    // Returns failure reason (null = success)
    public String downloadFile(FileDownloadRecord record, File targetFile) {
        try {
            long fileSize = record.fileSize;
            Log.i(TAG, String.format("STARTING DOWNLOAD: %s (%.2f MB)", record.fileName, fileSize / (1024.0 * 1024.0)));
            Log.d(TAG, "Download attempt #" + (record.retryCount + 1));

            String presignedUrl = getPresignedDownloadUrl(record.fileName);
            boolean success = downloadFromS3(presignedUrl, targetFile, fileSize, record); // Pass record

            if (success) {
                long actualSize = targetFile.length();
                record.status = "completed";
                record.failureReason = null;
                record.fileSize = actualSize;
                record.timestamp = System.currentTimeMillis();
                dao.update(record);

                targetFile.setReadable(true, false);
                targetFile.setWritable(true, false);

                Log.i(TAG, String.format("DOWNLOAD SUCCESS: %s (%.2f MB)", record.fileName, actualSize / (1024.0 * 1024.0)));
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

    private String getFailureReason(String rawReason) {
        if (rawReason == null) return "UNKNOWN_ERROR";
        if (rawReason.contains("HTTP 404") || rawReason.contains("NoSuchKey")) return "FILE_NOT_FOUND";
        if (rawReason.contains("HTTP 403") || rawReason.contains("AccessDenied")) return "FILE_NOT_FOUND";
        if (rawReason.contains("timeout") || rawReason.contains("network") || rawReason.contains("connect")) return "NETWORK_ERROR";
        if (rawReason.contains("HTTP 500") || rawReason.contains("InternalError")) return "SERVER_ERROR";
        return "UNKNOWN_ERROR";
    }

    private String getPresignedDownloadUrl(String fileName) throws Exception {
        String cleanFileName = fileName.replace("fota/", "").replace("test/", "");

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

    // Now accepts record to set failureReason
    private boolean downloadFromS3(String presignedUrl, File targetFile, long expectedSize, FileDownloadRecord record) {
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

            Request request = new Request.Builder().url(presignedUrl).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String error = response.body() != null ? response.body().string() : "No body";
                    Log.e(TAG, "S3 failed: HTTP " + response.code() + " | " + error);

                    if (response.code() == 404) {
                        record.failureReason = "HTTP 404 - FILE_NOT_FOUND";
                    } else if (response.code() == 403) {
                        record.failureReason = "HTTP 403 - ACCESS_DENIED";
                    } else if (response.code() >= 500) {
                        record.failureReason = "HTTP " + response.code() + " - SERVER_ERROR";
                    } else {
                        record.failureReason = "HTTP " + response.code() + " - " + error;
                    }
                    return false;
                }

                try (InputStream in = response.body().byteStream();
                     FileOutputStream out = new FileOutputStream(targetFile)) {

                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int read;
                    long lastLog = System.currentTimeMillis();

                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        total += read;

                        if (System.currentTimeMillis() - lastLog > 1000) {
                            if (expectedSize > 0) {
                                double pct = (total * 100.0) / expectedSize;
                                Log.d(TAG, String.format("Progress: %.1f%% (%.2f/%.2f MB)", pct,
                                        total / (1024.0 * 1024.0), expectedSize / (1024.0 * 1024.0)));
                            } else {
                                Log.d(TAG, String.format("Downloaded: %.2f MB", total / (1024.0 * 1024.0)));
                            }
                            lastLog = System.currentTimeMillis();
                        }
                    }

                    out.flush();
                    out.getFD().sync();
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
}