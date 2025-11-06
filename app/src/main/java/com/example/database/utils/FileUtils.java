package com.example.database.utils;

import android.content.Context;
import android.os.StatFs;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import timber.log.Timber;

public class FileUtils {
    public static int deleteOldFilesIfLowStorage(Context context, String directoryPath, long thresholdBytes) {
        File folder = new File(directoryPath);
        if (!folder.exists() || !folder.isDirectory()) {
            Timber.e("Directory %s does not exist or not a directory.", directoryPath);
            return 0;
        }

        long freeBytes = getFreeStorageBytes(folder);
        if (freeBytes > thresholdBytes) {
            Timber.d("Sufficient storage, no need to delete files.");
            return 0;
        }

        File[] files = folder.listFiles();
        if (files == null || files.length == 0) {
            Timber.d("No files for cleanup.");
            return 0;
        }

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int deletedCount = 0;
        for (File file : files) {
            if (file.isFile() && file.delete()) {
                Timber.i("Cleanup: Old file deleted: %s", file.getName());
                deletedCount++;
                freeBytes = getFreeStorageBytes(folder);
                if (freeBytes > thresholdBytes) break;
            }
        }
        Timber.i("Cleanup: Total %d old files deleted.", deletedCount);
        return deletedCount;
    }

    public static long getFreeStorageBytes(File folder) {
        StatFs stat = new StatFs(folder.getAbsolutePath());
        long availableBlocks = stat.getAvailableBlocksLong();
        long blockSize = stat.getBlockSizeLong();
        return availableBlocks * blockSize;
    }
}
