package com.familysafety.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "devices", primaryKeys = ["id"])
data class DeviceEntity(
    val id: String,
    val deviceName: String,
    val manufacturer: String?,
    val model: String?,
    val osVersion: String?,
    val appVersion: String?,
    val status: String,
    val lastSeenAt: String?,
    val ownerUserId: String,
    val ownerDisplayName: String,
    val pairingId: String?,
    val pairedAt: String?,
    val familyName: String?,
    val latestUsageDate: String?,
    val latestUsageForegroundMs: Long?,
    val activeScreenShareSessionId: String?,
)

/** Locally aggregated usage for this (child) device, before/after upload. */
@Entity(tableName = "local_app_usage", primaryKeys = ["date", "packageName"])
data class LocalAppUsageEntity(
    val date: String,
    val packageName: String,
    val appLabel: String?,
    val foregroundMs: Long,
    val launchCount: Int,
    val lastUsedAtMs: Long?,
)

@Entity(tableName = "local_day_summary", primaryKeys = ["date"])
data class LocalDaySummaryEntity(
    val date: String,
    val totalForegroundMs: Long,
    val firstActivityAtMs: Long?,
    val lastActivityAtMs: Long?,
    val collectedAtMs: Long,
    val syncedAtMs: Long?,
)

/** Tracks which alerts have already produced a local notification. */
@Entity(tableName = "notified_alerts", primaryKeys = ["alertId"])
data class NotifiedAlertEntity(val alertId: String, val notifiedAtMs: Long)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY ownerDisplayName COLLATE NOCASE")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("DELETE FROM devices") suspend fun clear()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<DeviceEntity>)
    @Query("DELETE FROM devices WHERE id = :id") suspend fun delete(id: String)

    @Transaction
    suspend fun replaceAll(items: List<DeviceEntity>) { clear(); insertAll(items) }
}

@Dao
interface LocalUsageDao {
    @Query("SELECT * FROM local_app_usage WHERE date = :date ORDER BY foregroundMs DESC")
    suspend fun appsFor(date: String): List<LocalAppUsageEntity>

    @Query("SELECT * FROM local_app_usage WHERE date = :date ORDER BY foregroundMs DESC")
    fun observeAppsFor(date: String): Flow<List<LocalAppUsageEntity>>

    @Query("SELECT * FROM local_day_summary WHERE date = :date") fun observeSummary(date: String): Flow<LocalDaySummaryEntity?>
    @Query("SELECT * FROM local_day_summary ORDER BY date DESC") suspend fun summaries(): List<LocalDaySummaryEntity>
    @Query("DELETE FROM local_app_usage WHERE date = :date") suspend fun deleteApps(date: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertApps(items: List<LocalAppUsageEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSummary(item: LocalDaySummaryEntity)
    @Query("UPDATE local_day_summary SET syncedAtMs = :at WHERE date IN (:dates)") suspend fun markSynced(dates: List<String>, at: Long)
    @Query("DELETE FROM local_app_usage WHERE date < :before") suspend fun pruneApps(before: String)
    @Query("DELETE FROM local_day_summary WHERE date < :before") suspend fun pruneSummaries(before: String)
    @Query("DELETE FROM local_app_usage") suspend fun clearApps()
    @Query("DELETE FROM local_day_summary") suspend fun clearSummaries()

    /** Replaces one day's snapshot atomically. */
    @Transaction
    suspend fun replaceDay(summary: LocalDaySummaryEntity, apps: List<LocalAppUsageEntity>) {
        deleteApps(summary.date)
        insertApps(apps)
        upsertSummary(summary)
    }
}

@Dao
interface NotifiedAlertDao {
    @Query("SELECT alertId FROM notified_alerts WHERE alertId IN (:ids)") suspend fun existing(ids: List<String>): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(item: NotifiedAlertEntity)
    @Query("DELETE FROM notified_alerts WHERE notifiedAtMs < :before") suspend fun prune(before: Long)
    @Query("DELETE FROM notified_alerts") suspend fun clear()
}

@Database(
    entities = [DeviceEntity::class, LocalAppUsageEntity::class, LocalDaySummaryEntity::class, NotifiedAlertEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class FamilySafetyDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun localUsageDao(): LocalUsageDao
    abstract fun notifiedAlertDao(): NotifiedAlertDao
}
