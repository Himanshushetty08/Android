//package com.example.database.service;
//
//import android.content.Context;
//import java.io.File;
//import java.io.FileInputStream;
//import java.io.FileOutputStream;
//import java.io.IOException;
//import java.nio.channels.FileChannel;
//import timber.log.Timber;
//
//public class FileQueueManager {
//
//    private static final String RETRY_QUEUE_DIR = "retry_queue";
//    private static final long RETRY_INTERVAL_MS = 30 * 60 * 1000; // 30 minutes
//
//    public static File getRetryQueueDir(Context context) {
//        File retryDir = new File(context.getFilesDir(), RETRY_QUEUE_DIR);
//        if (!retryDir.exists()) {
//            retryDir.mkdirs();
//            Timber.d("📁 Created retry queue directory: %s", retryDir.getAbsolutePath());
//        }
//        return retryDir;
//    }
//
//    /**
//     * Move failed upload file to retry queue
//     */
//    public static boolean moveToRetryQueue(Context context, File sourceFile) {
//        try {
//            File retryDir = getRetryQueueDir(context);
//            File destFile = new File(retryDir, sourceFile.getName());
//
//            // Only move if not already in retry queue
//            if (!destFile.exists()) {
//                boolean moved = copyFile(sourceFile, destFile);
//                if (moved) {
//                    // Delete original file after successful copy
//                    sourceFile.delete();
//                    Timber.i("📂 Moved to retry queue: %s", sourceFile.getName());
//                    return true;
//                }
//            } else {
//                Timber.d("📂 File already in retry queue: %s", sourceFile.getName());
//                return true;
//            }
//        } catch (Exception e) {
//            Timber.e(e, "❌ Failed to move file to retry queue: %s", sourceFile.getName());
//        }
//        return false;
//    }
//
//    /**
//     * Remove file from retry queue after successful upload
//     */
//    public static boolean removeFromRetryQueue(Context context, String fileName) {
//        try {
//            File retryDir = getRetryQueueDir(context);
//            File file = new File(retryDir, fileName);
//
//            if (file.exists()) {
//                boolean deleted = file.delete();
//                if (deleted) {
//                    Timber.i("🗑️ Removed from retry queue: %s", fileName);
//                }
//                return deleted;
//            }
//        } catch (Exception e) {
//            Timber.e(e, "❌ Failed to remove from retry queue: %s", fileName);
//        }
//        return false;
//    }
//
//    /**
//     * Calculate next retry time based on attempt count
//     */
//    public static long calculateNextRetryTime(int retryCount) {
//        // 30 minutes for first retry, then exponential backoff (max 4 hours)
//        long baseInterval = RETRY_INTERVAL_MS;
//        long maxInterval = 4 * 60 * 60 * 1000; // 4 hours
//
//        long nextInterval = Math.min(baseInterval * (retryCount + 1), maxInterval);
//        return System.currentTimeMillis() + nextInterval;
//    }
//
//    private static boolean copyFile(File source, File dest) {
//        FileInputStream fis = null;
//        FileOutputStream fos = null;
//        FileChannel sourceChannel = null;
//        FileChannel destChannel = null;
//
//        try {
//            fis = new FileInputStream(source);
//            fos = new FileOutputStream(dest);
//            sourceChannel = fis.getChannel();
//            destChannel = fos.getChannel();
//
//            destChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
//            return true;
//        } catch (IOException e) {
//            Timber.e(e, "❌ File copy failed");
//            return false;
//        } finally {
//            try {
//                if (sourceChannel != null) sourceChannel.close();
//                if (destChannel != null) destChannel.close();
//                if (fis != null) fis.close();
//                if (fos != null) fos.close();
//            } catch (IOException e) {
//                Timber.e(e, "❌ Error closing file streams");
//            }
//        }
//    }
//}



package com.example.database.service;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import timber.log.Timber;

public class FileQueueManager {

    private static final String RETRY_QUEUE_DIR = "retry_queue";
    private static final long RETRY_INTERVAL_MS = 30 * 60 * 1000; // 30 minutes - CONSTANT

    public static File getRetryQueueDir(Context context) {
        File retryDir = new File(context.getFilesDir(), RETRY_QUEUE_DIR);
        if (!retryDir.exists()) {
            retryDir.mkdirs();
            Timber.d("📁 Created retry queue directory: %s", retryDir.getAbsolutePath());
        }
        return retryDir;
    }

    /**
     * Move failed upload file to retry queue
     */
    public static boolean moveToRetryQueue(Context context, File sourceFile) {
        try {
            File retryDir = getRetryQueueDir(context);
            File destFile = new File(retryDir, sourceFile.getName());

            // Only move if not already in retry queue
            if (!destFile.exists()) {
                boolean moved = copyFile(sourceFile, destFile);
                if (moved) {
                    // Delete original file after successful copy
                    sourceFile.delete();
                    Timber.i("📂 Moved to retry queue: %s", sourceFile.getName());
                    return true;
                }
            } else {
                Timber.d("📂 File already in retry queue: %s", sourceFile.getName());
                return true;
            }
        } catch (Exception e) {
            Timber.e(e, "❌ Failed to move file to retry queue: %s", sourceFile.getName());
        }
        return false;
    }

    /**
     * Remove file from retry queue after successful upload
     */
    public static boolean removeFromRetryQueue(Context context, String fileName) {
        try {
            File retryDir = getRetryQueueDir(context);
            File file = new File(retryDir, fileName);

            if (file.exists()) {
                boolean deleted = file.delete();
                if (deleted) {
                    Timber.i("🗑️ Removed from retry queue: %s", fileName);
                }
                return deleted;
            }
        } catch (Exception e) {
            Timber.e(e, "❌ Failed to remove from retry queue: %s", fileName);
        }
        return false;
    }

    /**
     * ✅ CORRECTED: Always return constant 30-minute interval
     */
    public static long calculateNextRetryTime(int retryCount) {
        // CONSTANT 30-minute intervals for all retries (no exponential backoff)
        long nextRetryTime = System.currentTimeMillis() + RETRY_INTERVAL_MS;

        Timber.d("🔄 Next retry scheduled in exactly 30 minutes (attempt #%d)", retryCount + 1);
        return nextRetryTime;
    }

    private static boolean copyFile(File source, File dest) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        FileChannel sourceChannel = null;
        FileChannel destChannel = null;

        try {
            fis = new FileInputStream(source);
            fos = new FileOutputStream(dest);
            sourceChannel = fis.getChannel();
            destChannel = fos.getChannel();

            destChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
            return true;
        } catch (IOException e) {
            Timber.e(e, "❌ File copy failed");
            return false;
        } finally {
            try {
                if (sourceChannel != null) sourceChannel.close();
                if (destChannel != null) destChannel.close();
                if (fis != null) fis.close();
                if (fos != null) fos.close();
            } catch (IOException e) {
                Timber.e(e, "❌ Error closing file streams");
            }
        }
    }
}


