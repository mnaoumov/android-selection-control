# android-selection-control

Android app giving **cursor and selection control where no keyboard can reach** — text that is
selectable by touch but *not* editable: a web page in a browser, a read-only view.

Tracked centrally in a private tracker. Three items are closed and hold the history: **the first spike**
(NO-GO — the accessibility selection actions stop at the editable-buffer boundary), **the gesture spike** (GO —
`dispatchGesture` drives the target app's own selection UI, plus the per-app observation matrix), and
**The pad build** (the pad itself, built and measured). Read the gesture spike for the mechanism and the pad build for what the pad
does and what it costs.

Open work is split by shape, one item each: **the held-pointer fix** make a character step cost one gesture — the
latency, and the reason the pad is not enjoyable yet; **the swap-edge fix** the swap-edge path reads the wrong end;
**The button-drive work** drive page / start / end to completion; **the word-left fix** word-left and its remembered boundaries;
**The handle-location defect** a handle inside a wrapped node; **the test-rig work** make the test rig a repo asset; **the Play distribution work** Play
distribution, which ECM makes mandatory rather than optional.

Nothing about this project's plan lives here — this file is build/run mechanics only.

## Status

**The app exists as of 2026-09-03** — the repo root is the product (`app/`), and `spike/` remains a
throwaway diagnostic, first for the first spike, then the gesture spike, then the pad build's probes. Nothing under `spike/` is app code and
none of it was promoted; it is kept because it is how every mechanism below was measured, and how the next
one will be.

The two builds are **separate Gradle projects**: `spike/` has its own wrapper and settings, and the root
`settings.gradle.kts` deliberately does not include it.

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
field has focus, so it could never show over a browser page. The overlay is a `TYPE_ACCESSIBILITY_OVERLAY`
window added with `WindowManager.addView`, which needs no `SYSTEM_ALERT_WINDOW`, so the zero-permission
property survives. (the gesture spike named `attachAccessibilityOverlayToDisplay` for this; that API does not work from
an ordinary app — see the overlay section below.) **Measured 2026-09-02: a finger press reaches its buttons,
and its presence does not perturb the selection loop at all.**

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

**The landed offset is NOT a function of where the finger ends up.** Measured 2026-09-03 on one
Chrome node, three drags all ending at exactly x=741.5: from the handle at 712 it left the offset at
16, from 777.6 it gave 18, from 755.75 it gave 16 again. Same final pixel, three answers. Whatever
Chrome does on a handle drag keeps something from the grab, so a step cannot be computed as "put the
finger at x(target)" however good the pixel model is — which is why a character step still costs two
to seven gestures of read-and-correct rather than one. Do not rebuild the aim on the assumption that
it is a pure function; it is not.

**A character step costs one gesture and about a third of a second (the held-pointer fix, 2026-09-03).** Measured on
the OnePlus 15 against `TargetActivity`'s one-line block, ten presses, five each way, every one
moving **exactly one character**: **324-694 ms and 1-2 gestures**, against 0.7-2.3 s and two to seven
before. A long press starts a run that stepped twelve characters in about seven seconds. The
zero-permission property survives (`aapt2 dump permissions` prints the package line and nothing).

**The pad steps on RELEASE, never while a finger is on the glass**, and that is forced by the
mechanism rather than chosen: see the entry below. A tap is one step, a press held past the platform
long-press timeout arms a run that starts when the finger lifts, and any touch stops a run — which
costs nothing, because that touch would end the run's tracking anyway.

**Two things about a continued stroke's idle link, both measured the hard way.** A chain has to keep
dispatching to stay down, and while the loop waits for an announcement it has nowhere to go:

- **A zero-length continuation is refused**, and the refusal arrives as a *cancellation of the link*
 rather than an exception — `LOST after 1 link(s) — link cancelled`, on every press. This retires
 the "a `moveTo`-only path is fine" note that a first reading of `spike`'s `point` suggested;
 that path is only ever used as a FINAL stroke, and `pressdrag`, which uses one as a continuation,
 is itself recorded as never having worked.
- **A whole-pixel wobble is too much.** When the handle sits near a character boundary it flips the
 offset back and forth for ever, the settle never goes quiet, and "did that move?" stops meaning
 anything: one press took 3.2 s across 80 links and landed two characters out. Half a pixel,
 alternating, is a real path and cannot cross a character.

**Still open: a chain in the app is cancelled one or two links after the grab**, where the same chain
driven from `spike` survives ten links and a clean lift. It is NOT the pad's touch — a repeat run's
second and later steps happen with no finger anywhere near the screen and are cancelled identically,
and a 250 ms delay before dispatching changed nothing. The grab itself always survives, which is why
the step still works; only the held *corrections* fall back to the released path, which is the
difference between the 330 ms presses and the 660 ms ones.

**Continued strokes DO stay down. What stops is the target app's handle drag, and what stops it is
the user's own finger.** Measured 2026-09-03 (the held-pointer fix) with `spike`'s `held` command, which is driven by
broadcast and so can run a chain with nothing touching the screen — the control every earlier round
was missing. Corrects the earlier entry here, which blamed Chrome for ending its drag; that was wrong.

- **The chain never dies.** Ten links and a clean lift, every time, on both targets — and it survives
 a real tap and a resting finger too. Every link reports `completed=true`.
- **But any real touch permanently ends the app's tracking.** A single brief tap mid-chain killed the
 selection's movement from that link onwards and it never recovered, while the chain itself ran to a
 clean lift. A finger resting through the chain does the same for as long as it is down. Matched
 runs from the same selection: 9 of 10 links moved untouched, 3 of 10 with a finger down, 2 of 10
 after one tap. **So a held chain cannot span pad presses — the press is what ends it.**
- **The lift does NOT snap.** Three links landed `10..12` and it was still `10..12` after the lift and
 900 ms of settle. The snap belongs to the RELEASED path, not to lifting as such.
- **One press = one chain, and it is exact.** Five consecutive grab-move-lift presses cost
 **318–329 ms and one chain each**, against 0.7–2.3 s and two to seven gestures released.

The chain mechanism is solved: continue each stroke from the previous gesture's `onCompleted`,
immediately — continuing 300 ms later after reading the selection back never grabs anything, and a
timer that fires early is worse, because dispatching while a gesture plays cancels it.

**A `moveTo`-only path does not THROW, but it is not usable as a continuation either.** `spike`'s
`point` is exactly that path and `tap` and `long` build strokes from it happily, so the old claim
that constructing one throws is withdrawn. What it cannot do is continue a chain: dispatched as a
continuation it comes back cancelled, silently, as the entry above records. `pressdrag`'s settle
stroke is such a continuation, which is a candidate reason `pressdrag` has never worked.

The still-true half of the old entry: the landed offset is a function of the final pixel **and the
lift**. Held, the handle stays exactly where it is put; released, the app snaps it somewhere of its
own. That is why the released path needs several read-and-correct rounds per character, and it is
exactly what a held press does not have to pay.

**The earlier Chrome result — one move, then nothing through twelve destinations — was a wrapped
paragraph.** Its node bounds are the union of four line boxes, so the interpolated handle sits below
the *last* line rather than on the selection, and nothing was ever grabbed. That is the handle-location defect,
not a held-pointer one. Re-measuring on a one-line node is what turned the whole picture around.

**Interpolation needs a box that hugs its text, and a `TextView` does not give you one.** The debug
target's blocks were `MATCH_PARENT` at first, so a 30-character node reported itself 1208 px wide;
every derived handle landed hundreds of pixels right of the real one and every press failed. It read
exactly like a gesture problem and was a fixture problem — the blocks are `WRAP_CONTENT` now. Worth
remembering before blaming the loop for a target whose node bounds are wider than its text.

**An overlay's `LayoutParams` x/y are inset by the status bar; `dispatchGesture` coordinates are raw
screen pixels.** Ask for (60, 1040) and the window lands at y=1181 — 141 px lower, the status bar's
height. Mixing the two coordinate spaces silently misplaces everything, and it cost one wrong
conclusion here: a tap computed against the REQUESTED footprint missed the overlay by 11 px, landed
on the page, and read as "gestures pass through the overlay" when the opposite is true. **Read the
real bounds back from the `windows` command**, which reports the accessibility window list in screen
pixels, rather than trusting what was asked for.

**The accessibility window list lags a window move.** Query it in the same breath as
`updateViewLayout` and it still reports the OLD bounds; a second later it is right. Anything that
places a window and then reasons about where it landed has to re-read, not read once.

**A probe that misses a handle DESTROYS the selection.** There is no way to ask whether a pixel holds
a handle — only a drag moves one, and only a moved handle announces anything — so a search has to
touch. A touch that lands on the page instead collapses the selection to a caret, which means a scan
gets **at most one wrong guess**. Any acquisition-by-probing design has to survive that, or be sure
of its first attempt.

**"An event fired" is not proof that a handle was grabbed.** A collapsing selection announces a change
just as loudly as a moving one, which produced a confident false hit at a pixel holding no handle.
The signature of a real grab is that **one edge held while the other moved, and the range stayed
non-empty**.

**The floating toolbar's horizontal centre tracks the selection's centre** to within ~6 px (measured:
toolbar 615.5, highlight 621.5, handle midpoint 616.5), and it keeps tracking as the selection
changes (720.5 after widening, matching the wider highlight). Since the handles are symmetric about
it and the anchor handle does not move while one edge is dragged, the moving handle is
`2 × centre − anchor` — **selection geometry with no node bounds and no text**, which is what `track`
uses where interpolation cannot work.

**The event's source node follows the MOVING end of the selection.** Grow a selection past its node
and the next event describes the node containing the new end, with that node's own bounds — usually
single-line and tight — so interpolation starts working again by itself. Measured: a three-line
selection drove four clean `servo` steps because its source had become the single-line node holding
the end. The genuinely hard case is therefore narrow: **a line wrap INSIDE one node**, the only place
where the source stays coarse while the end moves.

**Only the MOVING edge is derivable; the other one is not.** `low`/`high` are offsets into the
SOURCE node, not into the selection, so on a multi-line selection the non-moving edge computes to
nonsense — a start handle at x=26 whose real position was ~556, a line higher. Derive the edge being
moved and never the other.

**The floating toolbar pins the selection's START, not its end.** Its vertical position moved ten
pixels across a deliberate three-line selection — it cannot be used to find the moving handle's row.

**The mirror identity holds only while BOTH handles are on the same row.** `moving = 2·centre −
anchor` tracked a handle from 663.5 to 873.5 across four steps on a node where interpolation is
useless — and then broke the moment the selection's end crossed to another line, because the start
handle stays on the first line while the end handle moves to the last, so the toolbar centre is no
longer their midpoint and the handle row being assumed is the wrong one. The drag then lands on the
page and destroys the selection. Track the handle's ROW as well as its column, or stop at the wrap.

**Do not track a handle by where the last drag was dropped.** A word-snap leaves the handle behind
the finger, so the error accumulates until it falls outside its own touch target: measured as nine
good steps and then nothing at all. Re-derive the position every step.

**A node's bounds are the UNION of its line boxes when its phrase wraps.** Chrome's nodes do hug their
text, but a phrase running across three lines reports one box covering all three (`196 2314 1060
2507` for 63 characters), and interpolating an offset across that is meaningless. This is a *different*
failure from Obsidian's, where a 4-character node reports the full content width — two causes, one
symptom, and a fix for one need not fix the other.

**Do not read node identity off bounds alone.** Neighbouring nodes on the same line have plausible
bounds and entirely different text: a probe's caret landed on the node holding `" for editing text
files."` and was briefly taken for a finer view of the node holding `", shown here, are often
included…"`. Check the text (or the length) before concluding two nodes describe the same run.

**Our own overlay eats our own dispatched gestures.** A gesture aimed inside the overlay's real
footprint hits the overlay — it will even fire the overlay's own button — and a selection handle
sitting under it cannot be driven at all, at any reach. Removing the overlay unblocks the identical
drag at the identical pixel immediately. So anything that dispatches must first ensure it is not
covering the point it is about to touch.

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

**The app** (repo root):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat:app:assembleDebug
# -> app\build\outputs\apk\debug\app-debug.apk
```

**The spike** (its own build):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Push-Location spike
.\gradlew.bat:app:assembleDebug
Pop-Location
# -> spike\app\build\outputs\apk\debug\app-debug.apk
```

Both produce `app-debug.apk` from a `:app` module, so **check which directory you are in** before
installing — the two APKs have different application ids (`dev.mnaoumov.asc` and
`dev.mnaoumov.asc.spike`) and can be installed side by side.

## Run the app

```powershell
adb -s <device-serial> install -r app\build\outputs\apk\debug\app-debug.apk
.\scripts\pad.ps1 on # off | status
```

`scripts\pad.ps1` is the by-hand switch. It **edits the enabled-services list in place** rather than
saving and restoring it, so anything else you rely on (a password manager's autofill service) is
preserved and a second run is a no-op. It also picks the **physical device** and ignores emulators,
per *Never touch* below.

**The app DOES appear in Settings → Accessibility → Downloaded apps; what is missing is the switch
inside it.** Say it that way round, because the looser version ("it does not appear in Settings") is
wrong and the row is genuinely there, reading `Selection Pad — Off ›` exactly like every other entry.

Open that row and it offers only **Shortcut** and **App info** — there is no "use this service"
toggle. Verified on the app 2026-09-03 by `uiautomator dump`: the page's single `Switch`
(`com.android.settings:id/switch_layout`) belongs to the Shortcut row. The control is Play-installed
Bitwarden's row in the same list, which reads `On / Assist with filling password fields…` because it
gets a real toggle in that position.

The cause is ECM, keying on install provenance: an `adb install` leaves `installerPackageName=null`
and `initiatingPackageName=com.android.shell`, which it treats as untrusted. OxygenOS offers no
"allow restricted settings" route, and `appops` cannot clear it either (adb's uid lacks
`MANAGE_APP_OPS_MODES`). A trusted install source is the only real fix — which is why Play is a
design constraint here rather than a distribution preference. Full reasoning in `the gesture spike's notes`.

A further wrinkle worth knowing: a service enabled with `settings put` **runs**, but that Settings
row still reads `Off`. So the list is not a reliable indicator of what is actually running — check
`settings get secure enabled_accessibility_services`, or just look for the pad.

The launcher entry (**Selection Pad**) shows the Play-required disclosure and a link to Accessibility
settings; on this device that link is informational, since the toggle will not be there.

The pad appears as soon as the service connects. Its buttons take **real injected input** —
`adb shell input tap <x> <y>` — which is the right way to test them: the service's own
`dispatchGesture` is a different input path, and O1 was careful about that distinction.

**Holding a direction button repeats it, chained off completion rather than on a timer.** A press is
a closed loop costing 300 ms – 2.2 s, so a fixed-interval repeat would queue steps faster than they
finish and the surplus would vanish on the `busy` guard. The next step therefore starts when the
previous one *ends*, if the finger is still down — self-pacing, and no initial-delay constant is
needed either, since a tap's finger has lifted long before step one completes.

A hold stops on the first step that does not move the selection. That is a **safety** rule: a failed
step failed by dragging where no handle was, and a drag that misses lands on the page, which on a
link navigates. It also stops when the handle is under the pad, because going non-touchable for the
step cancels the in-flight touch — the step runs, the repeat does not.

**The ▤ menu button covers the target app's selection toolbar.** It cannot be suppressed — it is
Chrome's own `PopupWindow`, put up by its `ActionMode`, and no accessibility API can stop another
app drawing — so the pad paints an overlay over its bounds instead. Three things this had to get
right, each measured:

- **`FLAG_LAYOUT_IN_SCREEN` on the mask window.** Without it `LayoutParams.x/y` are measured inside
 the content area while the bounds from the accessibility window list are raw screen pixels, so the
 cover landed exactly one status bar (141 px) low — blanking the body text under the toolbar and
 leaving the toolbar itself in plain sight. It reads as a z-order problem and is not one: the
 toolbar is at layer 21000, this overlay at 631000.
- **Never refresh it mid-press.** The app takes its toolbar down for the duration of any handle drag
 and puts it back after, so following it during a press added and removed an overlay window six
 times inside one step; that press took 3.6 s, spent ten gestures and moved nothing.
- **Do not believe the first disappearance.** Same reason — hiding on the first `null` made the mask
 strobe once per gesture. It lingers ~700 ms before coming down.

The mask is `FLAG_NOT_TOUCHABLE`, deliberately: the toolbar sits right beside the selection, which is
where the handles are, and a touchable overlay there would eat the service's own gestures. So the
hidden toolbar is still pressable blind — masking hides it without disabling it.

**Closing the pad hides the overlay and leaves the service connected**, on purpose. The `✕` at the
right of the status line is the only way out that is available on this device: the Accessibility
switch is ECM-blocked (above), so `disableSelf` would strand the user behind it, needing
`scripts\pad.ps1 on` to get back. Instead the service stays alive with no overlay, and **Show the
pad** on the launcher screen puts it back — a direct call through
`AscAccessibilityService.showPad`, which works because the activity and the service share one
process.

**A reinstall can leave the service listed in settings but with no accessibility connection** — it
answers commands while `windows` reports 0 and the tree comes back empty. Rewrite the
`enabled_accessibility_services` string (remove ours, put it back) to force a rebind.

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
| `findhandle <y> <centreX> [step] [maxProbes]` | Hunt for a handle by probing outward from a centre, for the surfaces where interpolation cannot reach it |
| `track <anchorX> <y> <dx> <count> [settleMs]` | The closed loop **without** node bounds: derive the moving handle as `2 × toolbarCentre − anchorX` before every step |
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

#### Overlay commands (the pad build Phase 1a)

| Command | Meaning |
|---|---|
| `overlay on [w] [h] [x] [y]` | Attach a touchable box — a drag strip above two buttons — over whatever is on screen; defaults to 660x260 at (300, 2200) |
| `overlay move <x> <y>` | Move it, the scripted equivalent of dragging its strip |
| `overlay off` | Release it |
| `overlay state` | Whether one is attached, and where |

The pad carries a **drag strip** so it can be pushed around the screen (owner's request, and the
manual half of the "pad must not cover what it is about to touch" constraint). The strip's touch
listener returns `true` — it consumes — unlike the diagnostic listeners on the buttons, because a
drag must not also read as a press. Dragging updates `LayoutParams.x/y` and calls
`WindowManager.updateViewLayout`, tracking `rawX/rawY` so the arithmetic stays in screen coordinates.

Every placement logs **both** the requested position and the real screen bounds, for the reason in
the gotcha below.

**Use `WindowManager.addView` with `TYPE_ACCESSIBILITY_OVERLAY` (2032) — NOT
`attachAccessibilityOverlayToDisplay`.** That API takes a `SurfaceControl`, so views must reach it
through a `SurfaceControlViewHost`, and `setView` on a host with a **null host token is refused**:
`RuntimeException("Adding window failed")` from `ViewRootImpl.setView`, measured both before and
after attaching the surface, so it is not an ordering problem. There is no public way to mint a host
token — `InputTransferToken`'s constructor is package-private. `TYPE_ACCESSIBILITY_OVERLAY` is what
an accessibility service is entitled to, needs **no `SYSTEM_ALERT_WINDOW`**, and is API 22.

Two more that are not obvious: **a Service has no theme**, so views need a `ContextThemeWrapper` or a
`Button` inflates wrong; and `onUnbind` must remove the overlay, because one that outlives its
service cannot be told to go away — `overlay off` needs a running service to receive it.

The overlay view logs **raw MotionEvents and button clicks separately, on purpose** — a view can
receive touches and still never fire a click, and which of the two happens is the measurement.

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
- **A locked phone still lists the pad, and still swallows every tap aimed at it.** Measured
 2026-09-03 with the screen dozing (`dumpsys power` → `mWakefulness=Dozing`, `dumpsys window` →
 `mDreamingLockscreen=true`): `dumpsys window windows` reported our overlay with a real frame and
 `isOnScreen=true`, and `adb shell input tap` on its buttons produced **no log line at all** — the
 press never arrived. So the window list is not evidence that the pad is reachable; check
 `mWakefulness` before concluding a button is broken, and take a screenshot first, because a dozing
 screen screencaps as pure black (~20 KB PNG) rather than failing.

## Never touch

`emulator-5554` / the `obsidian_test` and `obsidian_screenshots` AVDs belong to the Obsidian projects'
integration suites, which are worked in other sessions. This project uses a physical device.
