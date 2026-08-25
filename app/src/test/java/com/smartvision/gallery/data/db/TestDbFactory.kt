package com.smartvision.gallery.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

object TestDbFactory {
    fun buildInMemory(): SmartVisionDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SmartVisionDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
}