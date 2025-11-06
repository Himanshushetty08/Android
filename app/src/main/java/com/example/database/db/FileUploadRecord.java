


package com.example.database.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "FileUploadRecord")
public class FileUploadRecord {
    @PrimaryKey(autoGenerate = true)
    public int id;

    @NonNull
    public String fileName = "";
    public int srNo;
    public String category;
    @NonNull
    public String status = "";  // "pending", "success", "failed"
    public String failureReason;
    public long timestamp;

    // Default constructor (required by Room)
    public FileUploadRecord() {
    }

    // Constructor for creating new records
    public FileUploadRecord(@NonNull String fileName, int srNo, String category,
                            @NonNull String status, String failureReason, long timestamp) {
        this.fileName = fileName;
        this.srNo = srNo;
        this.category = category;
        this.status = status;
        this.failureReason = failureReason;
        this.timestamp = timestamp;
    }
}
