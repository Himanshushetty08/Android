//package com.ultraviolette.s3service.db;
//
//import androidx.room.*;
//import java.util.List;
//
//@Dao
//public interface FileDownloadDao {
//
//    @Insert
//    long insert(FileDownloadRecord record);
//
//    @Update
//    void update(FileDownloadRecord record);
//
//    @Query("SELECT * FROM FileDownloadRecord WHERE fileName = :fileName LIMIT 1")
//    FileDownloadRecord getRecordByFileName(String fileName);
//
//    @Query("SELECT * FROM FileDownloadRecord WHERE status = 'pending'")
//    List<FileDownloadRecord> getPendingDownloads();
//
//    @Query("SELECT * FROM FileDownloadRecord WHERE status = 'failed'")
//    List<FileDownloadRecord> getFailedDownloads();
//
//    @Query("SELECT * FROM FileDownloadRecord WHERE status = 'failed' AND timestamp < :thirtyMinutesAgo")
//    List<FileDownloadRecord> getFailedDownloadsReadyForRetry(long thirtyMinutesAgo);
//
//    @Query("SELECT * FROM FileDownloadRecord ORDER BY timestamp DESC")
//    List<FileDownloadRecord> getAllDownloadRecords();
//
//    @Query("SELECT COUNT(*) FROM FileDownloadRecord")
//    int getDownloadCount();
//}


package com.ultraviolette.s3service.db;

import androidx.room.*;
import java.util.List;

@Dao
public interface FileDownloadDao {

    @Insert
    long insert(FileDownloadRecord record);

    @Update
    void update(FileDownloadRecord record);

    @Query("SELECT * FROM FileDownloadRecord WHERE fileName = :fileName LIMIT 1")
    FileDownloadRecord getRecordByFileName(String fileName);

    @Query("SELECT * FROM FileDownloadRecord WHERE status = 'pending'")
    List<FileDownloadRecord> getPendingDownloads();

    @Query("SELECT * FROM FileDownloadRecord WHERE status = 'failed'")
    List<FileDownloadRecord> getFailedDownloads();

    @Query("SELECT * FROM FileDownloadRecord WHERE status = 'failed' AND timestamp < :thirtyMinutesAgo")
    List<FileDownloadRecord> getFailedDownloadsReadyForRetry(long thirtyMinutesAgo);

    @Query("SELECT * FROM FileDownloadRecord ORDER BY timestamp DESC")
    List<FileDownloadRecord> getAllDownloadRecords();

    @Query("SELECT COUNT(*) FROM FileDownloadRecord")
    int getDownloadCount();

    // ONLY THIS METHOD ADDED — REQUIRED FOR INSTANT OTA RESUME
    @Query("SELECT * FROM FileDownloadRecord WHERE fileName LIKE :prefix || '%'")
    List<FileDownloadRecord> getRecordsByPrefix(String prefix);
}