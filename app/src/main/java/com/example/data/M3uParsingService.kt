package com.example.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

object M3uParsingService {

    private val moshi = Moshi.Builder().build()

    private val listType = Types.newParameterizedType(List::class.java, ParsedM3uItem::class.java)
    private val jsonAdapter = moshi.adapter<List<ParsedM3uItem>>(listType)

    /**
     * Parses M3U playlist content and returns a structured JSON array of channels.
     */
    fun parseM3uToJson(content: String): String {
        val items = IptvParser.parseM3uToItems(content)
        return jsonAdapter.toJson(items)
    }

    /**
     * Parses a structured JSON array back into a list of [ParsedM3uItem] objects.
     */
    fun parseM3uFromJson(json: String): List<ParsedM3uItem> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            jsonAdapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
