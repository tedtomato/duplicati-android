package io.github.tedtomato.duplicatirestore

import android.app.Application
import io.github.tedtomato.duplicatirestore.cache.VolumeCache
import io.github.tedtomato.duplicatirestore.data.SecretStore
import io.github.tedtomato.duplicatirestore.data.Settings
import io.github.tedtomato.duplicatirestore.index.AppDatabase
import io.github.tedtomato.duplicatirestore.sync.BackupRepository
import io.github.tedtomato.duplicatirestore.update.Updater

class App : Application() {
    val db by lazy { AppDatabase.create(this) }
    val settings by lazy { Settings(this) }
    val secretStore by lazy { SecretStore(this) }
    val volumeCache by lazy { VolumeCache(cacheDir) { settings.cacheLimitMb * 1_000_000L } }
    val repository by lazy { BackupRepository(db, volumeCache.workDir) }
    val updater by lazy { Updater(this) }
}
