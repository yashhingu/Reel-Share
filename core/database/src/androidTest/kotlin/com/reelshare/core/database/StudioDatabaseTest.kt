package com.reelshare.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.reelshare.core.database.di.DatabaseModule
import com.reelshare.core.database.entity.DraftEntity
import com.reelshare.core.database.entity.StudioProfile
import com.reelshare.core.database.entity.StudioSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class StudioDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun draftAndProfileSurviveDatabaseReopen() = runBlocking {
        val name = "studio-persistence-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
        try {
            open().withDatabase { db ->
                db.studioDao().putDraft(DraftEntity("one", "/private/original.mp4", durationMs = 12_000,
                    width = 1080, height = 1920, trimStartMs = 1500, trimEndMs = 9000, aspect = "1:1", mute = true, audioPath = "/private/music/audio", audioName = "My song",
                    audioArtist = "Me", audioArtwork = "/private/music/cover", audioDurationMs = 20_000,
                    audioStartMs = 5000, audioEndMs = 10_000, audioVolume = .7f, editRecipe = "{\"version\":1,\"speed\":2}"))
                db.studioDao().putStyle(com.reelshare.core.database.entity.StudioStyle("style", "Warm", "{\"warmth\":0.4}", 1234))
                db.studioDao().putProfile(StudioProfile(name = "My studio", photoPath = "/private/photo.jpg"))
                db.studioDao().putSettings(StudioSettings(appearance = "Dark"))
            }
            open().withDatabase { db ->
                val draft = db.studioDao().draft("one")!!
                assertEquals(1500L, draft.trimStartMs)
                assertEquals(9000L, draft.trimEndMs)
                assertEquals("1:1", draft.aspect)
                assertTrue(draft.mute)
                assertEquals("/private/music/audio", draft.audioPath)
                assertEquals("My song", draft.audioName)
                assertEquals("Me", draft.audioArtist)
                assertEquals("/private/music/cover", draft.audioArtwork)
                assertEquals(20_000L, draft.audioDurationMs)
                assertEquals(5000L, draft.audioStartMs)
                assertEquals(10_000L, draft.audioEndMs)
                assertEquals(.7f, draft.audioVolume, .001f)
                assertEquals("{\"version\":1,\"speed\":2}", draft.editRecipe)
                assertEquals("Warm", db.studioDao().observeStyles().first().single().name)
                assertEquals("/private/original.mp4", draft.sourcePath)
                assertEquals("My studio", db.studioDao().profile()!!.name)
                assertEquals("/private/photo.jpg", db.studioDao().profile()!!.photoPath)
                assertEquals("Dark", db.studioDao().settings()!!.appearance)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionTwoMigrationPreservesOwnVideosAndRemovesSamples() = runBlocking {
        val name = "studio-migration-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        // The three legacy tables have unchanged definitions. Remove the studio additions
        // to reconstruct the v2 fixture, then exercise Room's real migration validation.
        Room.databaseBuilder(context, ReelDatabase::class.java, name).build().withDatabase { it.openHelper.writableDatabase }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("DROP TABLE drafts")
            old.execSQL("DROP TABLE studio_profile")
            old.execSQL("DROP TABLE studio_settings")
            old.execSQL("DROP TABLE studio_styles")
            old.execSQL("DROP TABLE room_master_table")
            fun insert(id: String, author: String) {
                old.execSQL("""INSERT INTO reels VALUES (?, 'A reel', '', 'file:///private/draft.mp4', '', ?, 'you', 'You', '', 0, 0, 0, 0, 0, 0, 0, 'Original', '', '', 12000, -1, 1)""", arrayOf(id, author))
            }
            insert("mine", "local_creator")
            insert("sample", "someone_else")
            old.version = 2
        }
        try {
            Room.databaseBuilder(context, ReelDatabase::class.java, name).addMigrations(DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4, DatabaseModule.MIGRATION_4_5, DatabaseModule.MIGRATION_5_6).build().withDatabase { migrated ->
                val own = migrated.studioDao().draft("mine")!!
                assertEquals("/private/draft.mp4", own.sourcePath)
                assertEquals(12000L, own.trimEndMs)
                assertEquals("READY", own.status)
                assertNull(migrated.studioDao().draft("sample"))
                assertNull(migrated.reelDao().getReelById("sample"))
                assertNotNull(migrated.reelDao().getReelById("mine"))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionThreeMigrationKeepsEditsAndDefaultsToSystemAppearance() = runBlocking {
        val name = "studio-appearance-migration.db"
        context.deleteDatabase(name)
        try {
            Room.databaseBuilder(context, ReelDatabase::class.java, name).build().withDatabase { db ->
                db.studioDao().putDraft(DraftEntity("kept", "/private/clip.mp4", durationMs = 3000,
                    width = 720, height = 1280, trimStartMs = 500, trimEndMs = 2500))
                db.studioDao().putProfile(StudioProfile(name = "Kept studio"))
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                restoreVersionFourDrafts(old)
                old.execSQL("DROP TABLE studio_settings")
                old.execSQL("DROP TABLE studio_styles")
            old.execSQL("DROP TABLE room_master_table")
                old.version = 3
            }
            Room.databaseBuilder(context, ReelDatabase::class.java, name)
                .addMigrations(DatabaseModule.MIGRATION_3_4, DatabaseModule.MIGRATION_4_5, DatabaseModule.MIGRATION_5_6).build().withDatabase { db ->
                    assertEquals(500L, db.studioDao().draft("kept")!!.trimStartMs)
                    assertEquals("Kept studio", db.studioDao().profile()!!.name)
                    assertNull(db.studioDao().settings())
                    db.studioDao().putSettings(StudioSettings(appearance = "Light"))
                    assertEquals("Light", db.studioDao().settings()!!.appearance)
                }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun versionFourMigrationKeepsDraftsAndDefaultsMusicToEmpty() = runBlocking {
        val name = "studio-music-migration.db"
        context.deleteDatabase(name)
        try {
            Room.databaseBuilder(context, ReelDatabase::class.java, name).build().withDatabase { db ->
                db.studioDao().putDraft(DraftEntity("kept", "/private/clip.mp4", durationMs = 3000,
                    width = 720, height = 1280, trimStartMs = 500, trimEndMs = 2500, mute = true))
                db.studioDao().putProfile(StudioProfile(name = "Kept studio"))
                db.studioDao().putSettings(StudioSettings(appearance = "Dark"))
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                restoreVersionFourDrafts(old)
                old.execSQL("DROP TABLE studio_styles")
            old.execSQL("DROP TABLE room_master_table")
                old.version = 4
            }
            Room.databaseBuilder(context, ReelDatabase::class.java, name)
                .addMigrations(DatabaseModule.MIGRATION_4_5, DatabaseModule.MIGRATION_5_6).build().withDatabase { db ->
                    val kept = db.studioDao().draft("kept")!!
                    assertEquals(500L, kept.trimStartMs)
                    assertTrue(kept.mute)
                    assertEquals("", kept.audioPath)
                    assertEquals(0L, kept.audioStartMs)
                    assertEquals(.5f, kept.audioVolume, .001f)
                    assertEquals("Kept studio", db.studioDao().profile()!!.name)
                    assertEquals("Dark", db.studioDao().settings()!!.appearance)
                    db.studioDao().putDraft(kept.copy(audioPath = "/private/music", audioDurationMs = 5000, audioEndMs = 2000))
                    assertEquals("/private/music", db.studioDao().draft("kept")!!.audioPath)
                }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionFiveMigrationPreservesMusicAndAddsEmptyRecipe() = runBlocking {
        val name = "studio-recipe-migration.db"
        context.deleteDatabase(name)
        try {
            Room.databaseBuilder(context, ReelDatabase::class.java, name).build().withDatabase { db ->
                db.studioDao().putDraft(DraftEntity("kept", "/private/clip.mp4", durationMs = 3000,
                    width = 720, height = 1280, trimEndMs = 2500, audioPath = "/private/music", audioStartMs = 300, audioEndMs = 2300, audioVolume = .8f))
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("CREATE TEMP TABLE v5_drafts AS SELECT `id`, `sourcePath`, `thumbnailPath`, `title`, `caption`, `durationMs`, `width`, `height`, `trimStartMs`, `trimEndMs`, `aspect`, `quality`, `mute`, `audioPath`, `audioName`, `audioArtist`, `audioArtwork`, `audioDurationMs`, `audioStartMs`, `audioEndMs`, `audioVolume`, `revision`, `renderedRevision`, `renderedPath`, `exportUri`, `status`, `error`, `workId`, `createdAt`, `updatedAt` FROM drafts")
                old.execSQL("DROP TABLE drafts")
                old.execSQL("""CREATE TABLE IF NOT EXISTS `drafts` (`id` TEXT NOT NULL, `sourcePath` TEXT NOT NULL, `thumbnailPath` TEXT NOT NULL, `title` TEXT NOT NULL, `caption` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `trimStartMs` INTEGER NOT NULL, `trimEndMs` INTEGER NOT NULL, `aspect` TEXT NOT NULL, `quality` TEXT NOT NULL, `mute` INTEGER NOT NULL, `audioPath` TEXT NOT NULL DEFAULT '', `audioName` TEXT NOT NULL DEFAULT '', `audioArtist` TEXT NOT NULL DEFAULT '', `audioArtwork` TEXT NOT NULL DEFAULT '', `audioDurationMs` INTEGER NOT NULL DEFAULT 0, `audioStartMs` INTEGER NOT NULL DEFAULT 0, `audioEndMs` INTEGER NOT NULL DEFAULT 0, `audioVolume` REAL NOT NULL DEFAULT 0.5, `revision` INTEGER NOT NULL, `renderedRevision` INTEGER NOT NULL, `renderedPath` TEXT NOT NULL, `exportUri` TEXT NOT NULL, `status` TEXT NOT NULL, `error` TEXT NOT NULL, `workId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                old.execSQL("INSERT INTO drafts SELECT * FROM v5_drafts")
                old.execSQL("DROP TABLE v5_drafts")
                old.execSQL("DROP TABLE studio_styles")
                old.execSQL("DROP TABLE room_master_table")
                old.version = 5
            }
            Room.databaseBuilder(context, ReelDatabase::class.java, name).addMigrations(DatabaseModule.MIGRATION_5_6).build().withDatabase { db ->
                val draft = db.studioDao().draft("kept")!!
                assertEquals("{}", draft.editRecipe)
                assertEquals("", draft.captionWorkId)
                assertEquals("/private/music", draft.audioPath)
                assertEquals(300L, draft.audioStartMs)
                assertEquals(.8f, draft.audioVolume, .001f)
                assertTrue(db.studioDao().observeStyles().first().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }

    // Frozen released v4 schema. Do not construct an old fixture from today's entity fields.
    private fun restoreVersionFourDrafts(old: SQLiteDatabase) {
        old.execSQL("CREATE TEMP TABLE kept_drafts AS SELECT `id`, `sourcePath`, `thumbnailPath`, `title`, `caption`, `durationMs`, `width`, `height`, `trimStartMs`, `trimEndMs`, `aspect`, `quality`, `mute`, `revision`, `renderedRevision`, `renderedPath`, `exportUri`, `status`, `error`, `workId`, `createdAt`, `updatedAt` FROM drafts")
        old.execSQL("DROP TABLE drafts")
        old.execSQL("""CREATE TABLE IF NOT EXISTS `drafts` (`id` TEXT NOT NULL, `sourcePath` TEXT NOT NULL, `thumbnailPath` TEXT NOT NULL, `title` TEXT NOT NULL, `caption` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `trimStartMs` INTEGER NOT NULL, `trimEndMs` INTEGER NOT NULL, `aspect` TEXT NOT NULL, `quality` TEXT NOT NULL, `mute` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `renderedRevision` INTEGER NOT NULL, `renderedPath` TEXT NOT NULL, `exportUri` TEXT NOT NULL, `status` TEXT NOT NULL, `error` TEXT NOT NULL, `workId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
        old.execSQL("INSERT INTO drafts SELECT * FROM kept_drafts")
        old.execSQL("DROP TABLE kept_drafts")
    }

}

private inline fun <T> ReelDatabase.withDatabase(block: (ReelDatabase) -> T): T = try { block(this) } finally { close() }
