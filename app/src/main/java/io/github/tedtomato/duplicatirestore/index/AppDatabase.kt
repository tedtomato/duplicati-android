package io.github.tedtomato.duplicatirestore.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        VersionEntity::class, FileEntry::class, FolderEntry::class, BlockLoc::class,
        VolumeEntity::class, BlocklistEntity::class, SeenIndex::class, RestoreItem::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): IndexDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "index.db")
                // The index is only a cache of the remote backup and can always be rebuilt.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
