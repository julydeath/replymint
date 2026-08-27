# Dev Setup

## Prerequisites (already present on this machine)

- **JDK 17** (Temurin) — `java -version`
- **Android SDK** at `~/Library/Android/sdk` — compileSdk **36**, build-tools 35/36/37
- **adb** — `~/Library/Android/sdk/platform-tools/adb`
- **Node 22** — for the backend

## Android app

The project uses the Gradle wrapper (`./gradlew`). AGP + Kotlin are resolved on first sync.

```bash
cd android
./gradlew assembleDebug          # build a debug APK
```

Open `android/` in Android Studio to sync and run on an emulator/device. `local.properties` points
Gradle at the SDK:

```
sdk.dir=/Users/<you>/Library/Android/sdk
```

### Grant the two permissions (required for the bubble to work)

On first launch, `MainActivity` walks the user through:

1. **Display over other apps** — `Settings → Apps → ReplyMint → Display over other apps`
   (or the system prompt from `ACTION_MANAGE_OVERLAY_PERMISSION`).
2. **Accessibility** — `Settings → Accessibility → ReplyMint → On`.

Without both, the bubble cannot appear or read/write. This is expected and explained in onboarding.

### Run on a device

```bash
~/Library/Android/sdk/platform-tools/adb devices          # confirm a device/emulator
cd android && ./gradlew installDebug                       # install
```

Then open WhatsApp/Gmail, tap the bubble, and try the three actions.

## Backend

```bash
cd backend
cp .env.example .env            # add ANTHROPIC_API_KEY
npm install
npm run dev                     # starts on http://localhost:8787
```

Point the app at the backend by setting `BASE_URL` in
`android/app/src/main/java/com/replymint/net/ReplyClient.kt` (or via a build config field) to your
machine's LAN IP for a physical device, or `http://10.0.2.2:8787` for the emulator.

## Handy commands

```bash
cd android && ./gradlew assembleDebug          # build APK
cd android && ./gradlew installDebug           # build + install
~/Library/Android/sdk/platform-tools/adb logcat -s ReplyMint   # app logs
cd backend && npm run dev                       # backend
```
