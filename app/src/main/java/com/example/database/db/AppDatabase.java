






package com.example.database.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {FileUploadRecord.class, FileDownloadRecord.class},
        version = 3,  // ✅ UPDATED: Increment version for new table
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    private static final String DB_NAME = "fileupload_db";
    private static volatile AppDatabase instance;

    public abstract FileUploadDao fileUploadDao();
    public abstract FileDownloadDao fileDownloadDao();  // ✅ ADD: Download DAO

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, DB_NAME)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}





