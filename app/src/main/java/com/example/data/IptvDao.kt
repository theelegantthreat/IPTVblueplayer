package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface IptvDao {

    // --- Channels ---
    @Query("SELECT * FROM channels ORDER BY name ASC")
    fun getAllChannelsFlow(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY name ASC")
    suspend fun getAllChannels(): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannel(channel: ChannelEntity): Long

    @Query("SELECT * FROM channels WHERE url = :url LIMIT 1")
    suspend fun getChannelByUrl(url: String): ChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Update
    suspend fun updateChannel(channel: ChannelEntity)

    @Update
    suspend fun updateChannels(channels: List<ChannelEntity>)

    @Delete
    suspend fun deleteChannel(channel: ChannelEntity)

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun deleteChannelById(id: Int)

    @Query("DELETE FROM channels WHERE status = 'dead'")
    suspend fun deleteDeadChannels()

    @Query("DELETE FROM channels")
    suspend fun deleteAllChannels()


    // --- EPG Programs ---
    @Query("SELECT * FROM epg_programs WHERE channelName = :channelName AND endTime >= :currentTime ORDER BY startTime ASC")
    fun getProgramsForChannelFlow(channelName: String, currentTime: Long): Flow<List<EpgProgramEntity>>

    @Query("SELECT * FROM epg_programs WHERE channelName = :channelName AND endTime >= :currentTime ORDER BY startTime ASC")
    suspend fun getProgramsForChannel(channelName: String, currentTime: Long): List<EpgProgramEntity>

    @Query("SELECT * FROM epg_programs WHERE endTime >= :currentTime ORDER BY startTime ASC")
    fun getAllUpcomingProgramsFlow(currentTime: Long): Flow<List<EpgProgramEntity>>

    @Query("SELECT * FROM epg_programs WHERE endTime >= :currentTime ORDER BY startTime ASC")
    suspend fun getAllUpcomingPrograms(currentTime: Long): List<EpgProgramEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(programs: List<EpgProgramEntity>)

    @Query("DELETE FROM epg_programs WHERE channelName = :channelName")
    suspend fun deleteProgramsForChannel(channelName: String)

    @Query("DELETE FROM epg_programs")
    suspend fun deleteAllPrograms()

    @Query("DELETE FROM epg_programs WHERE endTime < :currentTime")
    suspend fun deleteExpiredPrograms(currentTime: Long)


    // --- EPG Sources ---
    @Query("SELECT * FROM epg_sources ORDER BY name ASC")
    fun getAllEpgSourcesFlow(): Flow<List<EpgSourceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpgSource(source: EpgSourceEntity): Long

    @Delete
    suspend fun deleteEpgSource(source: EpgSourceEntity)
}
