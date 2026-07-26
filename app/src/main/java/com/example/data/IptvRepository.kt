package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class IptvRepository(private val dao: IptvDao) {

    val allChannelsFlow: Flow<List<ChannelEntity>> = dao.getAllChannelsFlow()
    val allEpgSourcesFlow: Flow<List<EpgSourceEntity>> = dao.getAllEpgSourcesFlow()

    fun getEpgProgramsFlow(channelName: String, currentTime: Long): Flow<List<EpgProgramEntity>> {
        return dao.getProgramsForChannelFlow(channelName, currentTime)
    }

    fun getAllUpcomingProgramsFlow(currentTime: Long): Flow<List<EpgProgramEntity>> {
        return dao.getAllUpcomingProgramsFlow(currentTime)
    }

    suspend fun getAllUpcomingPrograms(currentTime: Long): List<EpgProgramEntity> = withContext(Dispatchers.IO) {
        dao.getAllUpcomingPrograms(currentTime)
    }

    suspend fun insertPrograms(programs: List<EpgProgramEntity>) = withContext(Dispatchers.IO) {
        dao.insertPrograms(programs)
    }

    suspend fun getAllChannels(): List<ChannelEntity> = withContext(Dispatchers.IO) {
        dao.getAllChannels()
    }

    suspend fun insertChannel(channel: ChannelEntity, deduplicate: Boolean = true): Long = withContext(Dispatchers.IO) {
        if (deduplicate) {
            val existing = dao.getChannelByUrl(channel.url)
            if (existing != null) {
                val updated = existing.copy(
                    name = channel.name.ifEmpty { existing.name },
                    logoUrl = channel.logoUrl.ifEmpty { existing.logoUrl },
                    category = if (channel.category != "General") channel.category else existing.category,
                    country = if (channel.country != "Unknown") channel.country else existing.country,
                    language = if (channel.language != "Unknown") channel.language else existing.language
                )
                dao.updateChannel(updated)
                existing.id.toLong()
            } else {
                dao.insertChannel(channel)
            }
        } else {
            dao.insertChannel(channel)
        }
    }

    suspend fun insertChannels(channels: List<ChannelEntity>, deduplicate: Boolean = true) = withContext(Dispatchers.IO) {
        if (deduplicate) {
            val existingChannels = dao.getAllChannels()
            val existingMap = existingChannels.associateBy { it.url }
            
            val toInsert = mutableListOf<ChannelEntity>()
            val toUpdate = mutableListOf<ChannelEntity>()
            
            val uniqueIncoming = channels.distinctBy { it.url }
            for (ch in uniqueIncoming) {
                val existing = existingMap[ch.url]
                if (existing != null) {
                    val updated = existing.copy(
                        name = ch.name.ifEmpty { existing.name },
                        logoUrl = ch.logoUrl.ifEmpty { existing.logoUrl },
                        category = if (ch.category != "General") ch.category else existing.category,
                        country = if (ch.country != "Unknown") ch.country else existing.country,
                        language = if (ch.language != "Unknown") ch.language else existing.language
                    )
                    if (updated != existing) {
                        toUpdate.add(updated)
                    }
                } else {
                    toInsert.add(ch)
                }
            }
            if (toInsert.isNotEmpty()) {
                dao.insertChannels(toInsert)
            }
            if (toUpdate.isNotEmpty()) {
                dao.updateChannels(toUpdate)
            }
        } else {
            dao.insertChannels(channels)
        }
    }

    suspend fun updateChannel(channel: ChannelEntity) = withContext(Dispatchers.IO) {
        dao.updateChannel(channel)
    }

    suspend fun deleteChannel(channel: ChannelEntity) = withContext(Dispatchers.IO) {
        dao.deleteChannel(channel)
    }

    suspend fun deleteChannelById(id: Int) = withContext(Dispatchers.IO) {
        dao.deleteChannelById(id)
    }

    suspend fun deleteDeadChannels() = withContext(Dispatchers.IO) {
        dao.deleteDeadChannels()
    }

    suspend fun deleteAllChannels() = withContext(Dispatchers.IO) {
        dao.deleteAllChannels()
    }

    suspend fun insertEpgSource(source: EpgSourceEntity): Long = withContext(Dispatchers.IO) {
        dao.insertEpgSource(source)
    }

    suspend fun deleteEpgSource(source: EpgSourceEntity) = withContext(Dispatchers.IO) {
        dao.deleteEpgSource(source)
    }

    suspend fun deleteAllPrograms() = withContext(Dispatchers.IO) {
        dao.deleteAllPrograms()
    }

    suspend fun deleteExpiredPrograms(currentTime: Long) = withContext(Dispatchers.IO) {
        dao.deleteExpiredPrograms(currentTime)
    }

    // --- Networking Operations ---

    suspend fun downloadAndImportM3u(url: String, deduplicate: Boolean = true): Int = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext 0
                val bodyStr = response.body?.string() ?: return@withContext 0
                val channels = IptvParser.parseM3u(bodyStr)
                if (channels.isNotEmpty()) {
                    insertChannels(channels, deduplicate)
                }
                return@withContext channels.size
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0
        }
    }

    suspend fun downloadAndImportEpg(url: String): Int = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext 0
                val bodyStr = response.body?.string() ?: return@withContext 0
                val programs = IptvParser.parseXmlTvEpg(bodyStr)
                if (programs.isNotEmpty()) {
                    dao.insertPrograms(programs)
                }
                return@withContext programs.size
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0
        }
    }

    suspend fun verifyStreamUrl(urlStr: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(urlStr).head().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code in 200..399) {
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            // Fallback to small GET request
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(urlStr).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code in 200..399) {
                        return@withContext true
                    }
                }
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
        return@withContext false
    }
}
