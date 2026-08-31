# android-selection-control

Android app giving **cursor and selection control where no keyboard can reach** — text that is
selectable by touch but *not* editable: a web page in a browser, a read-only view.

Tracked centrally as **the first spike**, **closed 2026-08-30** — a private tracker. Read
that file first; it holds the scope, the survey of what already exists, the risk order, and the spike
result that closed it. Nothing about this project's plan lives here — this file is build/run mechanics
only.

The task closed because its premise was falsified, **not** because the goal was met — see Status below.
Any successor work needs a new `T<n>-this project`.

## Status

**Feasibility spike only — and it came back NO-GO.** The app does not exist. `spike/` is a throwaway
diagnostic that answered the first spike's risk 1; nothing under it is app code.

Measured 2026-08-30 on a OnePlus 15 (Android 16, Chrome 151): **Chrome page text does not support
`ACTION_SET_SELECTION`.** Page nodes report `sel=false edit=false` and advertise only the granularity
actions; `performAction(ACTION_SET_SELECTION)` returns `false`; granularity-with-extend returns `true`
but moves no selection. Only editable nodes work. Full evidence and the controls that make the negative
trustworthy are in `the first spike's notes` — read that before proposing anything built on these actions.

## Why an AccessibilityService and not a keyboard — and why that reasoning turned out incomplete

An `InputMethodService` reaches its target through `InputConnection`, which exists **only** where an
editable field has focus. A browser page has none, so no IME is ever invoked there — which is why every
existing solution (Gboard's editing pad, SwiftKey, CleverKeys, Hacker's Keyboard) stops at the same
boundary.

The spike showed `AccessibilityService`'s *selection actions* stop at that **same** boundary: they drive
a real selection only where an editable text buffer exists. The route reaches more apps, not more kinds
of text. What is still untested, and what the finding points at, is `dispatchGesture` — synthesising the
long-press-and-drag that drives the app's own selection UI, which needs no selection action at all.

## Gotcha that will mislead you if you do not know it

**`getActionList` lies.** Native read-only `TextView`s in `com.android.settings` advertise
`ACTION_SET_SELECTION`, and `performAction` on them **returns `true`** — while producing no selection at
all (`textSelectionStart/End` stays `-1..-1`, nothing on screen). Never treat an advertised action, or a
`true` return, as evidence that anything happened. Only a screenshot is evidence.

## The no-INTERNET property

`spike/app/src/main/AndroidManifest.xml` declares **no permissions at all**, deliberately, from the
first build. Verify it on any built APK rather than trusting the manifest:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\aapt2.exe" dump permissions <path-to.apk>
```

A package line with nothing under it is the pass condition.

## Toolchain

Nothing is installed system-wide; the Gradle wrapper supplies Gradle (pinned by SHA-256 in
`spike/gradle/wrapper/gradle-wrapper.properties`).

| | |
|---|---|
| JDK | Android Studio's JBR — `C:\Program Files\Android\Android Studio\jbr` (21.0.10). **Must be set as `JAVA_HOME`**; there is no JDK on `PATH`. |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` (already in `ANDROID_HOME`). Platform android-36.1, build-tools 36.1.0 / 37.0.0. |
| Gradle | 9.7.1 via the wrapper |
| AGP | 9.3.2 — **has built-in Kotlin support**, so `org.jetbrains.kotlin.android` must NOT be applied; adding it fails the build with an explicit error. |

`cmdline-tools` is **not** installed, so there is no `sdkmanager` / `avdmanager`. AVDs are managed
through Android Studio's Device Manager.

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Push-Location spike
.\gradlew.bat:app:assembleDebug
Pop-Location
# -> spike\app\build\outputs\apk\debug\app-debug.apk
```

## Run the spike

The spike is driven **entirely over adb**, so the target app stays foregrounded and no UI has to be
built. `MainActivity` exists only to give a launcher entry and a shortcut to the Accessibility settings.

```powershell
$d = "<device-serial>" # adb devices -l
adb -s $d install -r spike\app\build\outputs\apk\debug\app-debug.apk
```

Enable the service — either by hand under Settings → Accessibility, or over adb (faster, and it does
**not** exercise the restricted-settings block that a hand-enable would):

```powershell
$orig = (adb -s $d shell settings get secure enabled_accessibility_services).Trim # SAVE THIS
$ours = "dev.mnaoumov.asc.spike/dev.mnaoumov.asc.spike.SpikeAccessibilityService"
adb -s $d shell settings put secure enabled_accessibility_services "$orig`:$ours"
adb -s $d shell settings put secure accessibility_enabled 1
```

To restore afterwards, put the saved `$orig` back — do not blank the setting, or you disable whatever
accessibility services the owner actually relies on.

### Commands

```powershell
adb -s $d logcat -c
adb -s $d shell am broadcast -a dev.mnaoumov.asc.spike.CMD --es cmd "<command>"
adb -s $d logcat -s ASCSPIKE:I -d
```

| Command | Meaning |
|---|---|
| `windows` | List accessibility windows (needs `flagRetrieveInteractiveWindows`) |
| `dump [package]` | Depth-first walk of the active window; `package` filters what is printed, never how nodes are indexed |
| `node <i>` | Full detail on one node: action list, granularities, current selection range |
| `focus <i>` | `ACTION_ACCESSIBILITY_FOCUS` — usually required before the granularity actions will do anything |
| `select <i> <start> <end>` | `ACTION_SET_SELECTION` with start/end arguments |
| `clear <i>` | `ACTION_SET_SELECTION` with no arguments (documented as "clears the selection") |
| `gran <i> <granularity> <extend> [forward]` | `ACTION_NEXT`/`PREVIOUS_AT_MOVEMENT_GRANULARITY`. Granularity is the raw mask: 1 CHAR, 2 WORD, 4 LINE, 8 PARAGRAPH, 16 PAGE |
| `copy <i>` | `ACTION_COPY` |

Node indices are depth-first positions, and every command **re-walks the tree** rather than holding
node references, so an index from `dump` stays valid for the commands that follow it (as long as the
screen has not changed).

### Gotchas

- **A `performAction` returning `true` is not proof of anything the user can see.** Always pair it with
 `adb -s $d exec-out screencap -p > shot.png`.
- logcat truncates a single entry near 4 KB. The spike already emits one line per entry to dodge this —
 do not "tidy" that into one big log call.
- The phone must be **unlocked**; a locked screen shows only a `com.android.systemui` window and every
 dump comes back empty.

## Never touch

`emulator-5554` / the `obsidian_test` and `obsidian_screenshots` AVDs belong to the Obsidian projects'
integration suites, which are worked in other sessions. This project uses a physical device.
