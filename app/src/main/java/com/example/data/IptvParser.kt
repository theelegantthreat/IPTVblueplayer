package com.example.data

import android.util.Xml
import com.squareup.moshi.JsonClass
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern

@JsonClass(generateAdapter = true)
data class ParsedM3uItem(
    val name: String,
    val url: String,
    val groupTitle: String,
    val logoUrl: String = "",
    val country: String = "Unknown",
    val language: String = "Unknown"
)

object IptvParser {

    private data class PendingChannelInfo(
        val name: String,
        val logoUrl: String = "",
        val category: String = "General",
        val country: String = "Unknown",
        val language: String = "Unknown"
    )

    fun parseM3uToItems(content: String): List<ParsedM3uItem> {
        val cleanContent = content.replace("\uFEFF", "")
        val items = mutableListOf<ParsedM3uItem>()
        val lines = cleanContent.lines()
        var currentInfo: PendingChannelInfo? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTINF:") || trimmed.startsWith("#EXTINF ")) {
                val displayName = if (trimmed.contains(",")) {
                    trimmed.substringAfterLast(",").trim()
                } else {
                    ""
                }
                val logoUrl = extractAttribute(trimmed, "tvg-logo") ?: extractAttribute(trimmed, "logo") ?: ""
                val category = extractAttribute(trimmed, "group-title") ?: extractAttribute(trimmed, "category") ?: "General"
                val country = extractAttribute(trimmed, "tvg-country") ?: extractAttribute(trimmed, "country") ?: "Unknown"
                val language = extractAttribute(trimmed, "tvg-language") ?: extractAttribute(trimmed, "language") ?: "Unknown"
                val tvgName = extractAttribute(trimmed, "tvg-name")

                val finalName = displayName.ifEmpty { tvgName ?: "" }.trim()

                currentInfo = PendingChannelInfo(
                    name = finalName,
                    logoUrl = logoUrl,
                    category = category.ifEmpty { "General" },
                    country = country.ifEmpty { "Unknown" },
                    language = language.ifEmpty { "Unknown" }
                )
            } else if (!trimmed.startsWith("#") && (trimmed.contains("://") || trimmed.startsWith("http") || trimmed.startsWith("rtsp") || trimmed.contains(".m3u8") || trimmed.contains(".ts"))) {
                val info = currentInfo ?: PendingChannelInfo(
                    name = trimmed.substringAfterLast("/").substringBefore("?").ifEmpty { "Unnamed Stream" }
                )
                val finalName = info.name.ifEmpty { trimmed.substringAfterLast("/").substringBefore("?").ifEmpty { "Unnamed Stream" } }
                items.add(
                    ParsedM3uItem(
                        name = finalName,
                        url = trimmed,
                        groupTitle = info.category,
                        logoUrl = info.logoUrl,
                        country = info.country,
                        language = info.language
                    )
                )
                currentInfo = null
            }
        }
        return items
    }

    fun parseM3u(content: String): List<ChannelEntity> {
        val cleanContent = content.replace("\uFEFF", "")
        val channels = mutableListOf<ChannelEntity>()
        val lines = cleanContent.lines()
        var currentInfo: PendingChannelInfo? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTINF:") || trimmed.startsWith("#EXTINF ")) {
                // Parse displayName (everything after the last comma)
                val displayName = if (trimmed.contains(",")) {
                    trimmed.substringAfterLast(",").trim()
                } else {
                    ""
                }
                val logoUrl = extractAttribute(trimmed, "tvg-logo") ?: extractAttribute(trimmed, "logo") ?: ""
                val category = extractAttribute(trimmed, "group-title") ?: extractAttribute(trimmed, "category") ?: "General"
                val country = extractAttribute(trimmed, "tvg-country") ?: extractAttribute(trimmed, "country") ?: "Unknown"
                val language = extractAttribute(trimmed, "tvg-language") ?: extractAttribute(trimmed, "language") ?: "Unknown"

                currentInfo = PendingChannelInfo(
                    name = displayName.ifEmpty { "Unknown Channel" },
                    logoUrl = logoUrl,
                    category = category.ifEmpty { "General" },
                    country = country.ifEmpty { "Unknown" },
                    language = language.ifEmpty { "Unknown" }
                )
            } else if (!trimmed.startsWith("#") && (trimmed.contains("://") || trimmed.startsWith("http") || trimmed.startsWith("rtsp") || trimmed.contains(".m3u8") || trimmed.contains(".ts") || trimmed.contains(".mpd") || trimmed.contains(".mp4"))) {
                val info = currentInfo ?: PendingChannelInfo(
                    name = trimmed.substringAfterLast("/").substringBefore("?").ifEmpty { "Unnamed Stream" }
                )
                val finalName = info.name.ifEmpty { trimmed.substringAfterLast("/").substringBefore("?").ifEmpty { "Unnamed Stream" } }
                channels.add(
                    ChannelEntity(
                        name = finalName,
                        url = trimmed,
                        logoUrl = info.logoUrl,
                        category = info.category,
                        country = info.country,
                        language = info.language,
                        status = "unknown"
                    )
                )
                currentInfo = null
            }
        }
        return channels
    }

    private fun extractAttribute(line: String, attribute: String): String? {
        val patterns = listOf(
            Pattern.compile("$attribute\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("$attribute\\s*=\\s*'([^']*)'", Pattern.CASE_INSENSITIVE),
            Pattern.compile("$attribute\\s*=\\s*([^\\s,]+)", Pattern.CASE_INSENSITIVE)
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(line)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    fun parseXmlTvDate(dateStr: String): Long {
        try {
            val clean = dateStr.replace("[^0-9]".toRegex(), "")
            if (clean.length >= 14) {
                val yyyy = clean.substring(0, 4).toInt()
                val MM = clean.substring(4, 6).toInt() - 1
                val dd = clean.substring(6, 8).toInt()
                val HH = clean.substring(8, 10).toInt()
                val mm = clean.substring(10, 12).toInt()
                val ss = clean.substring(12, 14).toInt()

                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.set(yyyy, MM, dd, HH, mm, ss)
                cal.set(Calendar.MILLISECOND, 0)

                val tzMatch = Pattern.compile("([+-])(\\d{2})(\\d{2})").matcher(dateStr)
                if (tzMatch.find()) {
                    val sign = tzMatch.group(1)
                    val hours = tzMatch.group(2)?.toIntOrNull() ?: 0
                    val mins = tzMatch.group(3)?.toIntOrNull() ?: 0
                    val offsetMillis = (hours * 3600 + mins * 60) * 1000L
                    val time = cal.timeInMillis
                    return if (sign == "+") time - offsetMillis else time + offsetMillis
                }
                return cal.timeInMillis
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return System.currentTimeMillis()
    }

    fun parseXmlTvEpg(xmlContent: String): List<EpgProgramEntity> {
        val programs = mutableListOf<EpgProgramEntity>()
        val channelMap = mutableMapOf<String, String>() // Map xmlChannelId to display name
        
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xmlContent))
            var eventType = parser.eventType
            
            var currentChannelId: String? = null
            var currentChannelName: String? = null
            
            var currentProgChannel: String? = null
            var currentProgStart: String? = null
            var currentProgStop: String? = null
            var currentProgTitle: String? = null
            var currentProgDesc = ""
            
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = name
                        if (name == "channel") {
                            currentChannelId = parser.getAttributeValue(null, "id")
                            currentChannelName = null
                        } else if (name == "programme") {
                            currentProgChannel = parser.getAttributeValue(null, "channel")
                            currentProgStart = parser.getAttributeValue(null, "start")
                            currentProgStop = parser.getAttributeValue(null, "stop")
                            currentProgTitle = null
                            currentProgDesc = ""
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            if (currentTag == "display-name" && currentChannelId != null) {
                                currentChannelName = text
                            } else if (currentTag == "title" && currentProgChannel != null) {
                                currentProgTitle = text
                            } else if (currentTag == "desc" && currentProgChannel != null) {
                                currentProgDesc = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        currentTag = ""
                        if (name == "channel") {
                            if (currentChannelId != null && currentChannelName != null) {
                                channelMap[currentChannelId] = currentChannelName
                            }
                            currentChannelId = null
                            currentChannelName = null
                        } else if (name == "programme") {
                            if (currentProgChannel != null && currentProgTitle != null && currentProgStart != null && currentProgStop != null) {
                                // Match program to readable channel name
                                val mappedChannelName = channelMap[currentProgChannel] ?: currentProgChannel
                                val startMs = parseXmlTvDate(currentProgStart)
                                val stopMs = parseXmlTvDate(currentProgStop)
                                
                                programs.add(
                                    EpgProgramEntity(
                                        channelName = mappedChannelName,
                                        title = currentProgTitle ?: "No Title",
                                        description = currentProgDesc,
                                        startTime = startMs,
                                        endTime = stopMs
                                    )
                                )
                            }
                            currentProgChannel = null
                            currentProgStart = null
                            currentProgStop = null
                            currentProgTitle = null
                            currentProgDesc = ""
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return programs
    }
}
