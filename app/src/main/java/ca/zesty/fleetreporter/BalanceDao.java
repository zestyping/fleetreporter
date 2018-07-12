package ca.zesty.fleetreporter;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.Update;

@Dao
public interface BalanceDao {
    @Query("select * from balances where subscriber_id = :subscriberId")
    BalanceEntity get(String subscriberId);

    @Update
    int update(BalanceEntity balance);

    @Insert
    void insert(BalanceEntity balance);
}
