package com.reelshare.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.reelshare.core.database.dao.ReelDao
import com.reelshare.core.database.dao.ReelRemoteKeyDao
import com.reelshare.core.database.dao.CommentDao
import com.reelshare.core.database.entity.CommentEntity
import com.reelshare.core.database.entity.ReelEntity
import com.reelshare.core.database.entity.ReelRemoteKeyEntity

@Database(
    entities = [
        ReelEntity::class,
        ReelRemoteKeyEntity::class,
        CommentEntity::class,
        com.reelshare.core.database.entity.DraftEntity::class,
        com.reelshare.core.database.entity.StudioProfile::class,
        com.reelshare.core.database.entity.StudioSettings::class,
        com.reelshare.core.database.entity.StudioStyle::class
    ],
    version = 6,
    exportSchema = true
)
abstract class ReelDatabase : RoomDatabase() {
    abstract fun studioDao(): com.reelshare.core.database.dao.StudioDao
    abstract fun reelDao(): ReelDao
    abstract fun reelRemoteKeyDao(): ReelRemoteKeyDao
    abstract fun commentDao(): CommentDao
}
