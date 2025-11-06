package com.example.database.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "FileDownloadRecord")
public class FileDownloadRecord {
    @PrimaryKey(autoGenerate = true)
    public int id;

    @NonNull
    public String fileName;

    public long fileSize;

    @NonNull
    public String status; // "pending", "completed", "failed"

    public String failureReason;

    public long timestamp;

    public int retryCount;

    public FileDownloadRecord(@NonNull String fileName, long fileSize, @NonNull String status,
                              String failureReason, long timestamp, int retryCount) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.status = status;
        this.failureReason = failureReason;
        this.timestamp = timestamp;
        this.retryCount = retryCount;
    }
}
