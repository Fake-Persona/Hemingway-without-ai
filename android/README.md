# Hemingway for Android

Select text in **any** app — WhatsApp, Gmail, your notes app — and the floating
selection toolbar gains a **Hemingway** entry. Tap it to see the same highlighting the
website gives you, then optionally send the edited text back where it came from.

No keyboard replacement, no accessibility permission, **no permissions at all** — the
manifest declares none, not even `INTERNET`. The analysis runs offline in a WebView
loading a bundled copy of `hemingway.html`, the same single-file build the website ships.

## Status

Tier A of the Android plan. The source is complete but **has not been compiled or run** —
it was written in an environment with no Android SDK and no route to download one, so the
build below has not been executed. Treat the first local build as the real verification.

## Building

Requires the Android SDK (Android Studio, or command-line tools) and a JDK 17+.

```sh
# 1. Generate the engine the APK embeds. The Gradle build fails with a clear
#    message if you skip this.
npm run build          # from the repository root

# 2. Build the APK
cd android
./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

If you don't have a Gradle wrapper checked in, either open the `android/` folder in
Android Studio (it will offer to create one) or run `gradle wrapper` first.

## Installing

Sideload it — this is not intended for the Play Store:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Verify the privacy claim for yourself:

```sh
aapt dump permissions app/build/outputs/apk/debug/app-debug.apk
# should list the package and no uses-permission lines
```

## Using it

1. Select text in any app
2. Tap **Hemingway** in the toolbar that appears (may be behind the ⋮ overflow)
3. Read the highlights; edit in place if you want
4. **Replace selection** writes your edited text back

Replace is hidden when the calling app marks the selection read-only, since there's
nowhere to write back to.

## How it fits together

```
hemingway.html                    Generated at the repo root by `npm run build`
  └─ copied by the copyEngine Gradle task into the APK's assets
       └─ loaded by ProcessTextActivity over file:///android_asset/

window.hemingway.setText(text)    Host pushes the selection in
window.hemingway.getText()        Host reads the edited text back
```

The engine is **copied**, never duplicated in version control, so the phone can't drift
from the website. CI already fails if the committed `hemingway.html` is stale.

`ProcessTextActivity` escapes the selection with `JSONObject.quote` before it crosses into
JavaScript, so quotes, backslashes and newlines in your text can't break out of the string
literal. That round-trip is covered by a browser test, including a `<script>` payload.

## What this doesn't do

It's select-then-tap, not live as you type. Live feedback inside other apps needs an
`AccessibilityService` plus a `TYPE_APPLICATION_OVERLAY` widget — Tier B — which is a
much larger piece of work and can be switched off at OS level by Android 17's Advanced
Protection Mode. Tier A keeps working regardless, which is why it exists first.
