package com.example.database.service;

import android.content.Context;
import com.example.database.db.AppDatabase;
import com.example.database.db.FileUploadDao;
import com.example.database.db.FileUploadRecord;
import com.example.database.utils.NetworkUtils;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import timber.log.Timber;

public class UploadManager {

    private static final String VENDOR_DIR = "/data/vendor/udp_socket";
    private static final String QUEUE_DIR = "upload_queue";

    private static final String LAMBDA_URL = "https://bpfsuu5xvj.execute-api.ap-south-1.amazonaws.com/default/s3uploadurlcreatorv1-dev-getPreSignedURLToPutToS3-dev";

    public static void processFiles(Context context) {
        Executors.newSingleThreadExecutor().execute(() -> {
            Timber.i("UploadManager: Starting upload process...");

            if (!NetworkUtils.isWifiConnected(context)) {
                Timber.i("Wi-Fi not connected, upload aborted");
                return;
            }

            try {
                copyReadableFiles(context);
                uploadFromQueue(context);
                retryFailedUploads(context);
                Timber.i("Upload process completed");

            } catch (Exception e) {
                Timber.e(e, "Error in upload process");
            }
        });
    }

    private static void copyReadableFiles(Context context) {
        File vendorDir = new File(VENDOR_DIR);
        File queueDir = new File(context.getFilesDir(), QUEUE_DIR);

        if (!queueDir.exists()) queueDir.mkdirs();

        try {
            File[] vendorFiles = vendorDir.listFiles();
            if (vendorFiles == null) {
                Timber.w("Cannot access vendor directory");
                return;
            }

            AppDatabase db = AppDatabase.getInstance(context);
            FileUploadDao dao = db.fileUploadDao();

            int copied = 0, skipped = 0, errors = 0;

            for (File vendorFile : vendorFiles) {
                if (!vendorFile.isFile() || !vendorFile.canRead()) continue;

                File queueFile = new File(queueDir, vendorFile.getName());
                if (queueFile.exists()) { skipped++; continue; }

                FileUploadRecord existing = dao.getRecordByFileName(vendorFile.getName());
                if (existing != null) { skipped++; continue; }

                try {
                    copyFile(vendorFile, queueFile);
                    copied++;
                    Timber.d("Copied to queue: %s", vendorFile.getName());
                } catch (IOException e) {
                    errors++;
                    Timber.e(e, "Copy failed: %s", vendorFile.getName());
                }
            }

            Timber.i("File copy summary - Copied: %d, Skipped: %d, Errors: %d", copied, skipped, errors);

        } catch (Exception e) {
            Timber.e(e, "Error copying files from vendor directory");
        }
    }

    private static void uploadFromQueue(Context context) {
        File queueDir = new File(context.getFilesDir(), QUEUE_DIR);
        File[] queueFiles = queueDir.listFiles();

        if (queueFiles == null || queueFiles.length == 0) {
            Timber.d("Upload queue is empty");
            return;
        }

        AppDatabase db = AppDatabase.getInstance(context);
        FileUploadDao dao = db.fileUploadDao();

        int uploaded = 0, failed = 0;

        for (File file : queueFiles) {
            if (!file.isFile()) continue;

            FileUploadRecord record = dao.getRecordByFileName(file.getName());
            if (record == null) {
                int nextSrNo = dao.getHighestSrNo() + 1;
                record = new FileUploadRecord(file.getName(), nextSrNo,
                        determineCategory(file.getName()), "pending", null, System.currentTimeMillis());
                dao.insert(record);
                Timber.d("Created upload record: %s (srNo: %d)", file.getName(), nextSrNo);
            }

            if ("success".equals(record.status)) {
                file.delete();
                continue;
            }

            Timber.i("Uploading: %s", file.getName());
            uploadFile(context, file, record, dao);

            if ("success".equals(record.status)) {
                uploaded++;
                file.delete();
                Timber.i("Upload success: %s", file.getName());
            } else {
                failed++;
                Timber.w("Upload failed: %s", file.getName());
            }
        }

        Timber.i("Upload summary - Success: %d, Failed: %d", uploaded, failed);
    }

    private static void retryFailedUploads(Context context) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            FileUploadDao dao = db.fileUploadDao();

            long currentTime = System.currentTimeMillis();
            long thirtyMinutesAgo = currentTime - (30 * 60 * 1000);
            var retryUploads = dao.getFilesReadyForRetry(thirtyMinutesAgo);

            if (retryUploads.isEmpty()) {
                Timber.d("No uploads ready for retry (30+ minutes old)");
                return;
            }

            Timber.i("RETRY: Processing %d failed uploads", retryUploads.size());

            File queueDir = new File(context.getFilesDir(), QUEUE_DIR);

