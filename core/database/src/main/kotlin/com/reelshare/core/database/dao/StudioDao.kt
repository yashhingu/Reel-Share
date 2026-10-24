package com.reelshare.core.database.dao

import androidx.room.*
import com.reelshare.core.database.entity.DraftEntity
import com.reelshare.core.database.entity.StudioProfile
import com.reelshare.core.database.entity.StudioSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface StudioDao {
    @Query("SELECT * FROM studio_styles ORDER BY createdAt DESC")
    fun observeStyles(): Flow<List<com.reelshare.core.database.entity.StudioStyle>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putStyle(style: com.reelshare.core.database.entity.StudioStyle)
    @Query("DELETE FROM studio_styles WHERE id = :id")
    suspend fun deleteStyle(id: String)
    @Query("UPDATE drafts SET editRecipe = :recipe, revision = revision + 1, status = 'DRAFT', error = '', captionWorkId = '', updatedAt = :updated WHERE id = :id AND captionWorkId = :workId AND revision = :revision AND workId = ''")
    suspend fun finishCaptions(id: String, workId: String, revision: Long, recipe: String, updated: Long): Int
    @Query("UPDATE drafts SET captionWorkId = '', error = :error WHERE id = :id AND captionWorkId = :workId")
    suspend fun clearCaptionTask(id: String, workId: String, error: String): Int
    @Query("SELECT * FROM studio_settings WHERE id = 1")
    fun observeSettings(): Flow<StudioSettings?>
    @Query("SELECT * FROM studio_settings WHERE id = 1")
    suspend fun settings(): StudioSettings?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSettings(settings: StudioSettings)
    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    fun observeDrafts(): Flow<List<DraftEntity>>
    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun draft(id: String): DraftEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDraft(draft: DraftEntity)
    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteDraft(id: String)
    @Query("SELECT * FROM studio_profile WHERE id = 1")
    fun observeProfile(): Flow<StudioProfile?>
    @Query("SELECT * FROM studio_profile WHERE id = 1")
    suspend fun profile(): StudioProfile?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProfile(profile: StudioProfile)
}
