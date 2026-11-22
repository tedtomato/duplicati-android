package io.github.tedtomato.duplicatirestore.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class VolumeChoice(val blockHash: String, val dblockName: String)

@Dao
interface IndexDao {
    // Versions
    @Query("SELECT * FROM version ORDER BY time DESC")
    fun versions(): Flow<List<VersionEntity>>

    @Query("SELECT * FROM version WHERE name = :name")
    suspend fun version(name: String): VersionEntity?

    @Query("SELECT name FROM version")
    suspend fun versionNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVersions(v: List<VersionEntity>)

    @Query("DELETE FROM version WHERE name IN (:names)")
    suspend fun deleteVersions(names: List<String>)

    @Query("DELETE FROM file_entry WHERE versionName IN (:names)")
    suspend fun deleteFilesOf(names: List<String>)

    @Query("DELETE FROM folder_entry WHERE versionName IN (:names)")
    suspend fun deleteFoldersOf(names: List<String>)

    @Query("UPDATE version SET loaded = 1, fileCount = :count, totalSize = :size, blocksize = :blocksize WHERE name = :name")
    suspend fun markLoaded(name: String, count: Int, size: Long, blocksize: Int)

    // Files and folders
    @Insert
    suspend fun insertFiles(f: List<FileEntry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(f: List<FolderEntry>)

    @Query("SELECT * FROM folder_entry WHERE versionName = :v AND parentPath = :parent ORDER BY name COLLATE NOCASE")
    suspend fun subfolders(v: String, parent: String): List<FolderEntry>

    @Query("SELECT * FROM file_entry WHERE versionName = :v AND parentPath = :parent ORDER BY name COLLATE NOCASE")
    suspend fun filesIn(v: String, parent: String): List<FileEntry>

    @Query("SELECT * FROM file_entry WHERE versionName = :v AND instr(lower(normPath), lower(:q)) > 0 ORDER BY normPath COLLATE NOCASE LIMIT :limit")
    suspend fun search(v: String, q: String, limit: Int): List<FileEntry>

    /** All files under a folder (substr instead of LIKE, so `_` and `%` in paths are literal). */
    @Query("SELECT * FROM file_entry WHERE versionName = :v AND substr(normPath, 1, length(:prefix)) = :prefix")
    suspend fun filesUnder(v: String, prefix: String): List<FileEntry>

    @Query("SELECT * FROM file_entry WHERE id IN (:ids)")
    suspend fun filesById(ids: List<Long>): List<FileEntry>

    // Blocks and volumes
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlockLocs(b: List<BlockLoc>)

    @Upsert
    suspend fun upsertVolumes(v: List<VolumeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlocklists(b: List<BlocklistEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeen(s: List<SeenIndex>)

    @Query("SELECT name FROM seen_index")
    suspend fun seenIndexNames(): List<String>

    @Query("SELECT * FROM blocklist WHERE hash = :hash")
    suspend fun blocklist(hash: String): BlocklistEntity?

    @Query("SELECT blockHash, dblockName FROM block_loc WHERE blockHash IN (:hashes)")
    suspend fun locations(hashes: List<String>): List<VolumeChoice>

    @Query("SELECT * FROM volume WHERE name = :name")
    suspend fun volume(name: String): VolumeEntity?

    @Query("SELECT COUNT(*) FROM volume")
    suspend fun volumeCount(): Int

    // Restore jobs
    @Insert
    suspend fun insertRestoreItems(items: List<RestoreItem>)

    @Query("SELECT * FROM restore_item WHERE jobId = :jobId ORDER BY displayPath")
    fun restoreItems(jobId: String): Flow<List<RestoreItem>>

    @Query("SELECT * FROM restore_item WHERE jobId = :jobId")
    suspend fun restoreItemsNow(jobId: String): List<RestoreItem>

    @Query("UPDATE restore_item SET status = :status, message = :message, outputUri = :uri WHERE id = :id")
    suspend fun setRestoreResult(id: Long, status: String, message: String?, uri: String?)

    @Query("DELETE FROM restore_item WHERE jobId != :keep")
    suspend fun deleteOtherJobs(keep: String)

    @Transaction
    suspend fun clearAll() {
        clearFiles(); clearFolders(); clearVersions(); clearBlockLocs(); clearVolumes(); clearBlocklists(); clearSeen()
    }

    @Query("DELETE FROM file_entry") suspend fun clearFiles()
    @Query("DELETE FROM folder_entry") suspend fun clearFolders()
    @Query("DELETE FROM version") suspend fun clearVersions()
    @Query("DELETE FROM block_loc") suspend fun clearBlockLocs()
    @Query("DELETE FROM volume") suspend fun clearVolumes()
    @Query("DELETE FROM blocklist") suspend fun clearBlocklists()
    @Query("DELETE FROM seen_index") suspend fun clearSeen()
}
