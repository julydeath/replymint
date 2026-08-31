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

## Android beta / Windows waitlist flow

The Android and Windows buttons open an email-capture modal that POSTs to
`https://replymint-um33.onrender.com/v1/beta/request` (unauthenticated, CORS-open).
Signups land in the `beta_requests` table (email, platform, created_at) in Supabase —
check it in the Supabase dashboard, or:

```sql
select * from beta_requests order by created_at desc;
```

Manual follow-up per Android signup: add their Google account as an OAuth test user →
email them the APK (`android/app/build/outputs/apk/release/`). When the consent screen
is published (or the Play listing ships), swap the modal for a direct link — upload the
APK to the release as `ReplyMint-Android.apk` and use
`releases/latest/download/ReplyMint-Android.apk`.

The footer still shows `hello@replymint.app` as a contact — replace it if that inbox
isn't live.

## Hosting

Any static host works. Two zero-config options:

- **GitHub Pages**: repo Settings → Pages → deploy from branch, folder `/website`
  (requires the repo to be public, which it is).
- **Render static site**: New → Static Site → publish directory `website`.
