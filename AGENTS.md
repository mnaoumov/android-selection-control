# android-selection-control

Android app giving **cursor and selection control where no keyboard can reach** — text that is
selectable by touch but *not* editable: a web page in a browser, a read-only view.

Tracked centrally as **the pad build** — the app itself. Its two
predecessors are closed: **the first spike** and **the gesture spike**, the NO-GO and GO
spikes respectively. Read the pad build for what to build and the gesture spike for the measured mechanism; they hold the scope,
the survey of what already exists, the risk order, and both spike results. Nothing about this project's
plan lives here — this file is build/run mechanics only.

## Status

**Feasibility spikes only — the app does not exist.** `spike/` is a throwaway diagnostic, first for the first spike
and now for the gesture spike; nothing under it is app code.

- **the first spike — NO-GO.** Chrome page text does not support `ACTION_SET_SELECTION`. Page nodes report
 `sel=false edit=false`, `performAction` returns `false`, granularity-with-extend returns `true` and
 moves nothing. Only editable nodes work.
- **the gesture spike — GO.** `dispatchGesture` needs no selection action: a synthesised long-press selects page text
 on the first try, and a synthesised drag moves the resulting handle. Handle positions come from
 `TYPE_VIEW_TEXT_SELECTION_CHANGED` (character offsets plus the event source's screen bounds) combined
 with the node tree — **no screenshot analysis required**. Measured 20/20 on the full loop.
- **The gesture half generalises; the observation half does not.** The long-press selected text on all six
 tested surfaces that have text selection (Chrome, Obsidian editing and reading, Keep, Docs, Gecko via
 Tor). But **Google Docs fires no selection event at all** while reporting `textSelectionStart/End` on its
 node — the exact opposite of Chrome — so both rungs must be implemented. Sheets selects *cells*, not
 text. Full matrix in `the gesture spike's notes`.
- **Distribution is the open problem, and Play is the answer to both halves of it.** On OxygenOS 16 a
 sideloaded accessibility service simply has no enable toggle, and neither the UI nor adb's `appops` can
 lift the block; ECM keys on install provenance, so only a trusted install source clears it. Play policy
 itself is not the obstacle the first spike assumed — it "permits the use of the AccessibilityService API across a
 wide range of applications", with a declaration-and-disclosure lane for apps that are not
 `isAccessibilityTool`. See `the gesture spike's notes` for the policy read and the precedent.

## The intended product (decided 2026-08-30)

An **always-on selection pad** — cursor buttons that extend the selection the way a desktop keyboard does
(`Shift+Right`, `Ctrl+Shift+Right`, `Ctrl+Shift+PageDown`, `Ctrl+Shift+End`, and the Left / PageUp / Home
mirrors). It is a **closed loop**: each press reads the current selection, moves it one step, reads again.

**The pad is an accessibility overlay, NOT an `InputMethodService`** — an IME only appears when an editable
field has focus, so it could never show over a browser page. Use `attachAccessibilityOverlayToDisplay`
(API 34), which needs no `SYSTEM_ALERT_WINDOW`, so the zero-permission property survives.

**The granularity risk is answered (2026-09-02) — see `the pad build's notes` for the measurement.** Chrome hands
over both granularities for free, split by direction of travel: **growing** the selection moves it a whole
word at a time, **shrinking** it moves one character at a time. Neither needs a character of text read. The
open part is now handle *location* outside Chrome: Obsidian's block-width node bounds put the derived
handle nowhere near the real one, and the loop cannot close there.

## Why an AccessibilityService and not a keyboard

An `InputMethodService` reaches its target through `InputConnection`, which exists **only** where an
editable field has focus. A browser page has none, so no IME is ever invoked there — which is why every
existing solution (Gboard's editing pad, SwiftKey, CleverKeys, Hacker's Keyboard) stops at the same
boundary.

The first spike showed `AccessibilityService`'s *selection actions* stop at that **same** boundary. The gesture spike showed
`dispatchGesture` does not: it drives the target app's own selection UI, so the reach becomes "anywhere
the user can already select by touch" rather than "anywhere there is an editable buffer".

## Gotchas that will mislead you if you do not know them

**`getActionList` lies, and so does a `true` return.** Native read-only `TextView`s in
`com.android.settings` advertise `ACTION_SET_SELECTION`, and `performAction` on them **returns `true`** —
while producing no selection at all (`textSelectionStart/End` stays `-1..-1`, nothing on screen). Never
treat an advertised action, or a `true` return, as evidence that anything happened.

**`refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)` lies the same way for page content.** It
returns `true` with a correctly-sized array in which every `RectF` is just the whole node's bounds. The
control: on Chrome's own editable omnibox it returns real per-character rects. Do not build on it for
non-editable text.

**A selection event is a change notification, not a state query.** `TYPE_VIEW_TEXT_SELECTION_CHANGED` only
fires when the range CHANGES — re-selecting the same word is silent — and nothing lets you *ask* what is
currently selected, because `textSelectionStart/End` stays `-1` on page nodes. Track it from the stream.

**`from`/`to` are ANCHOR and FOCUS, not min and max.** Drag the *start* handle below the anchor and they
arrive reversed — `from=27 to=19`. Taking `from` as the left edge therefore derives the RIGHT handle's
pixel, so each step grabs the wrong handle and the selection flips end over end; measured 2026-09-02 as six
straight oscillating steps before the cause was visible. Normalise with `min`/`max` everywhere.

**Long-pressing inside an existing selection does not re-select.** The range never changes, so no event
fires, and anything waiting on the announcement is left with nothing to work from. Clear the selection with
a tap somewhere neutral before a long-press that is meant to start over.

**A drag that misses the handle is not a no-op — it is a tap on the page.** Lost-handle drags logged
`TYPE_VIEW_CLICKED` and, once, followed a link and navigated the page out from under the run. An app must
not dispatch a drag when it does not know where the handle is.

**The announced offsets are LOCAL to the event's source node.** When a selection grows past a node's edge
the source switches to the newly-covered node and the offsets restart from it — the run below stepped
`…46, 47, 48` on a 48-character node and then reported `0..4` on the next one. There is no document-wide
offset anywhere in this mechanism.

**A block-level node's `getBoundsInScreen` is the block box, not the text box.** Long-pressing the centre
of an `h1` whose bounds run 56..1218 but whose glyphs end at 547 hits empty space and selects nothing.
Inline nodes hug their text; block nodes do not.

**Long-pressing a link opens Chrome's link context menu**, not a selection.

**`screencap` returns pure black on `FLAG_SECURE` apps** — Tor Browser is one. The "only a screenshot is
evidence" rule cannot be honoured there, so corroborate with the selection event plus the floating
action-mode window and say which evidence you actually have.

**Handle geometry is per-app.** Chrome draws teardrops below the baseline; Google Keep draws circles at
the selection's top-left and bottom-right. Do not hardcode one offset pair.

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

Enable the service. **The by-hand route does not exist on this ROM** (the gesture spike): a sideloaded service gets no
master toggle under Settings → Accessibility → Downloaded apps, this OxygenOS build offers no "Allow
restricted settings" affordance, and `adb shell appops set … ACCESS_RESTRICTED_SETTINGS allow` fails
because uid 2000 lacks `MANAGE_APP_OPS_MODES`.

The guard is **ECM**, which protects exactly one setting today
(`AppOpsManager.OPSTR_BIND_ACCESSIBILITY_SERVICE`) and keys on install provenance: an `adb install` leaves
`installerPackageName=null` / `initiatingPackageName=com.android.shell`, which ECM treats as sideloaded.
Note the appop reads `default` for restricted and unrestricted apps alike — that is `ECM_STATE_IMPLICIT`,
"infer from install source", so it is not a usable signal. So adb is the only working path:

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
adb -s $d shell am broadcast -a dev.mnaoumov.asc.spike.CMD --es cmd "'<command>'"
adb -s $d logcat -s ASCSPIKE:I -d
```

**The inner quotes are load-bearing for any multi-word command.** `adb shell` joins its arguments with
spaces and hands the result to the device's shell, which splits them again — so `--es cmd "nodelong 63"`
arrives as `cmd=nodelong` with `63` taken as `am`'s package argument, the broadcast goes to a package
named `63`, and **nothing is logged at all**. The failure looks exactly like a dead service. Quote for the
device shell as well, as above.

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

#### Gesture commands (the gesture spike)

`dispatchGesture` synthesises touch, which needs no selection action at all — it drives the target
app's *own* selection UI. Coordinates are **physical screen pixels** (`adb shell wm size`; the
OnePlus 15 is 1272x2772).

| Command | Meaning |
|---|---|
| `tap <x> <y>` | A ~60 ms touch |
| `long <x> <y> [ms]` | A touch held in place, default 700 ms (the platform long-press timeout is 500 ms) |
| `drag <x1> <y1> <x2> <y2> [ms]` | Touch down, move, lift — for moving a selection handle that already exists |
| `pressdrag <x1> <y1> <x2> <y2> [holdMs] [dragMs] [settleMs]` | Long-press then drag **without lifting** — the gesture a user makes to select a phrase |
| `nodetap <i>` / `nodelong <i> [ms]` | The same at the centre of node `<i>`'s `getBoundsInScreen`, so coordinates come from the tree instead of guesswork |
| `sel` | Every node reporting a selection range (`textSelectionStart/End`) or `isTextSelectable` |
| `events [n]` | The last `n` accessibility events — type, package, class, `from`/`to`/`count`, scroll — from a 300-entry ring buffer filled by `onAccessibilityEvent`. Deliberately records no text |

`pressdrag` is **three chained dispatches**, not one gesture: `continueStroke` produces a stroke for
the *next* gesture and keeps the pointer down between them, so the hold, the drag and the settle are
fired one from the previous one's callback. Its parts are logged as `[1/3 hold]`, `[2/3 drag]`,
`[3/3 settle]`; if the chain breaks, the missing part says where.

#### Granularity-probe commands (the pad build)

| Command | Meaning |
|---|---|
| `selstate` | Both observation rungs as one state: the **last announced** `SELECTION_CHANGED` (offsets, source bounds, srcLen, age) plus a node-rung scan for any `textSelectionStart/End`, and the handle pixels derived from them |
| `probe <x> <y> <dx> <count> [settleMs]` | Walks a handle from `(x,y)` in `count` drags of `dx` px, each starting where the last ended, logging the announced offsets after each step |
| `nudge <start\|end> <dx> [settleMs]` | One **closed-loop** step: derive the handle from `selstate`, drag it `dx` px, re-read. The pad's kernel |
| `servo <start\|end> <dx> <count> [settleMs]` | `nudge` repeated — the closed-loop walk, and the instrument that actually measures granularity |
| `draghold <x1> <y1> <x2> <y2> [dragMs] [holdMs]` | Drag then **hold without lifting** (two chained strokes), for the edge auto-scroll that plain `drag` can never trigger |

Read either walk's output as a **sequence of offsets**, not of pixels: values landing inside a word
prove character granularity; values that only ever sit on word boundaries, with several silent steps
between them, prove the app snaps. **A step logging `NO EVENT` is a measurement, not a gap** — the
framework announces changes only, so an unchanged range is silent.

**Use `servo`, not `probe`, to measure.** `probe` is open-loop in pixels, which was the original
idea — re-deriving the handle each step could in principle let the servo hide the snapping being
measured — but it does not survive contact: **a handle does not stay under the pixel the last drag
lifted at.** Measured 2026-09-02, it held for one step, went silent for eight while the finger
crossed the next node, and from step 10 was producing `TYPE_VIEW_CLICKED` and collapsed
`from == to` events, i.e. it had lost the handle and was tapping the page. `servo` re-derives the
handle from the announced range each step and is also what the pad's buttons will do.

**A gesture run is only valid while the target app stays foregrounded.** A run whose steps go silent
partway is far more likely to have been switched away than to have measured anything: check
`adb shell dumpsys activity activities | Select-String topResumedActivity` before believing a dead
sequence. One notification tap cost a whole 25-step run this way.

Handle geometry is derived by interpolating the offset across the source node's bounds, then
`HANDLE_INSET` px outside the end and `HANDLE_DROP` px below the text — 30 and 57, the gesture spike's measured
Chrome numbers. They are **Chrome's**; Keep draws circles at the selection's corners instead.

### Gotchas

- **A `performAction` returning `true` is not proof of anything the user can see.** Always pair it with
 `adb -s $d exec-out screencap -p > shot.png`.
- **The same holds for a gesture's `onCompleted`** — it means the strokes were played, not that the
 target app did anything with them. `dispatchGesture` returning `true` means only "accepted for
 dispatch". Screenshot, every time.
- logcat truncates a single entry near 4 KB. The spike already emits one line per entry to dodge this —
 do not "tidy" that into one big log call.
- The phone must be **unlocked**; a locked screen shows only a `com.android.systemui` window and every
 dump comes back empty.

## Never touch

`emulator-5554` / the `obsidian_test` and `obsidian_screenshots` AVDs belong to the Obsidian projects'
integration suites, which are worked in other sessions. This project uses a physical device.
