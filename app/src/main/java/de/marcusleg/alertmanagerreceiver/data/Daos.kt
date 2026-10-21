package de.marcusleg.alertmanagerreceiver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import de.marcusleg.alertmanagerreceiver.model.AlertStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {
    @Query(
        """
        SELECT * FROM alerts
        ORDER BY CASE status WHEN 'FIRING' THEN 0 ELSE 1 END,
                 CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,
                 statusChangedAt DESC
        """
    )
    fun observeAll(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE fingerprint = :fingerprint")
    fun observe(fingerprint: String): Flow<AlertEntity?>

    @Query("SELECT * FROM alerts WHERE fingerprint = :fingerprint")
    suspend fun get(fingerprint: String): AlertEntity?

    @Query("SELECT COUNT(*) FROM alerts WHERE status = :status")
    fun observeCount(status: AlertStatus): Flow<Int>

    @Query("SELECT * FROM alerts WHERE status = 'FIRING'")
    suspend fun firing(): List<AlertEntity>

    @Upsert
    suspend fun upsert(alert: AlertEntity)

    @Query("UPDATE alerts SET acknowledged = :acknowledged WHERE fingerprint = :fingerprint")
    suspend fun setAcknowledged(fingerprint: String, acknowledged: Boolean)

    @Query("UPDATE alerts SET silenceId = :silenceId, silencedUntil = :until, acknowledged = 1 WHERE fingerprint = :fingerprint")
    suspend fun setSilenced(fingerprint: String, silenceId: String, until: Long)

    @Query("DELETE FROM alerts WHERE fingerprint = :fingerprint")
    suspend fun delete(fingerprint: String)

    @Query("DELETE FROM alerts WHERE status = 'RESOLVED'")
    suspend fun deleteResolved(): Int

    @Query("DELETE FROM alerts WHERE status = 'RESOLVED' AND statusChangedAt < :before")
    suspend fun deleteResolvedBefore(before: Long): Int

    @Query("DELETE FROM alerts")
    suspend fun deleteAll()
}

@Dao
interface DeliveryDao {
    @Insert
    suspend fun insert(delivery: DeliveryEntity): Long

    @Query("SELECT * FROM deliveries ORDER BY receivedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<DeliveryEntity>>

    @Query("SELECT * FROM deliveries WHERE httpStatus BETWEEN 200 AND 299 ORDER BY receivedAt DESC LIMIT 1")
    fun observeLastSuccess(): Flow<DeliveryEntity?>

    @Query("DELETE FROM deliveries WHERE id NOT IN (SELECT id FROM deliveries ORDER BY receivedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 200)

    @Query("DELETE FROM deliveries")
    suspend fun deleteAll()
}
