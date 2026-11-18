# Motion Downloader for Android

Phone version of the desktop Motion Downloader: paste a link, **Analyze**, pick single video /
playlist videos / whole playlist, choose MP4 quality or MP3/M4A, optional subtitles, **Download**.
Powered by yt-dlp + ffmpeg (bundled via the `youtubedl-android` library), UI in Kotlin + Jetpack Compose.

Files are saved to **Downloads/MotionDownloader** by default; use **Save to -> Browse** to pick any folder (playlists get their own sub-folder).
Use **Share -> Motion Downloader** in the YouTube app or a browser: a download pop-up (Music / Video options with sizes) slides up over that app, and the download runs in the background. "More formats" opens the full app (playlists, subtitles, all qualities).

Differences from the desktop app
- "Browser cookies" does not exist on Android -> import a `cookies.txt` file instead (for sign-in / age-restricted videos).
- Downloads keep running in the background with a progress notification.

## Get the APK (pick one)

### A) GitHub Actions (no Android Studio needed)
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab -> *Build APK* -> **Run workflow** (it also runs on every push).
3. When it finishes (~5 min), open the run and download the **MotionDownloader-apk** artifact -> unzip -> `app-release.apk`.

### B) Android Studio
1. Install Android Studio, choose **Open** and select this folder, let Gradle sync.
2. **Build -> Build Bundle(s)/APK(s) -> Build APK(s)**, or run:  `gradle assembleRelease`
3. APK: `app/build/outputs/apk/release/app-release.apk`

## Install on the phone
Copy the APK to the phone, open it, and allow "Install unknown apps" for your file manager/browser when asked.
The APK is signed with the debug key; use your own signing config in `app/build.gradle.kts` for publishing.

## Notes
- First launch unpacks Python/ffmpeg (a few seconds). APK size is large (~80-100 MB) because it contains them.
- Sites change often. To ship a newer yt-dlp, bump `val ytdl = "0.18.1"` in `app/build.gradle.kts` to the latest
  `io.github.junkfood02.youtubedl-android` version and rebuild.
- Only download content you have the right to download.

## Updates, folders and versions
- Every GitHub build is versioned `1.0.<run number>` and published as a **Release** with the APK attached.
  The installed app checks the newest release (on launch and twice a day in the background) and shows an
  "Update available" notification plus an in-app **Download & install** button. The repo must be **public**.
- Files are saved to `Downloads/MotionDownloader/<Site>/<Video|Audio>/` (e.g. `.../Facebook/Video`, `.../YouTube/Audio`).
- The signing key is `app/release.jks` (kept in the repo so new builds can update old installs). Replace it
  with your own private key before publishing anywhere public.
- **New design** (Home / Downloads / Settings): paste a link on Home, pick Video/Audio, quality and subtitles, then Download. Downloads go into a queue.
- **Downloads** tab: filters All / Downloading / Pending / Done, Pause and Resume, Cancel, Retry, Play, clear finished. The list is saved on the phone.
- **Internet drops?** The download is not failed. It shows "Waiting for internet…" and continues from where it stopped when the connection is back.
- **Built-in player**: tap Play on a finished download (or its notification) to watch or listen inside the app. Rotate the phone for full screen. Turn it off in Settings > Playback to use another player app.
- **Browser** tab: browse any website inside the app. When a video is open on the page, a small download button appears: tap it to pick quality and save. File links (PDF, APK, ZIP ...) open a "Download file!" pop-up (name, size, location, Wi-Fi only, Retry; ADD / CANCEL / START). Those downloads appear in the Downloads tab and go to Downloads/MotionDownloader/Files.
- Browser extras: pull down at the top of a page to reload; the clock button shows your browsing history (saved on the phone; remove one or clear all).
- **New version pop-up**: when a newer release exists, the app shows "New version available" with Download now (opens the GitHub repo) and Cancel.
- **Settings**: default quality, format and subtitle language; Wi-Fi only; simultaneous downloads (1-3); save location; quick share pop-up on/off; theme; cookies; update the app and yt-dlp.

## Owner controls
See OWNER_GUIDE.md: remote.json (force update, announcement, maintenance, feature switches) and optional Firebase (analytics, crash reports, push).
