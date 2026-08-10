package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ScanResults(
    val live: Int = 0,
    val dead: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class IptvViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: IptvRepository
    
    // UI state flows
    val allChannels: StateFlow<List<ChannelEntity>>
    val epgSources: StateFlow<List<EpgSourceEntity>>

    // Current selection
    private val _currentChannel = MutableStateFlow<ChannelEntity?>(null)
    val currentChannel: StateFlow<ChannelEntity?> = _currentChannel.asStateFlow()

    // EPG list for the currently selected channel
    val currentEpgPrograms: StateFlow<List<EpgProgramEntity>>

    // EPG list of all upcoming programs
    val allUpcomingEpgPrograms: StateFlow<List<EpgProgramEntity>>

    // Search and filters
    val searchQuery = MutableStateFlow("")
    val selectedFilterType = MutableStateFlow("all") // "all", "category", "country", "language"

    // Combined filtered channel list
    val filteredChannels: StateFlow<List<ChannelEntity>>

    // Channel Scanner State
    val isScanning = MutableStateFlow(false)
    val scanProgress = MutableStateFlow(0)
    val scanResults = MutableStateFlow(ScanResults())

    // Robust Local M3U Playlist Parser State
    private val _parsedPlaylistJsonState = MutableStateFlow<String>("[]")
    val parsedPlaylistJsonState: StateFlow<String> = _parsedPlaylistJsonState.asStateFlow()

    val parsedPlaylistItems: StateFlow<List<ParsedM3uItem>> = _parsedPlaylistJsonState
        .map { json -> M3uParsingService.parseM3uFromJson(json) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isParsingPlaylist = MutableStateFlow(false)
    val isParsingPlaylist: StateFlow<Boolean> = _isParsingPlaylist.asStateFlow()

    // Smart Deduplication State (Toggleable)
    val deduplicateImports = MutableStateFlow(true)

    fun setDeduplicateImports(enabled: Boolean) {
        deduplicateImports.value = enabled
    }

    fun parseM3uFromRemoteUrl(url: String, onCompleted: (Int) -> Unit) {
        viewModelScope.launch {
            _isParsingPlaylist.value = true
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder().url(url).build()
                val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val jsonStr = M3uParsingService.parseM3uToJson(bodyStr)
                    _parsedPlaylistJsonState.value = jsonStr
                    val count = M3uParsingService.parseM3uFromJson(jsonStr).size
                    onCompleted(count)
                } else {
                    _parsedPlaylistJsonState.value = "[]"
                    onCompleted(0)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _parsedPlaylistJsonState.value = "[]"
                onCompleted(0)
            } finally {
                _isParsingPlaylist.value = false
            }
        }
    }

    fun parseM3uFromLocalText(content: String, onCompleted: (Int) -> Unit) {
        viewModelScope.launch {
            _isParsingPlaylist.value = true
            try {
                val jsonStr = M3uParsingService.parseM3uToJson(content)
                _parsedPlaylistJsonState.value = jsonStr
                val count = M3uParsingService.parseM3uFromJson(jsonStr).size
                onCompleted(count)
            } catch (e: Exception) {
                e.printStackTrace()
                _parsedPlaylistJsonState.value = "[]"
                onCompleted(0)
            } finally {
                _isParsingPlaylist.value = false
            }
        }
    }

    fun importSelectedParsedItems(items: List<ParsedM3uItem>, onCompleted: (Int) -> Unit) {
        viewModelScope.launch {
            val channels = items.map { item ->
                ChannelEntity(
                    name = item.name,
                    url = item.url,
                    logoUrl = item.logoUrl,
                    category = item.groupTitle,
                    country = item.country,
                    language = item.language,
                    status = "unknown"
                )
            }
            if (channels.isNotEmpty()) {
                repository.insertChannels(channels, deduplicateImports.value)
            }
            onCompleted(channels.size)
        }
    }

    fun clearParsedPlaylist() {
        _parsedPlaylistJsonState.value = "[]"
    }

    private var scannerJob: Job? = null

    init {
        val database = IptvDatabase.getDatabase(application)
        val dao = database.iptvDao()
        repository = IptvRepository(dao)

        allChannels = repository.allChannelsFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        epgSources = repository.allEpgSourcesFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // Setup Epg program listening triggered by the current channel
        currentEpgPrograms = _currentChannel
            .flatMapLatest { channel ->
                if (channel == null) {
                    flowOf(emptyList())
                } else {
                    // Filter programs for selected channel name and current system time
                    repository.getEpgProgramsFlow(channel.name, System.currentTimeMillis())
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        allUpcomingEpgPrograms = repository.getAllUpcomingProgramsFlow(System.currentTimeMillis())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // Setup combined filtering
        filteredChannels = combine(
            allChannels,
            searchQuery,
            selectedFilterType
        ) { channels, query, filterType ->
            val q = query.trim().lowercase()
            val baseList = if (filterType == "favorites") {
                channels.filter { it.isFavorite }
            } else {
                channels
            }

            if (q.isEmpty()) {
                baseList
            } else {
                baseList.filter { channel ->
                    when (filterType) {
                        "category" -> channel.category.lowercase().contains(q)
                        "country" -> channel.country.lowercase().contains(q)
                        "language" -> channel.language.lowercase().contains(q)
                        else -> {
                            channel.name.lowercase().contains(q) ||
                                    channel.category.lowercase().contains(q) ||
                                    channel.country.lowercase().contains(q) ||
                                    channel.language.lowercase().contains(q)
                        }
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // Prepopulate database if empty on launch
        viewModelScope.launch {
            val currentList = repository.getAllChannels()
            if (currentList.isEmpty()) {
                prepopulateDefaults()
                prepopulateMockEpg()
            }
        }
    }

    private suspend fun prepopulateDefaults() {
        val defaults = listOf(
            ChannelEntity(
                name = "NASA TV",
                url = "https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8",
                logoUrl = "https://www.nasa.gov/favicon.ico",
                country = "USA",
                language = "English",
                category = "Science"
            ),
            ChannelEntity(
                name = "France 24 English",
                url = "https://stream.france24.com/hls/live/2037218/F24_EN_LO_HLS/master.m3u8",
                logoUrl = "https://www.france24.com/favicon.ico",
                country = "France",
                language = "English",
                category = "News"
            ),
            ChannelEntity(
                name = "DW News",
                url = "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8",
                logoUrl = "https://www.dw.com/favicon.ico",
                country = "Germany",
                language = "English",
                category = "News"
            ),
            ChannelEntity(
                name = "Al Jazeera English",
                url = "https://live-hls-web-aje.getaj.net/AJE/index.m3u8",
                logoUrl = "https://www.aljazeera.com/favicon.ico",
                country = "Qatar",
                language = "English",
                category = "News"
            ),
            ChannelEntity(
                name = "euronews English",
                url = "https://rakuten-euronews-1-gb.samsung.wurl.tv/manifest/playlist.m3u8",
                logoUrl = "https://www.euronews.com/favicon.ico",
                country = "EU",
                language = "English",
                category = "News"
            ),
            ChannelEntity(
                name = "Sky News",
                url = "https://skynews-ubr-live.3qsdn.com/demo-live-1/sky-news-demo_1080p25/chunklist.m3u8",
                logoUrl = "https://news.sky.com/favicon.ico",
                country = "UK",
                language = "English",
                category = "News"
            ),
            ChannelEntity(
                name = "CGTN",
                url = "https://news.cgtn.com/resource/live/english/cgtn-news.m3u8",
                logoUrl = "https://www.cgtn.com/favicon.ico",
                country = "China",
                language = "English",
                category = "News"
            ),
            ChannelEntity(
                name = "RT News",
                url = "https://rt-glb.rttv.com/liveedge/rtnews/playlist.m3u8",
                logoUrl = "https://www.rt.com/favicon.ico",
                country = "Russia",
                language = "English",
                category = "News"
            ),
            ChannelEntity(
                name = "TRT World",
                url = "https://trtworld.live.trt.com.tr/master.m3u8",
                logoUrl = "https://www.trtworld.com/favicon.ico",
                country = "Turkey",
                language = "English",
                category = "News"
            ),
            ChannelEntity(
                name = "NASA TV Media",
                url = "https://ntv2.akamaized.net/hls/live/2014078/NASA-NTV2-HLS/master.m3u8",
                logoUrl = "https://www.nasa.gov/favicon.ico",
                country = "USA",
                language = "English",
                category = "Science"
            )
        )
        repository.insertChannels(defaults)
    }

    private suspend fun prepopulateMockEpg() {
        val channels = repository.getAllChannels()
        val programs = mutableListOf<EpgProgramEntity>()
        val now = System.currentTimeMillis()
        val oneHour = 3600 * 1000L

        channels.forEach { ch ->
            val showTemplates = when (ch.name) {
                "NASA TV", "NASA TV Media" -> listOf(
                    "Space Station Live" to "Real-time coverage of the International Space Station crew activities.",
                    "Cosmic Voyages" to "Exploring deep space discoveries, nebulas, and distant galaxies.",
                    "Apollo Mission Retrospective" to "A deep dive into the historic Apollo moon landings.",
                    "Mars Rover Chronicles" to "Latest updates and imagery from the Perseverance and Curiosity rovers.",
                    "Hubble & James Webb Showcase" to "Comparing breathtaking images of the cosmos from flagship telescopes.",
                    "Astronaut Training Academy" to "How astronauts prepare for extreme environments in low Earth orbit."
                )
                "France 24 English" -> listOf(
                    "Paris Eye" to "Analysis of French and European politics, culture, and business.",
                    "The Debate" to "Lively discussion with experts on major international current events.",
                    "Arts and Culture Special" to "Exposing the latest in European cinema, literature, and art.",
                    "World News Hour" to "Comprehensive coverage of global headlines and breaking news.",
                    "Tech 24" to "The weekly magazine of technology and digital innovation.",
                    "Focus" to "In-depth investigative reports from our correspondents around the globe."
                )
                "DW News" -> listOf(
                    "DW News Today" to "In-depth reporting on politics, business, and current European news.",
                    "Made in Germany" to "The German business magazine focusing on economy and industry.",
                    "Conflict Zone" to "Hard-hitting interviews with key political decision-makers.",
                    "Tomorrow Today" to "The science program exploring breakthrough technologies.",
                    "Eco India" to "Finding sustainable solutions to environmental issues.",
                    "DocFilm Specials" to "Award-winning documentaries from the heart of Europe."
                )
                "Al Jazeera English" -> listOf(
                    "Al Jazeera News Hour" to "Breaking global news and analysis from the Middle East and worldwide.",
                    "The Stream" to "Social media community discussion on news of the day.",
                    "Inside Story" to "A panels-driven debate dissecting a major international issue.",
                    "Listening Post" to "Monitoring how global media covers the news of the week.",
                    "Fault Lines" to "Hard-hitting investigative journalism focusing on systemic issues.",
                    "Talk to Al Jazeera" to "Exclusive interviews with world leaders and heads of state."
                )
                "Sky News" -> listOf(
                    "Sky News Tonight" to "The biggest stories of the day dissected with UK and global focus.",
                    "Press Preview" to "A lively review of tomorrow's newspaper front pages.",
                    "Sophy Ridge on Sunday" to "Interviews with leading politicians and decision makers.",
                    "Ian King Live" to "The latest business, market, and financial updates.",
                    "The Daily Climate Show" to "In-depth tracking of the environmental challenges facing Earth.",
                    "Sky News World Report" to "Reporting from overseas correspondents on global stories."
                )
                else -> listOf(
                    "Global News Digest" to "A rapid summary of the hour's top stories.",
                    "Tech Frontiers" to "Discovering next-generation computing and AI advancements.",
                    "Culture & Cuisine" to "A tour of spectacular food and traditions around the world.",
                    "In-Depth Report" to "Special investigative journalism on global socioeconomic events.",
                    "Documentary Showcase" to "Compelling human stories from independent filmmakers.",
                    "Market Watch Daily" to "Analyzing currency, stocks, and international trade."
                )
            }

            val startTimeBase = now - (now % oneHour) - 4 * oneHour
            for (i in 0 until 12) {
                val template = showTemplates[i % showTemplates.size]
                programs.add(
                    EpgProgramEntity(
                        channelName = ch.name,
                        title = template.first,
                        description = template.second,
                        startTime = startTimeBase + i * oneHour,
                        endTime = startTimeBase + (i + 1) * oneHour
                    )
                )
            }
        }

        repository.insertPrograms(programs)
    }

    fun generateDemoEpg() {
        viewModelScope.launch {
            prepopulateMockEpg()
        }
    }

    fun selectChannel(channel: ChannelEntity?) {
        _currentChannel.value = channel
    }

    fun addChannel(
        name: String,
        url: String,
        logoUrl: String,
        category: String,
        country: String,
        language: String,
        status: String = "unknown"
    ) {
        viewModelScope.launch {
            val ch = ChannelEntity(
                name = name,
                url = url,
                logoUrl = logoUrl,
                category = if (category.trim().isEmpty()) "General" else category,
                country = if (country.trim().isEmpty()) "Unknown" else country,
                language = if (language.trim().isEmpty()) "Unknown" else language,
                status = status
            )
            val id = repository.insertChannel(ch)
            // Auto play added channel
            _currentChannel.value = ch.copy(id = id.toInt())
        }
    }

    fun updateChannel(channel: ChannelEntity) {
        viewModelScope.launch {
            repository.updateChannel(channel)
            if (_currentChannel.value?.id == channel.id) {
                _currentChannel.value = channel
            }
        }
    }

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch {
            val updated = channel.copy(isFavorite = !channel.isFavorite)
            repository.updateChannel(updated)
            if (_currentChannel.value?.id == channel.id) {
                _currentChannel.value = updated
            }
        }
    }

    fun deleteChannel(channel: ChannelEntity) {
        viewModelScope.launch {
            repository.deleteChannel(channel)
            if (_currentChannel.value?.id == channel.id) {
                _currentChannel.value = null
            }
        }
    }

    fun clearAllChannels() {
        viewModelScope.launch {
            repository.deleteAllChannels()
            _currentChannel.value = null
        }
    }

    fun deleteDeadChannels() {
        viewModelScope.launch {
            repository.deleteDeadChannels()
            if (_currentChannel.value?.status == "dead") {
                _currentChannel.value = null
            }
        }
    }

    fun addEpgSource(name: String, url: String) {
        viewModelScope.launch {
            repository.insertEpgSource(EpgSourceEntity(name = name, url = url))
        }
    }

    fun deleteEpgSource(source: EpgSourceEntity) {
        viewModelScope.launch {
            repository.deleteEpgSource(source)
        }
    }

    fun verifyChannel(url: String, onCompleted: (Boolean) -> Unit) {
        viewModelScope.launch {
            val isLive = repository.verifyStreamUrl(url)
            onCompleted(isLive)
        }
    }

    // --- XMLTV EPG Loading ---
    fun syncEpgFromUrl(url: String, onCompleted: (Int) -> Unit) {
        viewModelScope.launch {
            val count = repository.downloadAndImportEpg(url)
            onCompleted(count)
        }
    }

    // --- M3U Loading ---
    fun importM3uFromUrl(url: String, onCompleted: (Int) -> Unit) {
        viewModelScope.launch {
            val count = repository.downloadAndImportM3u(url, deduplicateImports.value)
            onCompleted(count)
        }
    }

    fun importM3uContent(content: String, onCompleted: (Int) -> Unit) {
        viewModelScope.launch {
            val channels = IptvParser.parseM3u(content)
            if (channels.isNotEmpty()) {
                repository.insertChannels(channels, deduplicateImports.value)
            }
            onCompleted(channels.size)
        }
    }

    // --- JSON Backup / Restore ---
    fun exportBackup(): String {
        return try {
            val arr = JSONArray()
            allChannels.value.forEach { ch ->
                val obj = JSONObject()
                obj.put("name", ch.name)
                obj.put("url", ch.url)
                obj.put("logoUrl", ch.logoUrl)
                obj.put("category", ch.category)
                obj.put("country", ch.country)
                obj.put("language", ch.language)
                obj.put("status", ch.status)
                arr.put(obj)
            }
            val root = JSONObject()
            root.put("version", 1)
            root.put("channels", arr)
            root.toString(2)
        } catch (e: Exception) {
            ""
        }
    }

    fun importBackupContent(jsonStr: String, onCompleted: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val root = JSONObject(jsonStr)
                val arr = if (root.has("channels")) {
                    root.getJSONArray("channels")
                } else {
                    JSONArray(jsonStr) // Fallback to raw list
                }
                
                val list = mutableListOf<ChannelEntity>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.optString("name", "Unnamed")
                    val url = obj.optString("url", "")
                    if (url.isEmpty()) continue

                    list.add(
                        ChannelEntity(
                            name = name,
                            url = url,
                            logoUrl = obj.optString("logoUrl", obj.optString("favicon", "")),
                            category = obj.optString("category", "General"),
                            country = obj.optString("country", "Unknown"),
                            language = obj.optString("language", "Unknown"),
                            status = "unknown"
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    repository.insertChannels(list, deduplicateImports.value)
                }
                onCompleted(list.size)
            } catch (e: Exception) {
                e.printStackTrace()
                onCompleted(0)
            }
        }
    }

    // --- Stream Scanner ---
    fun runStreamScanner(autoDeleteDead: Boolean) {
        if (isScanning.value) {
            scannerJob?.cancel()
            isScanning.value = false
            return
        }

        isScanning.value = true
        scanProgress.value = 0
        scanResults.value = ScanResults()

        scannerJob = viewModelScope.launch {
            val channelsToScan = allChannels.value
            val total = channelsToScan.size
            if (total == 0) {
                isScanning.value = false
                return@launch
            }

            var live = 0
            var dead = 0
            val deadList = mutableListOf<ChannelEntity>()

            for (i in 0 until total) {
                val ch = channelsToScan[i]
                val isLive = repository.verifyStreamUrl(ch.url)
                val updatedCh = ch.copy(status = if (isLive) "live" else "dead")
                
                repository.updateChannel(updatedCh)
                
                if (isLive) {
                    live++
                } else {
                    dead++
                    deadList.add(updatedCh)
                }

                scanProgress.value = ((i + 1) * 100) / total
                scanResults.value = ScanResults(live = live, dead = dead)
            }

            if (autoDeleteDead && deadList.isNotEmpty()) {
                repository.deleteDeadChannels()
            }

            isScanning.value = false
        }
    }
}
