package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "epg_programs")
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val channelName: String, // We match by channel name for maximum flexibility
    val title: String,
    val description: String = "",
    val startTime: Long, // Epoch millis
    val endTime: Long // Epoch millis
)
