package io.github.tedtomato.duplicatirestore.index

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One backup version, i.e. one dlist file. */
@Entity(tableName = "version")
data class VersionEntity(
    @PrimaryKey val name: String,
    val time: Long,
    val remoteSize: Long,
    val loaded: Boolean = false,
    val fileCount: Int? = null,
    val totalSize: Long? = null,
    val blocksize: Int? = null,
)

@Entity(
    tableName = "file_entry",
    indices = [Index(value = ["versionName", "parentPath"]), Index(value = ["versionName", "normPath"])],
)
data class FileEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val versionName: String,
    /** Path as recorded in the backup, e.g. `C:\Users\x\a.txt`. */
    val path: String,
    /** Normalized with `/`, e.g. `C:/Users/x/a.txt`. */
    val normPath: String,
    val parentPath: String,
    val name: String,
    val size: Long,
    val hash: String,
    val time: String?,
    val blockhash: String?,
    /** Comma-separated standard base64 blocklist hashes, or null for files of one block or less. */
    val blocklists: String?,
)

/** A folder in a version's tree, with totals of everything below it. */
@Entity(
    tableName = "folder_entry",
    primaryKeys = ["versionName", "normPath"],
    indices = [Index(value = ["versionName", "parentPath"])],
)
data class FolderEntry(
    val versionName: String,
    val normPath: String,
    val parentPath: String,
    val name: String,
    val fileCount: Int,
    val totalSize: Long,
)

@Entity(tableName = "block_loc", primaryKeys = ["blockHash", "dblockName"])
data class BlockLoc(val blockHash: String, val dblockName: String, val size: Long)

@Entity(tableName = "volume")
data class VolumeEntity(@PrimaryKey val name: String, val size: Long, val volumeHash: String?)

@Entity(tableName = "blocklist")
data class BlocklistEntity(@PrimaryKey val hash: String, val data: ByteArray)

/** dindex files that have been read, so later syncs only read new ones. */
@Entity(tableName = "seen_index")
data class SeenIndex(@PrimaryKey val name: String)

@Entity(tableName = "restore_item", indices = [Index("jobId")])
data class RestoreItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: String,
    val fileId: Long,
    val displayPath: String,
    val size: Long,
    /** PENDING, OK or FAIL. */
    val status: String = "PENDING",
    val message: String? = null,
    val outputUri: String? = null,
)
