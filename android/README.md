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

Requires the Android SDK (Android Studio, or the command-line tools) and JDK 17+. The
Gradle wrapper is checked in, so Gradle itself downloads on first run.

```sh
# 1. Generate the engine the APK embeds. The Gradle build fails with a clear
#    message if you skip this.
npm run build          # from the repository root

# 2. Point Gradle at your SDK — skip if ANDROID_HOME is already set, and
#    Android Studio writes this file for you when you open the folder.
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties     # macOS: $HOME/Library/Android/sdk

# 3. Build
./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

First run downloads Gradle 8.9 and the Android Gradle Plugin, so it needs network and a
few minutes. Opening `android/` in Android Studio instead does all of the above for you.

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

The app has **no launcher icon** — it isn't something you open, it's something that
appears when you select text. That's intentional, but it does mean nothing shows up on
your home screen after installing.

1. Select text in any app
2. Tap **Hemingway** in the toolbar that appears (often behind the ⋮ overflow)
3. Read the highlights; edit in place if you want
4. **Replace selection** writes your edited text back

Replace is hidden when the calling app marks the selection read-only, since there's
nowhere to write back to.

## If something goes wrong

Since this hasn't been compiled yet, here's where to look first:

| Symptom | Likely cause |
| --- | --- |
| `hemingway.html not found` | `npm run build` wasn't run from the repo root |
| `SDK location not found` | No `local.properties` and no `ANDROID_HOME` |
| **Hemingway** missing from the toolbar | Check the ⋮ overflow; some apps use a custom selection menu that suppresses `PROCESS_TEXT` entirely |
| Blank white screen | The asset didn't make it into the APK — check `copyEngine` ran, or unzip the APK and look for `assets/hemingway.html` |
| Replace button never appears | The calling app marked the selection read-only, which is expected in some fields |

`adb logcat | grep -i hemingway` is the fastest way to see what the activity is doing.

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
