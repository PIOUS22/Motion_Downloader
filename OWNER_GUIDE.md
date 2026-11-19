# Owner guide - how to control Motion Downloader

## A. Remote control file (works now, no account needed)
The app reads `remote.json` from your repo (main branch) every time it is opened (at most every 10 minutes).
Edit the file on GitHub (open it, tap the pencil, change, Commit). Changes reach phones within a few minutes.

| Setting | What it does |
|---|---|
| `"min_version": "1.0.30"` | Phones with an older version see "Update required" and cannot continue. Leave `""` to turn off. |
| `"maintenance": {"enabled": true, "message": "Back soon"}` | Switches the app off for everyone; they see your message. |
| `"announcement": {"id": "nov-20", "title": "Hello", "message": "New features!", "url": ""}` | Shows a message once to every user. Change the `id` to show a new one. Leave `id` empty for none. `url` adds an Open button. |
| `"features": {"browser": false}` | Hides the Browser tab. Also `share_popup` and `in_app_player`. |

Keep the file valid JSON (commas and quotes). If it is broken, the app ignores it and keeps the last good settings.

## B. Firebase (usage numbers, crash reports, push messages)
1. Go to console.firebase.google.com, create a project (free).
2. Add an Android app. Package name: `app.mediadownloader`. Download `google-services.json`.
3. Upload it to the repo folder `app/` (next to `build.gradle.kts`). Without this file Firebase stays off and the app builds normally.
4. Wait for the new build and install it.

Then in the Firebase console:
- **Analytics**: users per day, and events `download_started`, `download_done`, `download_failed`.
- **Crashlytics**: crash reports.
- **Messaging > New campaign > Notification**: send a push message. Target: **Topic** `all` (every user).

## C. Releases
Every push to main builds a new APK and release. Delete a bad release on the Releases page.
