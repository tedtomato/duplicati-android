# Duplicati Restore (Android)

A sideloaded Android app that restores files from a Duplicati backup on a Hetzner Storage Box over
WebDAV. It is **restore-only**: it never writes to, renames or deletes anything on the storage.

- Set up an account: WebDAV URL, username, password, backup passphrase and file prefix.
- The password and passphrase are stored on the device, encrypted and unlocked with a 4-digit PIN.
- Pick a backup version, then browse, search and multi-select its files and folders.
- Restore them onto the phone. Every block, every downloaded volume and every rebuilt file is checked
  against the SHA-256 values recorded in the backup. A file is only saved after it passes.
- The app updates itself from this repository's GitHub releases.

Supported: Duplicati backups with zip compression and Duplicati's built-in AES encryption
(`.zip.aes`), on WebDAV. Other setups (`.7z`, `.gpg`) are rejected with a clear message.

## Security notes

- **Use a read-only sub-account.** Create a read-only Storage Box sub-account limited to the backup
  folder. Its host is `uXXXXXX-subN.your-storagebox.de` and its root is the shared folder, so the URL
  path is shorter than with the main account.
- **Restored files are unencrypted** on the device.
- **Saved secrets:** the password and passphrase are encrypted twice. The inner layer is AES-GCM with
  a key derived from the PIN (PBKDF2-HMAC-SHA256, 200,000 iterations). The outer layer is AES-GCM
  with a non-exportable Android Keystore key. Five wrong PINs in a row erase them. A 4-digit PIN has
  only 10,000 values, so this protects against casual access, not against someone who has rooted the
  phone.
- The app opts out of Android cloud backup and device transfer.

## Building

Open the folder in Android Studio and run the `app` configuration. Debug builds get the package
suffix `.debug` and install next to the release app, because they are signed with a different key.

The GitHub Actions workflow (`.github/workflows/build.yml`) builds the debug APK and runs the unit
tests on every push. The debug APK is attached to each run as the `debug-apk` artifact.

## Releases and updates

The app checks `https://api.github.com/repos/tedtomato/duplicati-android/releases/latest`. It reads
that release's `update.json` (`versionCode`, `versionName`, `sha256`) and installs its APK with
Android's PackageInstaller once the user confirms. Android only accepts the update if it is signed
with the same key as the installed app, so all releases must be signed with one release key.

### One-time setup: the release key

On the PC (Android Studio's JDK has `keytool`), in PowerShell:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore release.jks -alias release -keyalg RSA -keysize 4096 -validity 36500
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
```

On GitHub, go to the repository's Settings › Secrets and variables › Actions and add four secrets:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the clipboard contents from above |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password |
| `RELEASE_KEY_ALIAS` | `release` |
| `RELEASE_KEY_PASSWORD` | the key password (the same as the keystore password if you pressed Enter) |

Keep `release.jks` and its password safe. If they are lost, users have to uninstall the app and
install a release signed with a new key.

### Publishing a version

1. Bump `appVersionCode` and `appVersionName` in `app/build.gradle.kts`. Never reuse a version
   that has already been delivered.
2. Commit and push, then either push a tag `v<versionName>` (e.g. `v0.2`) on that commit, or run
   the Build workflow by hand (Actions › Build › Run workflow) with "Publish a signed release" ticked.
3. The workflow builds the signed APK and publishes a GitHub release `v<versionName>` containing
   `DuplicatiRestore-<version>.apk` and `update.json`. It refuses to publish a version that already
   has a release.

Install the first release by opening its APK on the phone. After that, the app offers each new
release by itself (Settings › Updates, or a banner on the versions screen).

## Code layout

`app/src/main/java/io/github/tedtomato/duplicatirestore/`:

| Package | Contents |
|---|---|
| `webdav` | Read-only WebDAV client: PROPFIND Depth 1, and GET with an optional Range header |
| `crypto` | Streaming AES Crypt v2/v3 decryptor |
| `format` | Remote file names, manifest, `filelist.json` (streaming), dindex, base64 and path helpers |
| `index` | Room database: versions, files, folders, block locations, volumes, blocklists, restore results |
| `sync` | Folder listing, dindex import, dlist loading, "Test connection" |
| `restore` | Planning, whole-volume restore with all checks, foreground WorkManager job, saving files |
| `cache` | LRU cache of encrypted volumes |
| `data` | Settings, PIN-protected secret store, in-memory session, diagnostics log |
| `update` | GitHub-release self-update |
| `ui` | Compose screens: setup, PIN unlock, versions, browser, restore, settings |

The backup format logic is ported from `duplicati_verify.py`, which restored real files from the
backup. The unit tests (`app/src/test`) decrypt AES Crypt v2 and v3 files that were made by a
separate Python implementation of the spec.

## Status

Milestone 1 (whole-volume downloads) is implemented. Partial downloads with HTTP Range (milestone 2)
and resuming an interrupted restore are not yet done.
