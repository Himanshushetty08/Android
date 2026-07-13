package com.ultraviolette.s3service.db;

import androidx.room.*;
import java.util.List;

@Dao
public interface FileUploadDao {

    @Insert
    long insert(FileUploadRecord record);

    @Update
    void update(FileUploadRecord record);
    @Delete
    void delete(FileUploadRecord record);

    @Query("SELECT * FROM FileUploadRecord WHERE fileName = :fileName LIMIT 1")
    FileUploadRecord getRecordByFileName(String fileName);

    @Query("SELECT * FROM FileUploadRecord WHERE id = :id LIMIT 1")
    FileUploadRecord getRecordById(int id);

    @Query("SELECT * FROM FileUploadRecord WHERE status = 'pending' OR status = 'failed'")
    List<FileUploadRecord> getPendingOrFailedRecords();

    @Query("SELECT * FROM FileUploadRecord WHERE status = 'failed' AND timestamp < :timestamp")
    List<FileUploadRecord> getFilesReadyForRetry(long timestamp);

    @Query("SELECT * FROM FileUploadRecord ORDER BY timestamp DESC")
    List<FileUploadRecord> getAllRecords();

    @Query("SELECT COUNT(*) FROM FileUploadRecord")
    int getCount();

    @Query("SELECT COALESCE(MAX(srNo), 0) FROM FileUploadRecord")
    int getHighestSrNo();

    // ✅ ADD THIS METHOD
    @Query("SELECT * FROM FileUploadRecord WHERE status = 'success'")
    List<FileUploadRecord> getSuccessfulRecords();

    @Query("UPDATE FileUploadRecord SET status = :status, failureReason = :reason, timestamp = :ts WHERE fileName = :fileName")
    void updateStatusByFileName(String fileName, String status, String reason, long ts);


}
