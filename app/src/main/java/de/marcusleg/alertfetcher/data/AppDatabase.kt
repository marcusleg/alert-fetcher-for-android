package de.marcusleg.alertfetcher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [AlertEntity::class, CheckEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alertDao(): AlertDao
    abstract fun checkDao(): CheckDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "alerts.db")
                // Everything stored is a cache of Alertmanager's state and is re-fetched on the next check.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
