package ca.zesty.fleetreporter;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;
import android.support.annotation.NonNull;

@Entity(tableName = "balances")
public class BalanceEntity {
    @PrimaryKey @NonNull
    @ColumnInfo(name = "subscriber_id") public String subscriberId;
    @ColumnInfo(name = "amount") public long amount;
    @ColumnInfo(name = "expiration_millis") public long expirationMillis;

    public BalanceEntity(
        String subscriberId, long amount, long expirationMillis) {
        this.subscriberId = subscriberId;
        this.amount = amount;
        this.expirationMillis = expirationMillis;
    }
}
