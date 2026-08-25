package com.smartvision.gallery.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        MediaEntity::class,
        MediaFlagEntity::class,
        AlbumEntity::class,
        UserAlbumItemEntity::class
    ],
    version = 10,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class SmartVisionDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun mediaFlagDao(): MediaFlagDao
    abstract fun albumDao(): AlbumDao
    abstract fun userAlbumDao(): UserAlbumDao

    companion object {
        fun create(context: Context): SmartVisionDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SmartVisionDatabase::class.java,
                "smartvision.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build()
    }
}