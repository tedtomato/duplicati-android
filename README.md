# Duplicati Restore for Android

An Android app that restores files from a [Duplicati](https://duplicati.com) backup stored on a
WebDAV server. You browse your backup on the phone, pick the files you need and download them,
without a computer.

The app is **restore-only**. It never writes to, renames or deletes anything on the server.

## Features

- Connect to any WebDAV server with a URL, username and password.
- See every backup version, newest first.
- Browse a version's folders, search by name, and select files or whole folders.
- Restore them to a folder of your choice or to Downloads, keeping the original folder layout.
- Every restored file is checked against the backup's own checksums before it is saved:
  - each data block,
  - each downloaded volume,
  - the whole file (size and SHA-256).

  A file that fails a check is never saved.
- Before a restore starts, it shows how much will be downloaded, and warns you on mobile data.
- Restores keep running in the background, with a progress notification.
- Downloaded volumes are cached, still encrypted, so later restores can reuse them. You set the
  cache limit.
- Your WebDAV password and backup passphrase are stored encrypted on the device and unlocked with
  your fingerprint, face or screen lock.
- Quick restore: when the server supports partial downloads (HTTP Range), only the needed parts of
  each volume are downloaded, so restoring a small file doesn't cost a whole volume.
- Light and dark themes. Works on phones and tablets.
- The app updates itself from this repository's GitHub releases.

## Requirements

- Android 8.0 (API 26) or later.
- A Duplicati 2 backup that uses:
  - **zip** compression (the default),
  - Duplicati's built-in **AES-256 encryption** (files ending in `.zip.aes`),
  - a WebDAV destination reachable over **HTTPS**.

  Backups with other setups (`.7z`, `.gpg`, unencrypted) are detected and rejected with a message.

## Installing

1. On your phone, open the [latest release](../../releases/latest) and download the APK file.
2. Open it and allow your browser to install apps when Android asks.

After that, the app offers each new version by itself, either as a banner on the versions screen or
under Settings › Updates.

## Using the app

1. **Set up:** enter your details, then confirm with your fingerprint, face or screen lock.
   - The WebDAV URL of the folder that holds the backup files. This is the same folder Duplicati
     writes to, e.g. `https://example.com/backups/my-pc/`.
   - Your username, password and backup passphrase.
   - The file prefix (default `duplicati`).

   Tap **Test connection** to check that the app can list the folder and decrypt the backup. It
   also tells you whether quick restore is available on your server.
2. **Versions:** the app reads the backup's index and lists the versions. The first sync downloads
   all index files, which takes a moment. Later syncs only fetch new ones.
3. **Browse:** open a version, then navigate the folders or search. Tick the files and folders you
   want, then tap **Restore**.
4. **Restore:** check the summary, choose where to save, and start. When it finishes, each file is
   listed as OK or failed, and you can open the restored files from there.

If something goes wrong, Settings › Diagnostics › Show log has the details and a Copy button.

## Security

- **Use a read-only account.** If your WebDAV server supports it, create a read-only account limited
  to the backup folder and use that in the app.
- **Restored files are not encrypted** once they are on the device.
- **Saved secrets** are encrypted with a key kept in Android's Keystore (in the phone's secure
  hardware where available). The key can't be exported and only works right after you unlock with
  your fingerprint, face or screen lock. Android limits wrong attempts. On a device without a screen
  lock, nothing is saved and you enter the password and passphrase each time.
- **Quick restore** reads volumes partially, so the checksum of the whole volume can't be checked for
  those. Every block and every restored file is still checked against the backup. You can turn quick
  restore off in Settings.
- The app opts out of Android cloud backup and device-to-device transfer, so its data stays on the
  device.

## Limitations

- If the server doesn't support partial downloads, whole data volumes are downloaded (Duplicati's
  default is 50 MB each), even to restore a small file.
- An interrupted restore starts again from the beginning.
- WebDAV is the only storage type supported for now.

## Building from source

Open the project in Android Studio and run the `app` configuration. Alternatively, build from the
command line:

```bash
./gradlew assembleDebug
```

Debug builds install next to the release app with the package suffix `.debug`.

The GitHub Actions workflow (`.github/workflows/build.yml`) builds the app and runs the unit tests on
every push. Each run has the debug APK attached as the `debug-apk` artifact.

### Code layout

`app/src/main/java/io/github/tedtomato/duplicatirestore/`:

| Package | Contents |
|---|---|
| `webdav` | Read-only WebDAV client (PROPFIND and GET) |
| `crypto` | Streaming decryptor for the AES Crypt format (versions 2 and 3) used by Duplicati |
| `format` | Parsers for Duplicati's remote file names, manifest, file lists (`dlist`) and indexes (`dindex`) |
| `index` | Local Room database of versions, files, folders and block locations |
| `sync` | Reads the remote backup into the local index |
| `restore` | Plans and runs restores, checks every block and file, saves the results |
| `cache` | Size-limited cache of downloaded (encrypted) volumes |
| `data` | Settings, the PIN-protected secret store and the diagnostics log |
| `update` | Self-update from GitHub releases |
| `ui` | Jetpack Compose screens |
