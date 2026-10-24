package com.reelshare.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.reelshare.core.database.ReelDatabase
import com.reelshare.core.database.dao.ReelDao
import com.reelshare.core.database.dao.ReelRemoteKeyDao
import com.reelshare.core.database.dao.CommentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE drafts ADD COLUMN `editRecipe` TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("ALTER TABLE drafts ADD COLUMN `captionWorkId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE TABLE IF NOT EXISTS `studio_styles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `recipe` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        }
    }
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf("audioPath", "audioName", "audioArtist", "audioArtwork").forEach {
                db.execSQL("ALTER TABLE drafts ADD COLUMN `$it` TEXT NOT NULL DEFAULT ''")
            }
            listOf("audioDurationMs", "audioStartMs", "audioEndMs").forEach {
                db.execSQL("ALTER TABLE drafts ADD COLUMN `$it` INTEGER NOT NULL DEFAULT 0")
            }
            db.execSQL("ALTER TABLE drafts ADD COLUMN `audioVolume` REAL NOT NULL DEFAULT 0.5")
        }
    }
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `studio_settings` (`id` INTEGER NOT NULL, `appearance` TEXT NOT NULL, PRIMARY KEY(`id`))")
        }
    }
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS `drafts` (`id` TEXT NOT NULL, `sourcePath` TEXT NOT NULL, `thumbnailPath` TEXT NOT NULL, `title` TEXT NOT NULL, `caption` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `trimStartMs` INTEGER NOT NULL, `trimEndMs` INTEGER NOT NULL, `aspect` TEXT NOT NULL, `quality` TEXT NOT NULL, `mute` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `renderedRevision` INTEGER NOT NULL, `renderedPath` TEXT NOT NULL, `exportUri` TEXT NOT NULL, `status` TEXT NOT NULL, `error` TEXT NOT NULL, `workId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `studio_profile` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `bio` TEXT NOT NULL, `photoPath` TEXT NOT NULL, PRIMARY KEY(`id`))""")
            // Preserve videos made in the previous local feed. Never import sample posts.
            db.execSQL("""INSERT OR IGNORE INTO drafts SELECT id, REPLACE(videoUrl, 'file://', ''), REPLACE(thumbnailUrl, 'file://', ''), title, description, durationMs, 0, 0, 0, durationMs, 'Original', 'Balanced', 0, 0, 0, REPLACE(videoUrl, 'file://', ''), '', 'READY', '', '', createdAt, createdAt FROM reels WHERE authorId = 'local_creator'""")
            db.execSQL("DELETE FROM comments")
            db.execSQL("DELETE FROM reels WHERE authorId != 'local_creator'")
            db.execSQL("DELETE FROM reel_remote_keys")
        }
    }

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS `comments` (`id` TEXT NOT NULL, `reelId` TEXT NOT NULL, `userId` TEXT NOT NULL, `userName` TEXT NOT NULL, `userAvatarUrl` TEXT NOT NULL, `text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `likeCount` INTEGER NOT NULL, `isLiked` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_comments_reelId` ON `comments` (`reelId`)")
        }
    }

    @Provides
    @Singleton
    fun provideReelDatabase(
        @ApplicationContext context: Context
    ): ReelDatabase {
        return Room.databaseBuilder(
            context,
            ReelDatabase::class.java,
            "reelshare_database.db"
        )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
        .build()
    }

    @Provides
    fun provideReelDao(database: ReelDatabase): ReelDao {
        return database.reelDao()
    }

    @Provides
    fun provideReelRemoteKeyDao(database: ReelDatabase): ReelRemoteKeyDao {
        return database.reelRemoteKeyDao()
    }

    @Provides
    fun provideCommentDao(database: ReelDatabase): CommentDao = database.commentDao()

    @Provides
    fun provideStudioDao(database: ReelDatabase): com.reelshare.core.database.dao.StudioDao = database.studioDao()
}
