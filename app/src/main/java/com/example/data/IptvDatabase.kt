package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ChannelEntity::class, EpgProgramEntity::class, EpgSourceEntity::class],
    version = 2,
    exportSchema = false
)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun iptvDao(): IptvDao

    companion object {
        @Volatile
        private var INSTANCE: IptvDatabase? = null

        fun getDatabase(context: Context): IptvDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    IptvDatabase::class.java,
                    "iptv_blue_player_db"
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
