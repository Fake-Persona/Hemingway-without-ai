# Hemingway for Android

Readability feedback — adverbs, passive voice, complex phrases, hard sentences — for text
you write in other apps. No keyboard replacement, and no AI.

The analysis engine is not reimplemented here. A WebView loads a bundled copy of
`hemingway.html`, the same single-file build the website ships, so the phone cannot drift
from the site.

## Status

**Tier A** — reach it from the selection toolbar, the share sheet, or by pasting. Needs no
permissions. **Confirmed working in WhatsApp on a real device.**

**Tier B** — live as-you-type feedback in a floating panel, via an accessibility service.
Needs two grants from the setup screen. This is the only route that works in apps drawing
their own selection menu, because it reads the focused field rather than waiting to be
handed text. **Compiles in CI; not yet driven on a device.**

> **Open the app once after installing.** A freshly installed Android app stays in the
> "stopped" state until opened once, and a stopped app's components are skipped when the
> system resolves intents. Until you do, the selection-toolbar entry will not appear —
> this was a real bug in the first build, caused by shipping with no launcher icon at all.

## Using it

### Without any permissions (Tier A)

**1. Selection toolbar** — the best route, and the only one that can write text back.
Select text, tap **Hemingway** in the popup (often behind the ⋮ overflow), edit, then
**Replace selection**. Works wherever the app uses Android's own selection menu: WhatsApp,
Gmail, Chrome.

**2. Share sheet** — select text, **Share**, pick **Hemingway**. For apps that draw their
own selection menu and so never show a `PROCESS_TEXT` entry. Read-only, since sharing
gives no channel back to the sender.

**3. Open the app and paste** — works from anywhere that can copy.

Arriving by route 1 or 2 opens a floating dialog over the app you were in, rather than
replacing it. The screen itself is unavoidable: `ACTION_PROCESS_TEXT` starts an Activity
by definition.

### Live feedback (Tier B)

Open the app and grant both:

1. **Display over other apps** — lets the panel float above what you are writing in
2. **Accessibility service** — lets the app read the focused text field

A small draggable panel then shows grade level and counts as you type, in any app. It is
non-focusable, so typing underneath is unaffected.

### Apps that draw their own selection menu

Some editors — **Obsidian** is the known case — render their own text-selection popup
instead of Android's. The system toolbar never appears there, so no `PROCESS_TEXT` entry
can ever be shown and **no change to this app can force one in**. Route 2 works if the app
offers Share; otherwise paste, or use Tier B, which does not depend on menus at all.

## Privacy

The only permission declared is `SYSTEM_ALERT_WINDOW`, for the floating panel. **There is
no `INTERNET` permission**, so the app cannot transmit your writing even in principle. CI
fails the build if any other permission appears.

```sh
aapt dump permissions app-debug.apk   # expect only SYSTEM_ALERT_WINDOW
```

## Getting the APK

Easiest: let CI build it. Every push produces an APK as a workflow artifact, and pushes to
`main` publish it to the rolling **latest-android** release, which installs straight from a
phone browser. No toolchain needed.

To build locally instead, you need the Android SDK and JDK 17+:

```sh
npm run build          # from the repo root — generates the engine the APK embeds
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # macOS: $HOME/Library/Android/sdk
./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`. Sideload with
`adb install -r <path>`, or open `android/` in Android Studio, which handles all of it.

## Layout

```
HomeActivity              Launcher: permission status, grants, link to the editor
ProcessTextActivity       Shows the analysis; handles PROCESS_TEXT and SEND
EditorActivity            Paste-anything fallback (same screen, no incoming text)
HemingwayAccessibilityService   Watches the focused field, feeds the panel
OverlayWidget             The floating panel; runs the engine in an offscreen WebView
```

The host talks to the page through three functions and never touches its DOM:

```js
window.hemingway.setText(text)   // push text in
window.hemingway.getText()       // read edited text back
window.hemingway.analyze(text)   // stats only, no rendering — used by the panel
```

Kotlin escapes text with `JSONObject.quote` before it crosses into JavaScript, and decodes
`evaluateJavascript`'s JSON-encoded result on the way back. That round-trip is covered by
a browser test including newlines, quotes, backslashes, unicode and a `<script>` payload.

## Open question

Whether highlights can be painted **directly over your words** in another app, rather than
listed in the panel, depends on `refreshWithExtraData()` returning per-character pixel
bounds. Expected to work for standard `TextView` fields and to fail for custom-rendered
text (Flutter, some Compose). The service logs the real answer per app:

```sh
adb logcat -s HemingwayProbe
```

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| `hemingway.html not found` | `npm run build` wasn't run from the repo root |
| `SDK location not found` | No `local.properties` and no `ANDROID_HOME` |
| **Hemingway** missing from the toolbar | Open the app once first, then check the ⋮ overflow. Try Gmail or Chrome — some apps suppress `PROCESS_TEXT` entirely |
| Blank white screen | The asset missed the APK — check `copyEngine` ran, or unzip and look for `assets/hemingway.html` |
| Replace never appears | The calling app marked the selection read-only, or you arrived via Share |
| Panel never appears | Both grants are needed; Android 17's Advanced Protection Mode blocks the accessibility API outright |
