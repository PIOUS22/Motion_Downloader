# Instant "new version" notification (Firebase) - step by step

You do this once. After that every release sends a notification to all users, even when the app is closed.

1. Open https://console.firebase.google.com and sign in with your Google account.
2. Click "Add project", name it Motion Downloader, continue (Analytics can stay on).
3. Click the Android icon ("Add app"). Package name: `app.mediadownloader`. Click Register app.
4. Download `google-services.json`. Put it in the `app` folder of your GitHub repo (same level as `build.gradle.kts` of the app) and commit it.
5. In Firebase: Project settings (gear) -> Service accounts -> "Generate new private key" -> save the .json file.
6. In GitHub: your repo -> Settings -> Secrets and variables -> Actions -> New repository secret.
   Name: `FIREBASE_SERVICE_ACCOUNT`. Value: open the key file in a text editor and paste ALL of it.
7. In Firebase: Build -> Cloud Messaging -> make sure "Firebase Cloud Messaging API (V1)" is enabled.
8. Upload a new version. Users must install this build once. After that, every release sends a push.

Note: google-services.json must match the package name above, and the repo is public, so it contains only
public app identifiers (no passwords). Never put the private key file in the repo - only in the GitHub secret.