            for (FileUploadRecord record : retryUploads) {
                File file = new File(queueDir, record.fileName);
                if (!file.exists()) {
                    Timber.w("File missing, skipping: %s", record.fileName);
                    continue;
                }

                Timber.i("RETRY: %s - Original failure: %s", record.fileName, record.failureReason);
                uploadFile(context, file, record, dao);

                if ("success".equals(record.status)) {
                    Timber.i("RETRY SUCCESS: %s", record.fileName);
                    file.delete();
                } else {
                    Timber.w("RETRY FAILED: %s - will retry in 30 min", record.fileName);
                }
            }

        } catch (Exception e) {
            Timber.e(e, "Error processing retry uploads");
        }
    }

    private static void uploadFile(Context context, File file, FileUploadRecord record, FileUploadDao dao) {
        if (!file.exists() || file.length() == 0) {
            Timber.w("File is empty or missing: %s", file.getName());
            updateRecordFailure(record, dao, "File empty or missing");
            return;
        }

        long fileSize = file.length();
        Timber.d("File: %s, size: %.2f MB", file.getName(), fileSize / (1024.0 * 1024.0));

        try {
            String presignedUrl = getPresignedUrl(file.getName());
            boolean success = uploadToS3(file, presignedUrl, fileSize);

            if (success) {
                record.status = "success";
                record.failureReason = null;
                record.timestamp = System.currentTimeMillis();
                dao.update(record);
                Timber.i("Upload SUCCESS: %s", file.getName());
            } else {
                updateRecordFailure(record, dao, "S3 upload failed");
            }

        } catch (Exception e) {
            Timber.w("Upload failed for %s: %s", file.getName(), e.getMessage());
            updateRecordFailure(record, dao, e.getMessage());
        }
    }

    // FIXED: Upload to fota/ — S3 auto-creates folder
    private static String getPresignedUrl(String fileName) throws IOException {
        String s3Key = "fota/" + fileName;

        String jsonBody = "{\n" +
                "  \"operation\": \"UL_LOGS\",\n" +
                "  \"fileName\": \"" + s3Key + "\",\n" +
                "  \"fileType\": \"application/octet-stream\"\n" +
                "}";

        Timber.d("Request JSON: %s", jsonBody);

        OkHttpClient lambdaClient = createLambdaClient();
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request lambdaRequest = new Request.Builder()
                .url(LAMBDA_URL)
                .post(body)
                .build();

        Timber.d("Requesting pre-signed URL from: %s", LAMBDA_URL);

        try (Response lambdaResponse = lambdaClient.newCall(lambdaRequest).execute()) {
            if (!lambdaResponse.isSuccessful() || lambdaResponse.body() == null) {
                String errorMsg = "Failed to get pre-signed URL: " + lambdaResponse.code() + " " + lambdaResponse.message();
                Timber.e("Lambda Error: %s", errorMsg);
                throw new IOException(errorMsg);
            }

            String responseBody = lambdaResponse.body().string();
            Timber.d("Lambda Response: %s", responseBody);

            try {
                JSONObject jsonResponse = new JSONObject(responseBody);
                String presignedUrl = jsonResponse.getString("url");
                Timber.d("Extracted pre-signed URL: [%s]...", presignedUrl.substring(0, Math.min(100, presignedUrl.length())));
                return presignedUrl;
            } catch (Exception e) {
                Timber.e(e, "Failed to parse JSON response");
                throw new IOException("Failed to parse pre-signed URL: " + e.getMessage());
            }
        }
    }

    private static boolean uploadToS3(File file, String presignedUrl, long fileSize) throws IOException {
        Timber.d("Starting S3 upload: %s (%.2f MB)", file.getName(), fileSize / (1024.0 * 1024.0));

        OkHttpClient s3Client = createS3Client(fileSize);
        RequestBody fileBody = RequestBody.create(file, MediaType.parse("application/octet-stream"));

        Request s3Request = new Request.Builder()
                .url(presignedUrl)
                .put(fileBody)
                .build();

        long startTime = System.currentTimeMillis();
        try (Response s3Response = s3Client.newCall(s3Request).execute()) {
            long uploadTime = System.currentTimeMillis() - startTime;

            if (s3Response.isSuccessful()) {
                Timber.i("S3 upload completed in %.1f seconds (HTTP %d)",
                        uploadTime / 1000.0, s3Response.code());
                return true;
            } else {
                String errorBody = s3Response.body() != null ? s3Response.body().string() : "No response body";
                Timber.e("S3 upload failed: HTTP %d %s | Error: %s",
                        s3Response.code(), s3Response.message(), errorBody);
                return false;
            }
        }
    }

    private static void updateRecordFailure(FileUploadRecord record, FileUploadDao dao, String reason) {
        record.status = "failed";
        record.failureReason = reason;
        record.timestamp = System.currentTimeMillis();
        dao.update(record);
        Timber.d("Failure recorded: %s", reason);
        Timber.i("Will retry in 30 minutes");
    }

    private static String determineCategory(String fileName) {
        if (fileName.contains("boot_session") || fileName.contains("startup")) return "boot_logs";
        if (fileName.contains("error") || fileName.contains("crash")) return "error_logs";
        if (fileName.contains("sensor") || fileName.contains("telemetry")) return "sensor_data";
        return "general";
    }

    private static void copyFile(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) fos.write(buffer, 0, bytesRead);
            fos.flush();
        }
    }

    private static OkHttpClient createLambdaClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private static OkHttpClient createS3Client(long fileSizeBytes) {
        int writeTimeoutSec = fileSizeBytes > 5 * 1024 * 1024 ? 600 : 300;
        int readTimeoutSec = fileSizeBytes > 5 * 1024 * 1024 ? 600 : 300;
        int callTimeoutSec = fileSizeBytes > 5 * 1024 * 1024 ? 1200 : 600;

        return new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
                .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }
}