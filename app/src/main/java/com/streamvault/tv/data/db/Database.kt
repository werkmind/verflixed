package com.streamvault.tv.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Absolute URL – DiceBear OSS avatar or favorite series art. */
    val avatarUrl: String?,
    val createdAt: Long,
    val updatedAt: Long = createdAt
)

@Entity(tableName = "favorites", primaryKeys = ["profileId", "seriesId"])
data class FavoriteEntity(
    val profileId: String,
    val seriesId: String,
    val title: String,
    val posterUrl: String?,
    val cachedJson: String,
    val addedAt: Long,
    val streamsCached: Int = 0,
    val streamsTotal: Int = 0,
    val cacheStatus: String = "idle" // idle|caching|ready|partial
)

@Entity(tableName = "watch_progress", primaryKeys = ["profileId", "episodeId"])
data class WatchProgressEntity(
    val profileId: String,
    val episodeId: String,
    val seriesId: String,
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 1,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: Long
)

@Entity(tableName = "stream_cache", primaryKeys = ["profileId", "episodeId"])
data class StreamCacheEntity(
    val profileId: String,
    val episodeId: String,
    val seriesId: String,
    val streamUrl: String,
    val kind: String, // m3u8 | voe | other
    val updatedAt: Long
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt ASC")
    suspend fun all(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE profileId = :profileId ORDER BY addedAt DESC")
    suspend fun all(profileId: String): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE profileId = :profileId AND seriesId = :seriesId LIMIT 1")
    suspend fun get(profileId: String, seriesId: String): FavoriteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE profileId = :profileId AND seriesId = :seriesId)")
    suspend fun isFavorite(profileId: String, seriesId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE profileId = :profileId AND seriesId = :seriesId")
    suspend fun remove(profileId: String, seriesId: String)

    @Query("DELETE FROM favorites WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: String)

    @Query("DELETE FROM favorites")
    suspend fun clear()
}

@Dao
interface WatchDao {
    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId ORDER BY updatedAt DESC")
    suspend fun all(profileId: String): List<WatchProgressEntity>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND seriesId = :seriesId")
    suspend fun forSeries(profileId: String, seriesId: String): List<WatchProgressEntity>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND episodeId = :episodeId LIMIT 1")
    suspend fun get(profileId: String, episodeId: String): WatchProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId AND episodeId = :episodeId")
    suspend fun delete(profileId: String, episodeId: String)

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId AND seriesId = :seriesId AND seasonNumber = :seasonNumber")
    suspend fun deleteSeason(profileId: String, seriesId: String, seasonNumber: Int)

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: String)

    @Query("DELETE FROM watch_progress")
    suspend fun clear()
}

@Dao
interface StreamCacheDao {
    @Query("SELECT * FROM stream_cache WHERE profileId = :profileId AND episodeId = :episodeId LIMIT 1")
    suspend fun get(profileId: String, episodeId: String): StreamCacheEntity?

    @Query("SELECT * FROM stream_cache WHERE profileId = :profileId AND seriesId = :seriesId")
    suspend fun forSeries(profileId: String, seriesId: String): List<StreamCacheEntity>

    @Query("SELECT COUNT(*) FROM stream_cache WHERE profileId = :profileId AND seriesId = :seriesId")
    suspend fun countForSeries(profileId: String, seriesId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StreamCacheEntity)

    @Query("DELETE FROM stream_cache WHERE profileId = :profileId AND seriesId = :seriesId")
    suspend fun deleteSeries(profileId: String, seriesId: String)

    @Query("DELETE FROM stream_cache WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: String)

    @Query("DELETE FROM stream_cache")
    suspend fun clear()
}

@Database(
    entities = [
        ProfileEntity::class,
        FavoriteEntity::class,
        WatchProgressEntity::class,
        StreamCacheEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun favorites(): FavoriteDao
    abstract fun watch(): WatchDao
    abstract fun streams(): StreamCacheDao
}
