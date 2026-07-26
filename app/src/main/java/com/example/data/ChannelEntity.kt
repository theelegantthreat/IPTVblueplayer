package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val url: String,
    val logoUrl: String = "",
    val category: String = "General",
    val country: String = "Unknown",
    val language: String = "Unknown",
    var status: String = "unknown", // "live", "dead", "unknown"
    val isFavorite: Boolean = false
)
