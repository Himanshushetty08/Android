package com.ultraviolette.s3service.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.ultraviolette.s3service.IS3Service;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Receives the raw MQTT JSON payload from ClusterDataBus via [IS3Service] AIDL, parses it,
 * then downloads or deletes vehicle documents under [DOCUMENTS_DIR].
 *
 * Supported commands:
 *  cmd 11 — vehicle documents: "license", "registration", "insurance"
 *           Each field is a presigned S3 URL or the sentinel "d" (delete local copy).
 *  cmd 17 — F77 vehicle image: "url" field → stored as "f77_image"
 *
 * Downloads are dispatched on a single-thread executor so Binder threads are never blocked.
 * A write-then-rename strategy ensures the destination file is never partially written.
 */
public class S3DocumentService extends Service {

    private static final String TAG = "S3DocumentService";
    private static final String DOCUMENTS_DIR = "/data/vendor/uv/documents";
    private static final String SENTINEL_DELETE = "d";

    private static final int CMD_DOCUMENTS = 11;
    private static final int CMD_F77_IMAGE  = 17;

    private static final String[] CMD11_KEYS = {"license", "registration", "insurance"};

    private ExecutorService executor;
    private OkHttpClient httpClient;

    private final IS3Service.Stub binder = new IS3Service.Stub() {
        @Override
        public void downloadDocument(String payload) throws RemoteException {
            if (payload == null || payload.isEmpty()) {
                Log.w(TAG, "Received null/empty payload — ignoring");
                return;
            }
            executor.execute(() -> handlePayload(payload));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        Log.i(TAG, "S3DocumentService created — ready for ClusterDataBus payloads");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "ClusterDataBus bound to S3DocumentService");
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "ClusterDataBus unbound from S3DocumentService");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        Log.i(TAG, "S3DocumentService destroyed");
        super.onDestroy();
    }

    // --- Payload dispatch ---

    private void handlePayload(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            int cmd = json.getInt("cmd");

            switch (cmd) {
                case CMD_DOCUMENTS:
                    handleCmd11(json);
                    break;
                case CMD_F77_IMAGE:
                    handleCmd17(json);
                    break;
                default:
                    Log.w(TAG, "Unknown cmd=" + cmd + " — ignoring");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse payload: " + payload, e);
        }
    }

    /** cmd 11: license / registration / insurance — each may be a presigned URL or "d". */
    private void handleCmd11(JSONObject json) throws Exception {
        for (String key : CMD11_KEYS) {
            if (!json.has(key)) {
                Log.w(TAG, "cmd 11 missing field: " + key);
                continue;
            }
            String value = json.getString(key);
            handleDocument(key, value);
        }
    }

    /** cmd 17: F77 vehicle image — single "url" field, stored as "f77_image". */
    private void handleCmd17(JSONObject json) throws Exception {
        String url = json.getString("url");
        handleDocument("f77_image", url);
    }

    // --- Document handling ---

    private void handleDocument(String documentType, String url) {
        if (url == null || url.trim().isEmpty()) {
            Log.w(TAG, "Empty URL for " + documentType + " — skipping");
            return;
        }
        if (SENTINEL_DELETE.equals(url)) {
            deleteDocument(documentType);
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            downloadAndStore(documentType, url, new File(DOCUMENTS_DIR, documentType));
        } else {
            Log.w(TAG, "Unrecognised value for " + documentType + ": \"" + url + "\" — skipping");
        }
    }

    private void deleteDocument(String documentType) {
        // The stored file may have an extension (e.g. license.pdf) that wasn't known at delete
        // time. Find all files whose base name matches documentType and delete them all.
        File dir = new File(DOCUMENTS_DIR);
        File[] matches = dir.listFiles(f -> {
            String name = f.getName();
            return name.equals(documentType) || name.startsWith(documentType + ".");
        });

        if (matches == null || matches.length == 0) {
            Log.d(TAG, "Delete requested but no local file for: " + documentType);
            return;
        }
        for (File f : matches) {
            if (f.delete()) {
                Log.i(TAG, "Deleted local document: " + f.getName());
            } else {
                Log.e(TAG, "Failed to delete: " + f.getAbsolutePath());
            }
        }
    }

    private void downloadAndStore(String documentType, String url, File dest) {
        File dir = dest.getParentFile();
        if (dir != null && !dir.exists()) {
            if (!dir.mkdirs()) {
                Log.e(TAG, "Cannot create documents dir: " + dir.getAbsolutePath()
                        + " — SELinux/permission denied?");
                return;
            }
            dir.setReadable(true, false);
            dir.setWritable(true, false);
        }

        // Delete any previously stored file for this documentType (any extension)
        // before downloading, so stale files with a different extension never linger.
        if (dir != null) {
            File[] stale = dir.listFiles(f ->
                    !f.getName().endsWith(".tmp") &&
                    (f.getName().equals(documentType) || f.getName().startsWith(documentType + ".")));
            if (stale != null) {
                for (File f : stale) {
                    if (f.delete()) Log.i(TAG, "Removed stale file: " + f.getName());
                }
            }
        }

        Log.i(TAG, "Downloading document: " + documentType);
        File temp = new File(DOCUMENTS_DIR, documentType + ".tmp");

        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(TAG, "Download failed for " + documentType
                            + ": HTTP " + response.code());
                    return;
                }

                // Detect file type from Content-Type header and append extension to dest.
                String ext = extensionFromContentType(response.header("Content-Type"));
                if (!ext.isEmpty()) {
                    dest = new File(DOCUMENTS_DIR, documentType + ext);
                    temp = new File(DOCUMENTS_DIR, documentType + ext + ".tmp");
                    Log.d(TAG, "Content-Type resolved extension: " + ext
                            + " → " + dest.getName());
                }

                try (InputStream in = response.body().byteStream();
                     FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                    }
                    out.getFD().sync();
                }
            }

            if (dest.exists()) {
                dest.delete();
            }
            if (!temp.renameTo(dest)) {
                Log.e(TAG, "Failed to rename temp → dest for: " + documentType);
                temp.delete();
                return;
            }

            dest.setReadable(true, false);
            Log.i(TAG, "Document stored: " + documentType + " → " + dest.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "Error downloading document: " + documentType, e);
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    /**
     * Maps a Content-Type header value to a file extension (including the dot).
     * Returns an empty string if the type is unknown or the header was absent,
     * so callers store the file without an extension rather than guessing.
     */
    private static String extensionFromContentType(String contentType) {
        if (contentType == null) return "";
        // Strip any parameters like "; charset=utf-8"
        String mime = contentType.split(";")[0].trim().toLowerCase();
        switch (mime) {
            case "image/jpeg":
            case "image/jpg":          return ".jpg";
            case "image/png":          return ".png";
            case "image/webp":         return ".webp";
            case "application/pdf":    return ".pdf";
            default:
                Log.w(TAG, "Unrecognised Content-Type: " + mime + " — storing without extension");
                return "";
        }
    }
}