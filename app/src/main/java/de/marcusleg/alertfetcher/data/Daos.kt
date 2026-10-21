package de.marcusleg.alertfetcher.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import de.marcusleg.alertfetcher.model.AlertStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {
    @Query(
        """
        SELECT * FROM alerts
        ORDER BY CASE status WHEN 'FIRING' THEN 0 WHEN 'SUPPRESSED' THEN 1 ELSE 2 END,
                 CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,
                 statusChangedAt DESC
        """
    )
    fun observeAll(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE fingerprint = :fingerprint")
    fun observe(fingerprint: String): Flow<AlertEntity?>

    @Query("SELECT * FROM alerts WHERE fingerprint = :fingerprint")
    suspend fun get(fingerprint: String): AlertEntity?

    @Query("SELECT * FROM alerts WHERE status != 'RESOLVED'")
    suspend fun active(): List<AlertEntity>

    @Query("SELECT COUNT(*) FROM alerts WHERE status = :status")
    fun observeCount(status: AlertStatus): Flow<Int>

    @Query("SELECT * FROM alerts WHERE status = 'FIRING'")
    suspend fun firing(): List<AlertEntity>

    @Upsert
    suspend fun upsert(alert: AlertEntity)

    @Query("UPDATE alerts SET acknowledged = :acknowledged WHERE fingerprint = :fingerprint")
    suspend fun setAcknowledged(fingerprint: String, acknowledged: Boolean)

    @Query("UPDATE alerts SET silencedUntil = :until, acknowledged = 1 WHERE fingerprint = :fingerprint")
    suspend fun setSilenced(fingerprint: String, until: Long)

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
interface CheckDao {
    @Insert
    suspend fun insert(check: CheckEntity): Long

    @Query("SELECT * FROM checks ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<CheckEntity>>

    @Query("DELETE FROM checks WHERE id NOT IN (SELECT id FROM checks ORDER BY startedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 200)

    @Query("DELETE FROM checks")
    suspend fun deleteAll()
}
