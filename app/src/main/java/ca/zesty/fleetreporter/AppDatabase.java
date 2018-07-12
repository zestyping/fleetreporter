package ca.zesty.fleetreporter;

import android.arch.persistence.room.Database;
import android.arch.persistence.room.Room;
import android.arch.persistence.room.RoomDatabase;
import android.content.Context;

@Database(entities = {BalanceEntity.class}, exportSchema = false, version = 1)
public abstract class AppDatabase extends RoomDatabase {
    public abstract BalanceDao getBalanceDao();

    public static AppDatabase getDatabase(Context context) {
        return Room.databaseBuilder(context, AppDatabase.class, "database")
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .build();
    }
}
