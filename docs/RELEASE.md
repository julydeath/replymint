# Building & sharing a release APK

## One-time setup: the release keystore

Every release APK must be signed with the same keystore. **This file IS the app's identity** —
when we publish to Google Play later, the same key must sign every update. If it is lost there is
no recovery: the Play listing would have to be recreated from scratch under a new package.

Generate it once (from the repo root):

```bash
keytool -genkeypair -v -keystore android/replymint-release.jks -alias replymint -keyalg RSA -keysize 2048 -validity 10000
```

Answer the prompts (name/org can be anything; remember the passwords). Then create
`android/keystore.properties` (both files are gitignored — verify with `git status`):

```properties
storeFile=replymint-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=replymint
keyPassword=YOUR_KEY_PASSWORD
```

> **Back up `replymint-release.jks` + the passwords now** — a password manager entry and a copy
> in personal cloud storage. Do not commit either file.

The SHA-1 fingerprints are needed for Google Sign-In's OAuth clients:

```bash
keytool -list -v -keystore android/replymint-release.jks -alias replymint | grep SHA1
```

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
```

## Building

1. Bump `versionCode` (and `versionName` if user-visible) in `android/app/build.gradle.kts`.
2. Build:

```bash
cd android && ./gradlew assembleRelease
```

3. Output: `android/app/build/outputs/apk/release/app-release.apk`
4. Verify the signature:

```bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs android/app/build/outputs/apk/release/app-release.apk
```

Release builds talk to the production backend (`https://replymint-um33.onrender.com`) over HTTPS;
cleartext HTTP stays debug-only.

## Sideload instructions for testers

Send the APK (WhatsApp/Drive/etc.) with these steps:

1. Open the APK from your file manager or the chat attachment.
2. Android will warn about installing from an unknown source — tap **Settings** and allow
   "Install unknown apps" for that one app, then go back and tap **Install**.
3. Open ReplyMint, sign in with Google, and follow the setup (overlay + accessibility permissions).
4. Open WhatsApp / Instagram / Gmail / LinkedIn — the mint bubble appears on chat screens with a
   reply box. Tap it → **Auto Reply** to get a draft.

Known beta quirks:

- **First draft after a while can be slow (~1 min)** — the free-tier backend spins down when idle.
  Opening the ReplyMint app first warms it up.
- Testers must be added as test users on the Google OAuth consent screen while it is in Testing
  mode, or their sign-in will be blocked.
