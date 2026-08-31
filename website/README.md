# ReplyMint landing page

Static, single-file site: `index.html`. No build step, no external assets beyond Google Fonts.

What's on it: OS-aware download CTA, live animated demos (Mac Mail dictation + assistant,
persona switcher with 6 professional/personal scenarios, 3 Android phone demos lifted from
the approved mockup in `mockups/index.html`), the 18-app compatibility grid, a comparison
table vs Wispr Flow / built-in dictation / Grammarly, privacy section, and a 3-platform
download section (Mac live · Android beta-by-request · Windows waitlist).

## Download link (Mac)

The download buttons point to:

```
https://github.com/julydeath/replymint/releases/latest/download/ReplyMint-macOS.dmg
```

`releases/latest/download/<asset>` always resolves to the newest release, so the site never
needs updating for a new version — but the DMG must be uploaded to each release under the
**exact asset name** `ReplyMint-macOS.dmg`:

```
gh release create v0.1.0 --title "ReplyMint 0.1.0" \
  "desktop/src-tauri/target/release/bundle/dmg/ReplyMint_0.1.0_aarch64.dmg#ReplyMint-macOS.dmg"
```

(The `path#name` syntax renames the asset on upload.)

## Android beta flow

The Android card intentionally does **not** link an APK. Google sign-in only works for
accounts added as OAuth test users while the consent screen is in Testing mode, so the
button opens a prefilled email to `hello@replymint.app` requesting access. Flow: add their
Google account as a test user → send them the APK (`android/app/build/outputs/apk/release/`).

When the consent screen is published (or the Play listing ships), swap the button for a
direct link — upload the APK to the release as `ReplyMint-Android.apk` and use
`releases/latest/download/ReplyMint-Android.apk`.

**Check the contact address**: the site uses `hello@replymint.app` (beta requests, Windows
waitlist, footer). If that inbox isn't live, search-and-replace it with a real one.

## Hosting

Any static host works. Two zero-config options:

- **GitHub Pages**: repo Settings → Pages → deploy from branch, folder `/website`
  (requires the repo to be public, which it is).
- **Render static site**: New → Static Site → publish directory `website`.
