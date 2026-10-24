package com.reelshare.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "studio_settings")
data class StudioSettings(
    @PrimaryKey val id: Int = 1,
    val appearance: String = "System"
)
