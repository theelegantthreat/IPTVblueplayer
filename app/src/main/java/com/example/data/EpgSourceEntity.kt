package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "epg_sources")
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val url: String
)
