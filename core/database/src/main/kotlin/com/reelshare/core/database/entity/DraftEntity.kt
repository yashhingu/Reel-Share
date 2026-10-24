package com.reelshare.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/** Edit decisions are durable; the rendered file may be evicted from cache. */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val id: String,
    val sourcePath: String,
    val thumbnailPath: String = "",
    val title: String = "Untitled reel",
    val caption: String = "",
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val trimStartMs: Long = 0,
    val trimEndMs: Long,
    val aspect: String = "Original",
    val quality: String = "Balanced",
    val mute: Boolean = false,
    @ColumnInfo(defaultValue = "''") val audioPath: String = "",
    @ColumnInfo(defaultValue = "''") val audioName: String = "",
    @ColumnInfo(defaultValue = "''") val audioArtist: String = "",
    @ColumnInfo(defaultValue = "''") val audioArtwork: String = "",
    @ColumnInfo(defaultValue = "0") val audioDurationMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val audioStartMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val audioEndMs: Long = 0,
    @ColumnInfo(defaultValue = "0.5") val audioVolume: Float = 0.5f,
    @ColumnInfo(defaultValue = "'{}'") val editRecipe: String = "{}",
    @ColumnInfo(defaultValue = "''") val captionWorkId: String = "",
    val revision: Long = 0,
    val renderedRevision: Long = -1,
    val renderedPath: String = "",
    val exportUri: String = "",
    val status: String = "DRAFT",
    val error: String = "",
    val workId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "studio_styles")
data class StudioStyle(@PrimaryKey val id: String, val name: String, val recipe: String, val createdAt: Long)

@Entity(tableName = "studio_profile")
data class StudioProfile(
    @PrimaryKey val id: Int = 1,
    val name: String = "Your studio",
    val bio: String = "A little more creative, every day.",
    val photoPath: String = ""
)
