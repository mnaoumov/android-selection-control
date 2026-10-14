# android-selection-control

Android app giving **cursor and selection control where no keyboard can reach** — text that is
selectable by touch but *not* editable: a web page in a browser, a read-only view.

Tracked centrally in a private tracker. Six items are closed and hold the history: **the first spike**
(NO-GO — the accessibility selection actions stop at the editable-buffer boundary), **the gesture spike** (GO —
`dispatchGesture` drives the target app's own selection UI, plus the per-app observation matrix),
**The pad build** (the pad itself, built and measured), **the held-pointer fix** (the held pointer, and why a
character step now costs one gesture), **the swap-edge fix** (the swap button, and the one reading of "where
the moving edge is" that both edges now share), and **the paging fix** (page, start and end, driven to the
document's ends on the rig). Read the gesture spike for the mechanism, the pad build for what the pad
does, and the held-pointer fix for everything a continued stroke will and will not tolerate.

Open work is split by shape, one item each in that same store, and named there rather than here:
locating a handle that the aimed-at drop misses; the defects the handset pass of 2026-10-05 found
(two block-step cases, under *Gotchas*, both fixed on the rig: the step into a row of several nodes is confirmed on the phone, and `word →` onto a block's trailing `.` is confirmed there too, with the block step that follows it; the toolbar under a paged handle is fixed and confirmed on the phone, which
found four more under *Page, start and end*: a collapsing top bar read as the document's edge (fixed on the rig, which cannot show the collapse; on the phone, page and end now run to the article's real end, but the collapse did not recur, so the fix's own branch is unexercised; with the omnibox already hidden, a short `⇞ page` scroll still read as the edge there, and now the page must also stop listing that scroll direction, fixed on the rig and not yet confirmed on the phone), `start` ending
blind on an image (fixed on the rig; on the phone the move off the image landed on a hidden header button, and the walk now skips controls, which is fixed on the rig and confirmed on the phone; a page step that reaches the top used to land on the image itself, and every page drag now holds and moves off it, fixed on the rig and confirmed on the phone; a `start` from the page's top used to set off Chrome's pull-to-refresh, and now does not swipe at all, which is fixed on the rig and confirmed on the phone), a START handle at the left margin (inside OxygenOS's own edge-gesture strip; the pad now names that instead of failing silently, confirmed on the phone at both margins, and such a press now stops at the first swallowed touch-down, fixed on the rig and confirmed on the phone, which also showed that the strip lets through a grab whose first move is vertical), and a swipe that can open a heads-up notification (no touch-down lands in another app's window now; fixed on the rig and confirmed on the phone, where the pass also found that a press after a hand scroll can grab at a node's pre-scroll position, which now refuses instead, fixed on the rig and confirmed on the phone); the blind pad on Android 36.0 now names the Chrome flag that fixes it, confirmed on the phone; a
heading's block-wide box taken for its text now refuses instead of aiming, fixed on the rig and confirmed on the phone, under *Gotchas*; the grab that closed the pad through its own
`✕` is fixed on the rig and confirmed on the phone); and Play distribution, which ECM makes mandatory rather than optional. The test rig is now a repo asset — see *The test rig* below.

A handle inside a **wrapped** node was on that list and is no longer: the row it needs comes from the
platform's own per-character rectangle, which Chrome does supply for page content once the node has
been asked twice, for every character except the space a line wraps at (read from its neighbour). The gotcha that said otherwise had stood since the first spike and was the costliest
thing in this file; it is rewritten under *Gotchas*, with the measurement.

The boundary rework itself was measured on the rig on 2026-09-23: the long-press seed, the retrace and
both halves of the no-poisoning guard all hold, and the `word ←`-collapses-onto-the-anchor claim that
sat in this file unmeasured turned out to be false. All of it is under *Gotchas* below.

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

**`refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)` on page content is a request to LOAD,
not a query that lies — and that is the single most expensive mistake recorded in this file.** From the
first spike onwards it was written down as "it lies": it returns `true` with a correctly-sized array in
which every `RectF` is just the whole node's bounds, against a control (Chrome's own editable omnibox)
that returns real per-character rects. Every word of that is true of the **first** call. What nobody had
done was ask twice. The first request is what makes Chrome compute the node's inline text boxes;
**a later request on the same node returns real per-character rectangles**, each carrying its own line's
bottom — which is the ROW that a wrapped node's bounds cannot supply and the whole of the wrapped-handle
defect.

Measured on the rig 2026-09-23, against the three-line 81-character paragraph of the `ERR_INVALID_URL`
page, Chrome force-stopped first so nothing was warm:

```
press 1  locate: per-character rects are the node's own bounds here — the platform is not answering
press 2  locate: a measured rect gave the END handle its own row — caret=320.0 lineBottom=863.0 charPx=8.0
```

**80 ms apart**, same selection, nothing else touched — and 863 is the third line's bottom, not the
node's. Dragging the handle at that x with the row it derived grew the selection `new` → `new web`,
on the third line of a wrapped paragraph, which is the thing this project had concluded was impossible.

So the rule is: **ask once to make it measure, ask again to be answered.** `HandleLocator.primeCharacterRects`
does the first ask on the selection ANNOUNCEMENT rather than on the press, which buys the gap for free —
a human takes hundreds of milliseconds to reach a button — and `characterGeometry` deliberately does not
memoise a refusal, so the press that follows re-asks instead of being handed the primer's null.

**The primer fires on every NON-EMPTY selection, not only a wrapped one.** It was first gated on a box
measured to wrap, and a one-line node paid for that on its first press, sized by the node's average
instead of the glyph it crosses. Measured 2026-09-24 on the rig, Chrome force-stopped both times, the
same long-press on `Google` on `chrome://version` and one `char →`:

| primer | the press's ask | reach | time |
|---|---|---|---|
| wrapped nodes only | refused (the node's own box) | 15.54 px, the average | 1100 ms |
| every non-empty selection | answered | 12.0 px, the space it crossed | 639 ms |

Both landed on 7 in one gesture, because a space is forgiving; the reach is the difference that matters
on a narrow glyph. A caret is excluded on purpose: an editable field announces one on every keystroke
and the pad never steps a caret. On a `TextView`, which answers on the first ask, the prime is not
wasted for a rightward press: the success is memoised on that announcement and the press reads it.

**It also fires when a press ENDS, because the announcements a press causes arrive while it is busy,
where nothing primes.** A grow that carries the edge into a new node therefore used to leave that node
unasked, and the next press was cold. Measured 2026-09-24 on `ERR_INVALID_URL`, Chrome force-stopped:
a `word →` from `terms` into the wrapped paragraph, then a `char ←` 3 s later. Before, that press was
refused and grabbed under the third line (y 891); after, it read `lineBottom=767` and grabbed at y 795
on the first.

**A second trap sits on top of it: do not cache the "no".** The rung memoises per announcement so a step
pays one IPC rather than two, and the first version cached the refusal too. That is invisible and total:
the second press on an unchanged selection returned the memo without asking, so the answer Chrome was by
then ready to give could never arrive, and the whole thing read as a permanent refusal. Only a successful
answer is cached now.

**On a cold Chromium 157 the second ask can be refused too, so a press whose caret is refused asks
again before it aims.** Measured 2026-10-13 on the rig, Chromium r1709360 force-stopped before each run,
a served page of one-line `<p>` rows (`padding-top: 300px`), long-press `delta` on row 003, then `→ char`
about 2 s later. In 3 runs of 6 the primer's ask and the press's ask were both refused. The END was then
placed by the `<p>`'s block-wide box, caret 688 against glyphs ending at 502, and grabbed at x 706,
inside the 60 px right edge zone. It missed and ended `HandleLost`. The toolbar was up, but it was
pushed in from the right edge (`130..688`), so the END's ceiling was dropped. Unpushed, that ceiling would have
been 786 anyway, above the average. Now `perform` checks the moving caret on a Blink node, and when the platform
refuses it, re-asks every 100 ms for up to 1 s (`awaitCaretMeasured`), once per announcement. After
the change, 6 cold runs of 6 went `33 -> 1 -> 33`, then `⇥ word` `33 -> 4`. 2 of the 6 needed the wait,
and both were answered on the first re-ask, 100 ms in. Chrome 143 needed it once in 2 runs, and the
debug target's `TextView` never asks (`11 -> 12 -> 11`, `11 -> 19`). With the refusal marker, the wait
runs its whole second and the average's miss remains. Nothing on that row can rule it out.

**A third trap: the whitespace a line WRAPS at has no box, and Chrome answers for it with the node's
own bounds however often it is asked.** Loaded or not, that one index never becomes honest, and a word
grow parks the end edge on exactly that index whenever the word ends a line. Measured 2026-09-24 on the
rig against one 259-character, seven-line paragraph (a plain `<p>` served over `adb reverse`), Chrome
force-stopped before each run, the primer's ask plus two presses 3 s apart:

| selection | the index asked | answered? |
|---|---|---|
| `reader`, line 1 | 12, a space inside the line | yes, on the second ask |
| `selecting`, line 2 | 53, a space inside the line | yes, on the second ask |
| `missing`, line 7 | 243, a space inside the line | yes, on the second ask |
| `phrase`, end of line 2 | 62, the space line 2 wraps at | **never** |

The same refusal came back for the paragraph cut to 80 and to 160 characters, as a bare `<div>`, as
bare body text, and as a text run beside an `<em>`, so length and structure are ruled out. The
`ERR_INVALID_URL` control answered on the second ask in the same sitting. The caret has a character on
each side, so `characterGeometry` now asks the other one when the first is refused. For offset 62 that
is index 61, read from its right edge. The press then logs
`index 62 has no box of its own — the caret was read from 61` and aims at the handle Chrome actually
draws: (602, 1075) against a handle drawn at about (605, 1075).

**A step whose next character is on another row has to change row, not reach sideways.** A rightward
reach from the end of a line has nothing to enter, and `word →` from `small` at the end of line 1 ended
`HandleLost` three presses in three. `SelectionDriver.crossRowDelta` reads the carets of the current and
the destination offset from the same rectangles; when their bottoms differ, the handle travels to the
other row's caret. That applies to every step: the grow, the held first travel, and the walk-back and
held corrections, which is the mirror case of a shrink from a line's first word. Measured 2026-09-24 on
the rig, the same paragraph served lower down (`padding-top: 300px`) so the toolbar stays above:

| press | before | after |
|---|---|---|
| `word →` from `23..28` (`small`, end of line 1) | `HandleLost` | `28 -> 40`, the end of `frustration` on line 2 |
| `char →` from 28 | not measured | `28 -> 29`, 1 gesture |
| `char ←` from 29 | not measured | `29 -> 28`, 1 gesture, back up to line 1 |
| `word ←` from 29 | not measured | `29 -> 28`, 1 gesture |

**Chrome's grow is CHARACTER-granular across a row change.** One drag aimed 18 px into line 2 landed on
32, inside `frustration` (29..40), and even a drag aimed at line 2's own caret landed on 31. So a word
grow across a wrap is two stages (`changeRowThenGrow`): change row, walk a same-row shrink back onto the
row's first character, which is exact, then grow along the row from there as from any boundary. That
first character is recorded as a boundary only when the character between the rows has no box of its
own (`HandleLocator.hasOwnBox`), i.e. the line wraps at a space. No grow that changed row goes through
`recordBoundary`: the first version did, and 32, 34 and 35 went into the set.

**On Chrome the row change is two moves, never one diagonal line.** A grow goes straight down (or up)
onto the other row at the handle's column, then along that row onto the caret. A shrink goes along its
own row first, then onto the other row. A straight line from a line's last caret to the next line's
first sweeps back along the first line before it leaves it. Once it passes the anchor's column, Chrome
stops following the drag. Measured 2026-10-13 on the rig, Chrome 143, a served `As source code is text,
editors have features for it.` that wraps after `features`. Long-press `features` (`37..45`), then
`→ char`. The grab from (678, 887) to (50, 925) landed on `37..40`, and six held pushes along line 2
announced nothing: `Moved(45, 40)` in 8 gestures, 2 runs of 2. A hand drag on the same line
(`input motionevent`) also left `37..40`. A hand drag straight down and then left landed on `37..46`.
Going down first grows the edge to the other row's end, `37..53`. The slide back along that row is a
shrink, which is character-granular. `heldCharacterStep` holds the pointer between the two moves on
Blink, and so does `changeRowThenGrow` (`overTheWrapThenLift`), which then lifts. A `TextView` keeps the
one line. After the change, Chrome force-stopped before each run:

| presses | result |
|---|---|
| `→ char`, `← char`, `→ char`, `← char` from `features`, 2 runs | `45 -> 46 -> 45`, 2 gestures each, 0.85-1.09 s |
| `⇄ swap` on `for` (`46..49`), then `← char`, `→ char`, `← char`, 2 runs | `46 -> 45 -> 46 -> 45` on the START, 2 gestures each |
| `⇥ word` from `features` | the row change landed on 46 (before: `40..40`, `HandleLost`); the grow on from there is the margin defect below |
| `→ char`, `← char`, `→ char` from the caption's `are` (`14..17`), line 2 at x 110 | `17 -> 18 -> 17 -> 18`, 2-3 gestures |
| `⇥ word` ×2, `← char`, `⇤ word` from `are`, 2 runs | `17 -> 23` (`often`), `23 -> 32` (`included`), `32 -> 31`, `31 -> 24` |
| controls: one-line rows, `→ char`, `← char`, `⇥ word`; the debug target's `TextView` from `bravo` | `20 -> 1 -> 20`, `20 -> 4`; `11 -> 12 -> 11`, `11 -> 19` |

The grow from line 2's first caret of the `features` paragraph does not work yet. The END handle there
is at x 50, inside the rig's 60 px left gesture zone. Chrome holds an edge on a word's start, so the
grab is silent there. The edge-zone logic reads that silence as a swallowed touch-down and refuses
every margin press after it. That is tracked on its own.

**The grow escalation stops at the screen's edge.** Each reach is clamped to the screen, and the press
ends once the previous reach was already off it. Measured `word →` from the end of a paragraph's last
line: six tries, the last clamped to x 719, instead of the escalation running on to a 360 px reach.

**So does the walk-back's, and every dispatched point is clamped under both.** `StrokeDescription`
THROWS on a path with negative bounds (`Path bounds must not be negative`), and the throw kills the
process: the pad vanishes mid-press and the system restarts the service a second later. Measured
2026-09-24 on the rig: a walk back from 64 to 63 at the start of a line escalated leftward from a handle
at x 84, and its seventh try aimed below 0. `walkBackTo` now stops once its previous reach was already
off the screen, and `AscAccessibilityService.onScreen` clamps every point a stroke visits, the slop
detours and `HeldPointer`'s links included, so no caller's arithmetic can take the service down again.

**The lie is DETECTABLE, and that is what makes the priming safe.** A rectangle that is both as wide and
as tall as the node it came from is the node's bounds repeated, and a rectangle that is genuinely one
character is smaller than its node by whole characters *and* whole lines — nothing real sits in between.
So the rung *asks*, *checks*, and falls through when the answer is the box; the worst case is one wasted
round trip, and the primer throws its answer away by design.

**Descending the node tree for something finer than the paragraph does NOT work on Chrome — measured,
and it is the rung that stays unproven.** A wrapped Chrome text node is a **leaf**: `childCount` is 0 on
an 81-character three-line paragraph and on a 55-character one on `chrome://version`, so there is no
per-line or per-word child to interpolate across. `HandleLocator.lineNodeGeometry` is that rung and it
reports the negative itself (`the wrapped source is a leaf (81 chars) — the tree has nothing finer`); it
is kept for the surfaces whose trees are not Chrome's, with the caveat that **its success path has never
once fired on a measured surface**, so it is a guarded fallback rather than something proven.

**And `uiautomator dump` is NOT how to answer that question.** The dump shows Chrome's paragraphs as
leaves — and it also never listed the 7-character node that a selection inside one of them announced as
its source, so the two disagree in the direction that matters. Only a walk of the live
`AccessibilityNodeInfo` says what the service can see.

**Asking it for rectangles is not reading text.** It is asked with indices and answers with `RectF`s, so
it has the same standing as `text?.length` — a measurement of the glyphs, never a look at them. Worth
saying out loud, because the name sits close enough to the trust property to read as a breach.

**A selection event is a change notification, not a state query.** `TYPE_VIEW_TEXT_SELECTION_CHANGED` only
fires when the range CHANGES — re-selecting the same word is silent — and nothing lets you *ask* what is
currently selected, because `textSelectionStart/End` stays `-1` on page nodes. Track it from the stream.

**A newer Chrome announces a page selection from the frame's ROOT, with `-1..-1`, and the range is on
that root's `AccessibilityNodeInfo.getSelection()`.** This is Chromium's `AccessibilityExtendedSelection`
feature. In 154 it is off in the code and switched on by a server-side trial, which is how the handset's
Chrome 154.0.8037.59 got it on 2026-09-30. On Chromium's `main` it is on by default. The event's source
is the whole `WebView` (`srcLen=0`) and no page node reports a range, so to the old reader every press
was "the pad cannot see a selection". The root's `Selection` holds an anchor and a focus, each a node and an
offset. androidx records whether each offset counts characters or children in two
`...SELECTION_START/END_OFFSET_TYPE` extras. `SelectionObserver.extendedRange` reads them and rewrites the
range into the shape the old event had: the focus node as the source, `anchor..focus` when the anchor is
in that node and `0..focus` when it is not. So nothing downstream changed. `getSelection` is a **36.1** API
(`SDK_INT_FULL`), so the app compiles against 36.1. On a 36.0 device Chrome sends the root event and can
attach no range at all. Measured 2026-10-01 on the rig (API 37) with Chromium snapshot r1709360
(`AndroidDesktop_x64`'s `ChromePublic.apk`): `main`'s build said "cannot see a selection" on every press.
The fix seeded `bravo` at `14..19` and stepped char, word, wrapped-line and START-edge presses. The press
results were the rig's Chrome 143's to the gesture, on the same page and the same sequence.

**The handset IS that 36.0 device, so the pad is blind there while the trial is on.** Measured 2026-10-05
on the OnePlus 15 (`ro.build.version.sdk_full` 36.0, Chrome 154.0.8037.92), with a temporary probe on the
root announcement. The `WebView` root's extras carry the two `...OFFSET_TYPE` keys (0 or 1) and nothing
else about the selection: no anchor, no focus, no offset. Every press said "the pad cannot see a
selection" while Chrome showed one. **`chrome://flags#enable-accessibility-extended-selection` set to
Disabled**, followed by a relaunch, turns the old announcement back on, and every handset measurement
below was taken that way. The flag was put back to Default afterwards. Until the phone gets 36.1, that
flag is the only way the pad works there.

**So the pad names the flag instead of saying "re-select".** `SelectionObserver.chromeHidesSelection` is
set by a `-1..-1` announcement from a Blink frame root (class `android.webkit.WebView`) that yields no
range below 36.1. A press then walks the tree once more (the node rung), and when the toolbar shows a
selection the status line reads `Chrome hides it: disable flag "extended selection"`. That search finds
the flag's title, "Accessibility Extended Selection", in `chrome://flags`. The Play listing spells it out.
No other rung exists on 36.0: `getSelection` and `ACTION_SET_EXTENDED_SELECTION` are both 36.1, and no
page node reports `textSelectionStart/End`. A tap that clears the selection is announced from the root the
same way, so the message is gated on the toolbar, which a cleared selection takes down.

**The rig reproduces it with a debug marker file.** On a debuggable build,
`adb shell run-as dev.mnaoumov.asc touch files/force-no-extended-selection`, then `rig.ps1 rebind`, makes
the service treat the platform as below 36.1 (it logs `debug: treating the platform as below 36.1`); `rm`
it and rebind to undo. Measured 2026-10-05 on the rig with Chromium r1709360: with the marker, a long-press
on `bravo` logged `selection announced from Chrome's frame root, and below 36.1 nothing carries its range`,
and the press logged `node rung: no page node reports a range either`, on four presses over three
long-presses; the screenshot showed the flag message. Without it, the same page stepped `19 -> 20 -> 21`
exactly. A run straight after a reinstall announced nothing at all and got the old "re-select" message,
which is the pre-existing no-event case, not this one.

**The handset confirms it (2026-10-05, OnePlus 15, 36.0, Chrome 154.0.8037.92, flag at Default).** A
long-press on `editor` in `en.wikipedia.org/wiki/Text_editor`'s heading logged `selection announced from
Chrome's frame root, and below 36.1 nothing carries its range`. `→ char` then logged `node rung: no page
node reports a range either`, and the status line read `Chrome hides it: disable flag "extended selection"`.
So the root's class on the phone is the one `isBlinkRoot` expects. With the flag Disabled and Chrome
relaunched, the pad saw the selection again (`seeded from a fresh selection`), and `→ char` from `for` in a
body paragraph went `61 -> 62` in 1 gesture and 0.71 s. The flag was put back to Default afterwards.

**That same sitting found two defects.** From the heading's last word, `editor`, both
per-character asks were refused, so the END handle was placed by the node's average across the heading's
block-wide box, at x 2477 against glyphs ending near x 680. That press now refuses, which is confirmed on the phone (*A block-level
node's `getBoundsInScreen`* below). The grab was also inside
the pad's `✕`. The pad's pass-through relayout was logged 1 ms after the grab was dispatched, and the pad
window was destroyed 40 ms after the press ended. The pad stayed gone until a rebind. That half is fixed
(the entry after *A press can carry the handle UNDER the pad*).

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
the OnePlus 15 against `TargetActivity`'s one-line block: **sixteen consecutive presses, eight each
way — 11 to 19 and back to 11 — every one moving exactly one character**, at **310-599 ms**, one
gesture on thirteen of the sixteen. Before: 0.7-2.3 s and two to seven gestures. A long press starts
a run that stepped sixteen characters in about six seconds, one per step, stopped by a tap. The
zero-permission property survives (`aapt2 dump permissions` prints the package line and nothing).

Re-measured 2026-09-03 for the START edge and it holds there too: **sixteen presses, 12 to 4 and back
to 12, one character and one gesture each, 320-361 ms**. But **"held, it does not snap" is not
universal** — on the same fixture the END edge at offset 24 lands on 23, reports it, and is snapped
back to 24 by the app *after* the lift, so that one press can never get past it. See the two entries
below; do not treat the no-snap property as a law when reading the loop.

**A step is sized by the character it CROSSES, not by the node's average — and the platform will
measure that character for you.** `pixelsPerCharacter` used to divide a one-line node's width by its
length, which describes the string rather than the glyph in front of the handle, and proportional
text puts those a long way apart. Measured against `TargetActivity`'s `alpha bravo charlie delta
echo`, whose average is 27.73 px on the phone and 16.03 px on the rig: a reach of one *average*
character left from offset 24 crosses the `t` at index 23 and moved **two real characters** on both
devices. The correction that follows then travels most of an average character to cross a narrow one,
which parks the pointer in the far half of the target's cell — and the app, which follows the pointer
while the finger is down and finalises to the nearest boundary when it lifts, rounds it back. That is
the whole of the "one offset the press can never get past" above.

`refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)` answers honestly on a `TextView`, so
the one-line path now asks it and keeps the average only where it declines. On Chrome page content
it declines on the FIRST ask and answers on a later one (the gotcha above), and the primer
makes that later ask the first press, so a one-line node is sized by a real glyph there too. It must be asked for the
character the step CROSSES: leftward that is the one BEFORE the caret (`offset - 1`), and asking the
other way measures the glyph the step is walking away from. Measured on the rig, same fixture, back
to back: leftward from 25, the average sized every reach at 16.03 and needed a correction at the
`t`; the real widths came back 20, 12, 12, 19 px and every step was exact in one gesture. Twelve
rightward presses from `6..11` net **+9 characters** against **+4** for the average, with one
`HandleLost` against two and one backwards press against three.

**Read the selection back AFTER the lift — the held path's answer is not final until then.** A
release is asynchronous: the chain notices it at the end of the link in flight and then plays a lift,
so `releaseHeld` followed by reporting in the same breath answers before the target app has
finalised its own drag. Five presses in a row announced `24 → 23`, each true when it was said, and
the selection was back at 24 every time — a dead button the pad could not see, because it had stopped
looking. Every held exit now lifts, settles and reports the offset that survived; the log shows it as
`held: lifting` BEFORE the `-> Moved(...)` line rather than after. Nothing downstream needed
changing: `madeProgress` already treats an unchanged offset as no progress and stops a repeat run,
and the status line already says "didn't move".

**Chrome finalises a GROWING drag one character further on at the lift, so a grow pulls the held
pointer back 1 px before it lets go.** Measured 2026-09-24 on the rig, against a served page with a
centred 13 px `Google LLC`, the shape of `chrome://version`'s header: one `char →` from `0..6` held at
7 with the pointer 2 px into the `L`, and the lift turned it into 8 on seven presses of seven, cold
and warm. Nearest-boundary rounding cannot explain it (2 px into a 16 px glyph is 7). The direction of
the last move does, and the controls say so: a press whose held correction ended with a shrinking move
was never revised; a 1 px push forward before the lift was revised three times in three; a 1 px pull
back was revised never, 4 of 4, and then 25 of 26 Chrome presses over three nodes. The same 1 px on the
debug target's `TextView` left 16 of 16 presses exact, so `pullBackThenRelease` is not gated by
surface. The distance is not the point, only which way the pointer last moved, so it is the smallest
move there is.

**Chrome ignores a SHORT held shrink, so a silent one-character held correction lifts and walks back
released.** Measured 2026-09-24 on the rig, on a served one-line `Chrome is made by Google` at 32 px:
held corrections of -11.7 px (12 -> 11, back over an 18 px `a`), -5.2 px (8 -> 7, over an 8 px `i`)
and -6.5 px (18 -> 17, over a 10 px glyph) announced nothing, even after the wait. A -19.5 px one in
the same run moved. The press used to release on the overshoot and report `Moved(10, 12)`. Now
`releaseThenWalkBack` lifts, settles and hands over to `walkBackTo`. After the change, 36 of 36 Chrome
`char` presses over three lines moved exactly one character, four of them this way, at 1.4–1.6 s
and 3 gestures against ~0.8 s and 1.

**A one-line node's grab is aimed at the MEASURED caret now, but that was not what caused most of the
+2s.** `HandleLocator.locate`'s one-line branch used to interpolate by the node's average character, so
the grabs on `Chrome is made by Google` sat exactly 16 px apart per offset (146, 162, … 258) across glyphs
of 8 to 30 px. It now takes the caret from `characterGeometry`, which the primer has usually already
asked, and keeps the average only where the platform declines. The row stays `bounds.bottom`. Measured
2026-09-24 on the rig, Chrome force-stopped before each run, old build against new in one sitting, `char →`
along three served one-line nodes:

| line | presses | detours, old aim | detours, measured aim |
|---|---|---|---|
| `Chrome is made by Google`, from 6 | 16 | 6, 9, 10, 18 | 9, 10, 18 (two runs, identical) |
| `alpha bravo charlie delta`, from 11 | 12 | 12, 19, 20 | 12, 19, 20 |
| `mint oil ink lilt wax`, from 4 | 12 | 6, 11, 15 | 6, 11, 15 |

Every press moved exactly one character in both builds. The measured aim removed the one detour where
the average was furthest off (16 px at offset 6 of the first line). Everywhere else the same offsets
overshoot whichever aim is used: at offset 6 of `mint oil ink lilt wax` the grab moved 14.6 px and the
press still landed on 8. At offset 9 of the first line, the average and the measured caret were the same
pixel. So the rest of the +2 landings depend on where the step goes, not on where the grab starts. They
were the grab's slop detour (the entry after next). The debug target's `TextView` took 16 of 16 presses, 8 each way from `bravo`, exactly
one character each, with an aim that moved by up to 14 px.

A second +2 sat beside it: the held first travel was floored at `STEP_PX` = 12 even when the glyph
had been measured, so the 8 px `i` and `l` of `oil` were overshot, 6 -> 8, and a held correction
back to 7 announced nothing. That floor is gone; `pixelsPerCharacter` already bounds its answer.
The reported shape now reads `reach=10.0 -> 0..7`, pulls back, and stays on 7.

**On Chrome the held grab's detour stops just past the touch slop, because Chrome keeps the lead a
snap built up.** The grab used to travel 60 px past the handle before coming back to its destination.
That visit grows the selection, often past the next word's middle, where Chrome snaps to the word's
end. The return is a shrink, and Chrome's shrink keeps the distance between the snapped edge and the
finger, so the edge stops one character past the finger. Measured 2026-09-24 on the rig by logging
every announcement during the grab: `char →` from 9 of `Chrome is made by Google` announced 10, 14, 13,
12, 11 and stopped on 11 with the pointer on 10's caret. From 17 it snapped to 23, the end of `Google`,
and came back only to 20. A visit that stayed short of a word's middle (from 8: 10, then 9) came back
exactly. So the detour is now as long as the reach, or one pixel past the slop (17 px here) when the
reach is shorter, which is `drag`'s `pastTarget = false`. The page is recognised by the
`AccessibilityNodeInfo.chromeRole` key in the source node's extras, which Chrome and WebView set on
every node. Its class name is `android.widget.TextView`, so the class cannot say it.

A short detour does not reach the next word's middle, so from a word's START Chrome holds the edge and
the grab announces nothing. There the held pointer now pushes on through the snap (`heldSnapThrough`)
and walks back from the word's end. Releasing to the released grow instead took 3-7 gestures and once
ended where it began (`Moved(9, 9)` in 4.1 s). Chrome force-stopped before each run, served page:

| run | presses | extra gestures, 60 px detour | extra gestures, short detour |
|---|---|---|---|
| `Chrome is made by Google`, `char →` from 6 | 16 | from 9, 10, 17, 18 | from 10, 15, 18 (two runs, identical) |
| `alpha bravo charlie delta`, `char →` from 11 | 12 | from 12, 19, 20 | from 12, 20 |
| `mint oil ink lilt wax`, `char →` from 4 | 12 | from 6, 11, 15 | from 5, 9, 13 |
| `Google`, swapped, `char ←` from 18 | 12 | not measured | from 17, 14, 9 |
| `char ←` from 24 after four `word →` | 10 | not measured | none |

Every press in every run moved exactly one character. Each remaining extra-gesture press starts at a
word's start in its direction of travel. Where Chrome held the edge, the push-through took 3-4
gestures and 1.35-1.75 s. Where the short grab still crossed a short word's middle (`by`, `oil`,
`lilt`, `is`) and snapped, a released walk-back took 2 gestures and 1.2-1.5 s.
The mid-word and space +2s are gone (1 gesture, about 0.8 s). A shrink has no word to snap into, and 10 of 10
took one gesture. Detouring toward the anchor was also measured, and it was worse: the return is then a grow, which
Chrome ended one character SHORT about as often as the long detour ended long. The debug target's
`TextView` keeps the 60 px detour, and 16 of 16 presses from `bravo` still moved exactly one character.

**`chrome://version` itself cannot show any of this on the rig.** Its header sits right under the
omnibox, so Chrome puts the floating toolbar BELOW the selection, over the handle, and every grab there
lands on the toolbar and ends `HandleLost` (eight of eight). Measure the lift on a served page that
reproduces the header's shape lower down, as above. That count predates the toolbar-aware aim below
and has not been re-taken.

**A grab under the floating toolbar presses a toolbar BUTTON, which is worse than a miss: it acts.**
Near the top of the viewport Chrome puts the toolbar below the selection, over the handle. The mask
cannot help, because it must not take touches. Measured on the rig against a served paragraph on the
first lines of the page: a `word →` grab landed on **Select all**, and the pad read the whole-page
selection as `Moved(12 -> 24)`; another opened the overflow menu. A touch goes to the window that owns
the pixel of the down, and the toolbar does not always cover the whole handle. After a long-press on
line 1 the toolbar's touchable region was `(32,431)-(515,527)`, the line bottom 409..415 and the aim
437, so the handle's top band was bare. `HandleLocator.clearOfToolbar` moves the aim to just outside
the toolbar's nearer edge (2 dp), when that stays within 12 dp of the handle's centre and below the
text line. Otherwise it returns null, and the press ends `HandleCovered` with nothing touched.
Measured 2026-09-24, Chrome force-stopped each time, all three of the original repros:

| selection | toolbar top | aim | outcome |
|---|---|---|---|
| `reader`, line 1 | 431 | 437 -> 427 | `12..13`, 1 gesture, no button pressed |
| `selecting`, line 2 | 491 | 495 -> 487 | `53..54`, 1 gesture, no button pressed |
| `phrase`, end of line 2 | 491 | 495 -> 487 | nothing pressed; ends `HandleLost` at the line end, selection intact |

The third one's `HandleLost` is the end-of-line step failure, not the toolbar. The window list's
bounds for the toolbar are its touchable region. `dumpsys window` shows the PopupWindow's frame as the
much larger `(0,415)-(547,831)`, so do not read the toolbar's extent off the frame. The toolbar also
moves after the first drag, lower or sideways, so every grab re-checks it. The refusal path first fired
on the rig on 2026-10-05: a START at y 299, just under the band's top, under a toolbar at
`(32,261)-(515,357)`, ended `HandleCovered` in 19 ms with 0 gestures (*Page, start and end*).

**A `TextView`'s selection handles are windows too, and they are smaller than the toolbar.** Each
handle is a `PopupWindow` of its own in the target's package, 88x80 on the rig and centred on the
handle, so a lookup that took the smallest non-full-screen window found the handle and refused every
`TextView` step as `HandleCovered` in ~10 ms, with 0 gestures. `HandleLocator.toolbarBounds` now keeps
only a window whose node tree holds a clickable node. The toolbar's buttons are clickable, and the
handle's bare `View` is not. Measured 2026-09-24 on the rig: the two handle windows read
`clickable=false` and the toolbar `(80,384)-(482,480)` read `clickable=true`. `char →` and `char ←`
from `charlie` and from `bravo` all moved again. On Chrome's served page the toolbar below `reader`
was still found and the aim still moved off it (y 419 -> 403), `10 -> 11` in 1 gesture. Chrome draws
its handles inside the page, so there the toolbar was always the only candidate.

**A `TextView` snaps a grow that starts on a word boundary, and the grab can land BACKWARDS. The
platform's source says why.** The framework source is on this machine
(`%LOCALAPPDATA%\Android\Sdk\sources\android-36.1\android\widget\Editor.java`), and
`SelectionHandleView.updatePosition` holds three rules. Every one of them was then seen on the rig
(2026-09-24, `alpha bravo charlie delta echo`):

- **The word snap.** Growing from a boundary, the handle keeps its previous offset until the finger
 passes the MIDDLE of the word it is entering, and then it jumps to that word's END. From inside a
 word it moves one character at a time. So `char →` from the end of `bravo` (11) announces nothing
 for any reach short of half of `charlie`, and then lands on 19. Getting to 12 means overshooting to
 19 and shrinking back, because shrinking is always character-granular.
- **The first move event is always read as a shrink.** `mPrevX` is unset at touch-down, so the
 first move has `xDiff == 0`, is not "expanding", and goes through the shrinking branch at the
 finger's offset. The held grab also detours 60 px outward (`SLOP_DETOUR_PX`), which is about half
 a word, so where a grab lands depends on how the move events happen to be sampled. Measured
 landings for a one-character `char →`: 11 -> 12, nothing, 9 or 10; and 12 -> 13, nothing, 19 or
 11. The backwards ones are the whole of the old "moves the edge BACKWARDS" defect. The START edge
 does the mirror image (5 -> 8 on a `char ←`).
- **The touch-up filter.** On lift, an offset that changed in the last 150 ms is reverted to the
 one before it, if that one had held for 350 ms. This is the lift-time snap-back recorded above.

The pad now treats a landing that is not past the press's origin as not yet a step: it escalates,
pushing a held pointer through the snap (`heldSnapThrough`). Any walk-back of more than one
character is done released, because a held shrink out of a snap overshot three times in three
while the released one with the same reach was exact. That walk-back is sized by the measured
glyphs of the whole run (`HandleLocator.characterRun`, by its outer edges), not by one glyph times a count. Over 24
`char →` presses from `bravo` every one landed exactly one character on. About one press in five
still pays for the snap: 1.3–4.6 s and up to seven gestures, against 0.7–1 s and one gesture.

**The first travel aims at exactly ONE character, in both directions** — not at the released path's
[`CHARS_PER_ATTEMPT`] overshoot and not backed off by `BOUNDARY_BIAS`. Both deviations were measured
and both cost accuracy: 1.5 characters lands on +2 wherever growing is character-granular, and
0.65 of a character lands too near the boundary to hold — every left press announced `21 -> 20` and
the selection was back at 21 by the next press, ten times running. Aiming short is safe because a
word-snapping target simply announces nothing and the step falls back to the released path, which
escalates properly.

That `21 -> 20` measurement is the same defect as the entry above, seen from the other end: what put
the pointer too near the boundary to hold was a fraction of an AVERAGE character, and the app's
lift-time finalisation rounded it back. The first travel is a whole character for that reason too,
and now a whole *real* character wherever the platform will measure one.

**The pad steps on RELEASE, never while a finger is on the glass**, and that is forced by the
mechanism rather than chosen: see the entry below. A tap is one step, a press held past the platform
long-press timeout arms a run that starts when the finger lifts, and any touch stops a run — which
costs nothing, because that touch would end the run's tracking anyway.

**A chain's idle link: one pixel, VERTICAL, and the bookkeeping must follow it.** A chain has to keep
dispatching to stay down, and while the loop waits for an announcement it has nowhere to go. Four
measurements, in the order they were forced:

- **The next link's path must START where the last one ended**, including an idle link's own nudge.
 A version that moved the pointer but deliberately left its recorded position alone — "an idle link
 stands still" — broke every chain in the app two links after the grab, while the identical chain
 from `spike`, whose links always carry a real destination, ran ten and lifted cleanly. The
 mismatch is reported as a cancelled link, never as an error.
- **A zero-length continuation is refused**, with the same cancelled-link symptom. This narrows the
 "a `moveTo`-only path is fine" note: fine to build and fine as a FINAL stroke, not as a
 continuation — and `pressdrag`, which uses one as a continuation, is itself recorded as never
 having worked.
- **A sub-pixel nudge is unreliable.** Half a pixel lands on a new pixel or not depending on the
 fraction the handle happens to sit at, so chains died on about half the presses, in a pattern that
 followed the handle's x rather than anything about timing.
- **A whole pixel SIDEWAYS is too much.** Near a character boundary it flips the offset back and
 forth, the settle never goes quiet, and "did that move?" stops meaning anything — two presses of
 eight hit the settle cap and one moved two characters. Vertically none of this applies: the handle
 hangs below its line, a pixel is nowhere near leaving its touch target, and a vertical move cannot
 change a horizontal offset.

**Held, a silent correction means WAIT, not move again.** Released, a gesture that announced nothing
really did nothing, so retrying is right. Held, the pointer has already travelled and silence only
means the announcement is late — correcting again applies the same reach to a pointer that already
moved. Measured: two corrections of -18 px computed from one stale reading of `18`, landing on 16
instead of 17. The settle also needs a total cap, not just a quiet one, or a flapping selection makes
a press last for ever.

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

**"No toolbar" straight after a drag is not "no selection".** The target app takes its toolbar down
for the whole of a handle drag and puts it back after, so the safety check that a silent drag has
not destroyed the selection (`selectionStillOnScreen`, the toolbar's presence) reads "gone" every
time it is asked in that gap. It was asked 6 ms after the settle, and every silent grow on Chrome
ended `HandleLost` with the selection intact, so the escalation that would have passed the next
word's middle never ran. `awaitSelectionOnScreen` now re-reads it for up to 900 ms before believing
it. Measured 2026-09-24 on the rig, a served one-line `alpha extraordinarily wonderful`: three silent
grow tries in a row each found the toolbar gone and back **25 ms** later, and the fourth crossed the
word. On a `TextView` the toolbar is already back when asked, so the wait costs nothing there.

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

**What a wrapped node actually denies you is the ROW, not the column.** Every rung takes `y` from
`bounds.bottom`, which on a union of line boxes is the **last** line — right only when the moving edge
happens to be sitting there. An x can be estimated and corrected by the loop; a row cannot, because a
handle one line out is not a near miss but a touch on the page. That is why `SelectionDriver`'s scan now
*refuses* on a box measured to wrap (`HandleLocator.scanRowIsKnown`) instead of hunting sideways along a
row it has no reason to believe: a sideways search is only ever as good as the row it searches, and a
miss there costs the user their selection. **The toolbar-mirror rung is gated the same way**: it answers
with a column and borrows the same `bounds.bottom` row, and on a cold wrapped node it once aimed a drag
under the third line while the edge sat on the first.

That refusal is deliberately narrow — it fires only where the wrap is **measured**
(`sourceLength > 0 && !sourceIsOneLine`). A surface that announces no length at all (Gecko sends
`srcLen = -1`) cannot be classified either way, and keeps the behaviour it had, so nothing that might be
working is taken away to fix something that is not.

**Do not read node identity off bounds alone.** Neighbouring nodes on the same line have plausible
bounds and entirely different text: a probe's caret landed on the node holding `" for editing text
files."` and was briefly taken for a finer view of the node holding `", shown here, are often
included…"`. Check the text (or the length) before concluding two nodes describe the same run.

**Our own overlay eats our own dispatched gestures.** A gesture aimed inside the overlay's real
footprint hits the overlay — it will even fire the overlay's own button — and a selection handle
sitting under it cannot be driven at all, at any reach. Removing the overlay unblocks the identical
drag at the identical pixel immediately. So anything that dispatches must first ensure it is not
covering the point it is about to touch.

**A press can carry the handle UNDER the pad, so the check is made at every touch-down, not once
per press.** Measured 2026-09-24 on the rig, the served seven-line paragraph: `word →` from `phrase`,
at the end of line 2, changed row onto line 3 at y 1193, inside the docked pad (y 1152–1472). Every drag
after that landed on the pad. The walk back onto the row's first character announced nothing at any
reach, and the next press's fourteen grows did the same. The per-press check had missed it twice:
it ran before the row change, and it placed the handle at the wrapped node's `bounds.bottom`, which
is the last line (y 1487), below the pad. `clearThePadFor` now makes the pad transparent the moment a
drag, a hold or a grab is about to touch down inside it, and the press's end makes it solid again.
After that, three cold runs of three went `62 -> 65` (the end of `on`) in 4 gestures and 1.5 s, and
the walk back reached 63 on its second try.

**Making the pad transparent is asynchronous, and a touch-down dispatched in the same breath lands on
the pad.** `updateViewLayout` takes effect on the pad's next traversal, and the input system picks the
flag up after that. On the handset (2026-10-05) a grab aimed inside the `✕` reached it, and the lift
clicked it and closed the pad. On the rig a served word was placed so that its END handle sat inside the `✕`
(aim (610, 1189), `✕` at 528..704 x 1168..1264). There the grab's touch-down reached the pad 2 ms after
dispatch. The held grab and all six snap-through pushes announced nothing, and a released drag from the
same pixel afterwards moved the edge: `Moved(19, 20)` in 2.2 s and 8 gestures. Two fixes, and both are needed:

- **The pad's root (`Pad.TouchGate`) swallows every touch while the pad is meant to let touches
  through**, so a touch that still beats the flag presses nothing and closes nothing. It logs
  `pad: a touch-down ... swallowed`.
- **`clearThePadFor` runs the touch-down only once the flag can be relied on**: after two frames, and
  no sooner than `PASS_THROUGH_SETTLE_MS` = 100 ms. With two frames alone (7-18 ms on the rig), three
  grabs of four were still swallowed. The one 56 ms wait was clean. With the 100 ms floor, 6 runs of 6
  went `19 -> 20` in 1 gesture and about 0.8 s, nothing was swallowed, and the pad stayed. Only a press
  whose touch-down falls inside the pad pays the wait.

**The phone confirms it (2026-10-10, OnePlus 15, portrait, Chrome 154.0.8037.127 with the
extended-selection flag Disabled).** A served one-line `alpha bravo xy` at 20 px, placed so that
`bravo`'s END handle sat inside the `✕` (aim (1077.5, 2220), `✕` at 948..1256 x 2196..2364). Long-press
`bravo` (`6..11`), then `→ char`. Every run logged `the pad lets touches through after 100 ms` (101 once),
and none logged `swallowed`. The grab was accepted and the press went `11 -> 12` in 1 gesture and 0.80-0.83 s.
The pad stayed, and its status line read `moved 11 → 12`. That held in 3 runs of 3, so 100 ms is long enough
on the phone too.

**The announced offsets are LOCAL to the event's source node.** When a selection grows past a node's edge
the source switches to the newly-covered node and the offsets restart from it — the run below stepped
`…46, 47, 48` on a 48-character node and then reported `0..4` on the next one. There is no document-wide
offset anywhere in this mechanism.

**The caret between two adjacent nodes can be announced in EITHER node's frame.** Measured 2026-09-24 on
`ERR_INVALID_URL`, Chrome force-stopped: `char ←` from `0..1` of the paragraph landed on `9..15` of
`"chrome://terms/"`. That is the paragraph's offset 0 in the previous node's frame, as its length, and it
is exactly one character. A target of `sourceLength - 1` read it as an overshoot and took one more character
off. So a step that crosses a node is reckoned across the seam as if the nodes were adjacent
(`SelectionDriver.crossedTarget`): leftward `length + start - 1`, rightward `start + 1 - previous length`.
Where that falls outside the landing node, the held path takes its landing as the step. After the fix, cold:
`char ←` 1 → 15 in 1 gesture, `char →` 15 → 1, and `char ←` from 15 → 14 inside the node.

**The correction that follows a crossing can land on the seam in the OTHER frame too.** Measured 2026-09-24,
Chrome force-stopped: long-press `terms` (`9..14`), `char →`. The grab crossed to `0..2` of the paragraph, the
target was 0 (the seam), and the released walk-back reached it, but Chrome announced it as `9..15`, in the
previous node's frame. `walkBackTo` then read 15 as fifteen characters short of 0 and dragged the handle across
the whole node, which destroyed the selection. A held correction did the same once (`1 -> 9..15`, read as 15
back). So `heldCorrect` and `walkBackTo` now carry the snapshot whose node the target is counted in, and a
landing in another node is judged by `SelectionDriver.isSeamOf`. If that landing is the seam, it is the target.
Anywhere else the walk stops where it is, because correcting it would need a grow reckoned in a frame it does not
share. After the fix, four cold runs of `char →`, `char →`, `char ←`, `char ←` went 14 → 15 → 1 → 15 → 14.
That is 16 presses of 16 exact, with the seam walk-back taking 2 gestures and about 1 s. The held-correction
branch is built but has not fired again on the rig.

**From a block's last caret the next character is in another BLOCK, on another row, and a reach
along the row has nothing to enter.** Measured 2026-10-01 on the rig, a served page of one-line `<p>`
rows, `delta` (`28..33`, the END on the row's last caret): `word →` grew sideways eight times and
`char →` pushed six times, all silent, on Chrome 143 and on Chromium r1709360 alike. `crossRowDelta`
cannot help, because it only changes row inside one wrapped node. So `SelectionDriver.blockStep` finds
the neighbouring text node in the tree (`HandleLocator.adjacentNodeCaret`, geometry and length only)
and travels to its row. Three things about that had to be measured:

- **The neighbour's first caret is announced as an EMPTY range.** A hand drag of the END handle onto
 row 004's caret 0 was announced `0..0` in row 004's frame, which the pad reads as no selection, and
 every press after it said so. The same drag onto caret 1 was announced `0..1`, exactly. So a grow
 goes ONE character into the next block. A shrink back out (END at `0..1`, `char ←`) goes on to the
 previous block's END rather than stopping on that empty caret. The START edge mirrors both.
- **The order of the two moves is what makes it land.** A grab aimed straight at row 004's caret 1
 was announced `0..0`. Going straight down onto the row first (`0..33`), then sliding left onto the
 caret, was exact on `0..1`, because along the new row that slide is a shrink. A shrink back
 goes the other way round: along its own row first, then onto the other row.
- **A `<p>`'s inline text node reports the `<p>` as its parent, and the `<p>` reports NO children.**
 So the walk climbs past a parent that does not list the node. What it finds next is the next `<p>`,
 whose box is the block's (`32..688`, against the text's `32..502`). Its first per-character ask is
 refused, so the primer asks it on the announcement. Where the second ask is refused too, a one-line
 block is placed by its average, which is good for the row. The slide along the row is aimed again
 from the landing's own geometry.

Measured after the change, Chrome force-stopped before each run:

| presses | result |
|---|---|
| `char →`, `char ←` from `delta` on row 003, three cold runs | `33 -> 1` (row 004) then `1 -> 33`, 2 gestures, 0.85–1.0 s each |
| `char →` ×2, `char ←` ×3 after that | `1 -> 2 -> 1`, then `1 -> 33` back into row 003, all exact |
| `word →` ×2 from `delta` | `33 -> 4` (row 004's `row `, 1.9 s, 4 gestures), then `4 -> 7` |
| START edge from row 004's `0`: `char ←`, `char →`, `word ←` | `0 -> 32` (row 003), `32 -> 0`, `0 -> 28` (`delta`'s start) |
| `char →`, `char ←`, `word →` from row 006 into the wrapped paragraph | `33 -> 1` (`A`), `1 -> 33`, `33 -> 2` |
| the same END sequence on Chromium r1709360 | identical, press for press |

The word grow ends on the next word's START (`row ` with its space), because after the crossing the
edge is mid-word and Chrome's word walk steps on through the space (the word-walk entries below). Two
controls are unchanged. `chrome://terms`'s inline seam, which is on ONE row, went 14 → 15 → 1 → 15 → 14
in one gesture each, with no block step. And a `TextView` never takes one, because its selection
cannot leave its view (`blockStep` is Blink-only).

**A grab whose move is straight up or down takes no sideways detour.** The grab's slop detour is
horizontal, toward the destination's side. A block grow's first move has no horizontal travel, so the
detour went right. For a START at a row's first caret, that is a shrink. Measured 2026-10-13 on the rig,
Chrome 143, a served page of one-line `<p>` rows with a 16 px margin. Long-press `row` on row 002, `⇄ swap`,
`⇤ word` to `0..8`, then `← char`. The grab at x 14 detoured to x 31, and Chrome moved the START to 1 and
held it there through the move up. The press reported `Moved(0, 1)`, one character the wrong way. A
detour to the left is no better: it was clamped to x 0, and from there Chrome stopped following the drag.
Hand drags with `input motionevent` showed both, and a straight move up from the handle put the START on
row 001. So `HeldPointer.grab` skips the detour when it is short (Blink) and the move's own vertical travel
is past the slop. The page steps' held drags are vertical too, so they lost their detour as well. After
the change, Chrome force-stopped before each run:

| presses | result |
|---|---|
| `← char`, `→ char`, `← char`, `← char` from `0..8` of row 002, 3 runs | `0 -> 19` (row 001, 2 gestures, ~1.0 s), `19 -> 0`, `0 -> 19`, `19 -> 18` |
| control: END `→ char`, `← char`, `⇥ word` from `here` on row 002, 2 runs | `20 -> 1 -> 20`, then `20 -> 4`, as before |
| control: `⇟ page` ×2, `← char`, `→ char`, `⇞ page`, `⤓ end`, `← char`, 2 runs | one band each, `8 -> 7 -> 8`, `end` to row 040's 20, then `20 -> 19` |
| control: the debug target's `TextView` from `bravo` | `11 -> 12 -> 11`, `11 -> 19`, `⇟ page` `19 -> 30`, `30 -> 29 -> 30` |

**On a real article the next block's first row is several nodes, and the step lands in the wrong one.**
Measured 2026-10-05 on the handset (Chrome 154, the extended-selection flag Disabled), on Wikipedia's
`Text_editor`. From `cost.`, the END on 143 of 143, `char →` was announced `Moved(143, 1, crossedNode=true)`
in 2 gestures and 0.96 s. But the next paragraph opens `As ` (3 characters), then the link
`source code` (11), then ` is text, …` (58). The straight-down move landed in the 58-character node,
under the handle's column, and the slide aimed at that node's offset 1. The selection grew by
`As source code ` rather than by `A`. `char ←` from there (`1 -> 11`, the end of `source code`) and
`word →` (`11 -> 3`, onto ` is`) were each exact in 1 gesture. The rig's rows were one node each, so
the rig could not show this.

**So the slide aims at the NEIGHBOUR's caret unless the move landed in the neighbour, and a landing in
any other node of the row is moved onto it.** The tree walk's answer carries the neighbour's length and
box (`NeighbourCaret`). A landing is that node when the length matches and the columns overlap. The row is
not compared, because the walk read row 002's `<p>` at y 479..517 and the announcement from inside it
gave y 525..563 for the same 33 characters. `crossedTarget` counts one character into whatever node the
edge is in, so a landing in the wrong node used to pass as the step. Now `intoTheNeighbour` moves the held
pointer by the distance between the landed handle and the neighbour's, up to twice, and lets go if the
edge is still elsewhere. Measured 2026-10-05 on the rig, Chrome force-stopped before each run, a served
page with `<p>As <a>source code</a> is text, …</p>` and `<p>Al <a>link</a> then more text after it.</p>`
under one-line rows:

| press | old build | new build |
|---|---|---|
| `char →` from `delta` into `As …`, 3 runs | slid to x 278 (` is text`'s offset 1) twice → `0..4` of ` is text`; once `0..3` of `As ` | `0..3` of `As `, walked back to `0..1`, 8 runs of 8, 3 gestures, ~1.05 s |
| `char →` from `delta` into `Al …`, 3 runs | slid to x 146 twice → `0..1` of ` then …`, i.e. grew by `Al link ` | `0..1` of `Al `, 6 runs of 6, 2 gestures, ~1.0 s |
| `char ←` back out of `As ` / `Al ` | not measured | `1 -> 33` of the row above, 9 of 9 |
| one-line rows, `char →`, `char ←` ×2, `word →` | as before | `33 -> 1 -> 33 -> 32 -> 33`, 3 runs of 3 |
| `word →` ×2 from `delta` into `As …` | not measured | `33 -> 3` (all of `As `), then `3 -> 6` |

The correction move itself fired only in one run of a build whose node test also compared rows and so
misread row 002 as another node: from `0..2` it moved the pointer 18 px and landed on `0..1`, the
target, in one move. No landing in a wrong node has occurred with the final test, so that branch is
unproven against a real wrong node.

**The phone confirms the slide (2026-10-05, OnePlus 15, Chrome 154.0.8037.92 with the extended-selection
flag Disabled, `en.m.wikipedia.org/wiki/Text_editor`).** This was the same case that failed on the phone before.
Long-press `cost` (`138..142`), then `char →` to 143 in 1 gesture and 0.73 s. Then `char →` again: the
straight-down move landed in the 58-character ` is text, …` node (`0..19`) and logged `landed in another
node ... sliding to the neighbour's caret`. The slide then landed on `0..1` of the 3-character `As `, and
the screenshot showed `A` highlighted and nothing else. That took 2 gestures and 0.95-0.97 s. `char ←` from
there logged `srcLen=3` and went `1 -> 143`, back onto `cost.`, in 2 gestures and 0.82-0.85 s. Each
direction worked 3 runs of 3. On the phone the slide alone was enough. The correction move (`moving the
pointer`) never fired, so it is still unproven against a real wrong node.

**A superscript's box sits above its line, so "another row" means more than half a line away.**
`blockStep` used to call any neighbour whose bottom differed by more than 1 px another row. A `<sup>`
node's box ends above the line's: on a served `…<span>debugging environment</span>.<sup>[4]</sup> More
text after.` at 20 px, `[4]` ended at y 1029 and the `.` before it at 1045. So `char ←` from `0..1` of
`[4]` was taken as a step into the previous block. The first move landed correctly on `0..1` of the `.`,
but the second move, down onto the `.`'s row, landed in ` More text after.`. The press ended
`Moved(1, 21)` in 4 gestures and 2.3 s. `onTheSameRow` now also calls a gap under half the shorter
one-line box's height the same row. Rows of 38 px sit 46 px apart, so it does not merge them. It falls
back to the 1 px test where either box wraps. Measured 2026-10-13 on the rig, Chrome 143 force-stopped
before each run:

| press | old build | new build |
|---|---|---|
| `char ←` from `0..1` of `[4]`, with the refusal marker | block step, landed in ` More text after.`, 4 gestures | `the same row`, onto `0..1` of `.` in 1 gesture, ~0.6 s, 3 runs of 3 |
| the `char ←` after it | not measured | back to 21 of `environment`, 1 gesture |
| the same presses with the platform answering | not measured | identical, 1 run |
| controls: `char →`, `char ←` across one-line rows 003/004, `word →` into row 004 | as before | `33 -> 1 -> 33`, 2 gestures each; `word →` `33 -> 4` |
| control: the debug target's `TextView` from `bravo` | as before | `11 -> 12 -> 11`, `word →` `11 -> 19` |

A move that ends in another node is now always reported as a crossing (`perform`'s report), because
two nodes' offsets say nothing next to each other. That `char ←` is `0..1` of `[4]` to `0..1` of `.`,
which read as `Moved(1, 1)`, "didn't move", and would have stopped a repeat run.

**A char grow from a node's outer caret into the next node ON THE SAME ROW aims at that node's first
character, not by the node it leaves.** The `char →` from `environment`'s end used to reach 20 px,
`environment`'s average, past the 12 px `.` node, and the grab's slop detour (17 px here) went further
still. It landed on `0..1` of `[4]`, two characters on, and `crossedTarget`, which takes the two nodes
to be adjacent, took that as the step. `SelectionDriver.inlineNeighbour` now finds the next text leaf
where `blockStep` declines because it is on the same row, and the grab aims one character into it. A
landing in any other node is moved onto it by `intoTheNeighbour`, the block step's correction. Measured
2026-10-13 on the rig, Chrome force-stopped before each run, `→ char` ×2, `← char` ×2 from `environment`:

| run | before | after |
|---|---|---|
| Chrome 143, refusal marker on | `21 -> 1` of `[4]`, 4 runs of 4 | `21 -> 1` of `.` `-> 1` of `[4]` `-> 1` of `.` `-> 21`, 1 gesture each, 3 runs of 3 |
| Chrome 143, marker off | not measured | the same, 3 runs of 3 |
| Chromium 157, marker off | not measured | the first press onto `.`, 1 gesture; the next two are the Chromium 157 defects above, unchanged |
| control: `chrome://terms` seam, `→ char` ×2, `← char` ×2 from `terms` | `14 -> 15 -> 1 -> 15 -> 14` | the same, now through the new aim, 3 runs of 3 |
| control: one-line rows, `→ char`, `← char`, `⇥ word` from `delta` | `33 -> 1 -> 33`, `33 -> 4` | the same (the block step), 2 runs of 2 |
| control: the debug target's `TextView` | `11 -> 12 -> 11`, `11 -> 19`; `→ char` from `echo` `AtDocumentEdge` | the same |

The correction move did not fire in these runs: the aimed grab landed in the right node every time.

**Chromium 157 announces an END on an inline seam from the node AFTER it, as `0..0`.** The END's frame
in a node without the anchor is `0..focus`, so a focus on that node's first caret is an empty range,
and the pad read it as no selection. Measured 2026-10-13 on the rig, Chromium r1709360 with the refusal
marker, the same served page: long-press `environment` (`10..21`), `→ char`. The held grab moved the END
exactly one character, past the `.`, and Chromium announced it as `0..0` of `[4]` (`srcLen=3`). The press
ended `HandleLost`, and every press after it said "the pad cannot see a selection". So
`SelectionDriver.endBeforeSeam` reads such an END as the last caret of the text leaf before it in tree
order (`HandleLocator.textLeafBefore`): `0..length`, or `anchor..length` when that leaf holds the
anchor. It does so only when that leaf is on the same row, by bottoms or by tops within half the
shorter box, so a wrapped node's first line counts. A block's first caret is announced `0..0` too, and
its handle is on its own row, so that one is left alone. As with the START's `0..0`, `perform` believes
the reading only while the toolbar is up. Chromium force-stopped before each run:

| press | before | after |
|---|---|---|
| `→ char` from `environment`, marker on, 3 runs (one after `⇄ swap` ×2) | `0..0` of `[4]`, `HandleLost`, then blind | `0..0` of `[4]` read as `0..1` of `.`, `Moved(21, 1)`, 1 gesture, 0.73-0.89 s |
| `← char` back, then `→ char` again | not measured | `1 -> 21` in 2 gestures, then `21 -> 1` again |
| the same `→ char`, marker off | not measured | identical, 1 gesture |
| control: Chrome 143, marker off: `→`, `→`, `←`, `←` | not measured | `21 -> 1` (`.`) `-> 1` (`[4]`) `-> 1` (`.`) `-> 21`, 1 gesture each, with no re-homing |
| control: one-line rows on Chromium 157, `→ char` across rows 003/004 and back | as before | `33 -> 1 -> 33`, 2 gestures each, with no re-homing |

**Chromium 157 keeps no handle position INSIDE `[4]`, so from the `.` the smallest step is to `[4]`'s
end.** The grab aimed one character into `[4]` was announced `0..0` of `[4]`, the same caret it started
on. The held correction then counted its target in the `.`'s 1-character frame, as 2, and the snap-through,
sized by the `.`'s 12 px, pushed on to `0..1` of ` More text after.`, past `[4]` and the space, 2 runs of 2.
Released drags by hand (`input swipe`, the END handle 18 px right of its caret, `[4]` at x 460..500)
show the shape. From the seam, drags to x 500 and 510 stayed on the seam, and one to 520 put the edge on
`[4]`'s end. From that end, drags back to 500, 494 and 488 all went back to the seam, and one to 506 stayed.
So `SelectionDriver.throughTheNeighbour` pushes the held pointer on by the NEIGHBOUR's average character,
never past its far caret, until the edge leaves the seam. `backFromTheSnap` then shrinks back toward the
neighbour's first character with the pointer still down. Where that lands back on the seam, it puts the
pointer back where the snap was and takes the neighbour's end as the step. Measured 2026-10-13 on the rig,
Chromium force-stopped before each run, long-press `environment`, then `→ char` ×2 and `← char` ×2:

| run | before | after |
|---|---|---|
| Chromium 157, marker off, 3 runs | `21 -> 1` (`.`) `-> 1` of ` More text after.`, 3-4 gestures | `21 -> 1` (`.`) `-> 3` (`[4]`'s end, 4-5 gestures, 1.4-1.6 s) `-> 1` (`.`, 1 gesture) `-> 21` |
| Chromium 157, refusal marker on, 2 runs | the same skip | the same as marker off |
| control: Chrome 143, 2 runs | `21 -> 1 -> 1 -> 1 -> 21`, 1 gesture each | unchanged, never enters the push |
| control: one-line rows on Chromium 157, `→ char`, `← char`, `⇥ word` from `delta` | `33 -> 1 -> 33`, `33 -> 4` | the same in the runs whose first press grabbed (see below) |
| control: the debug target's `TextView` from `bravo` | `11 -> 12 -> 11`, `11 -> 19` | the same |

A shrink from `[4]`'s end lands on the `.`'s end, which is the same seam, so the two presses mirror each
other.

**The GRAB can snap there too, and it goes through the same judgement.** After `→ char` ×3 to ` More`
and `← char` ×2 back to the `.`'s end, the `→ char` grab from x 480 to 490 landed straight on `[4]`'s end.
The held correction then lifted for a walk of two, and the released walk-back went back to the seam:
`Moved(1, 1)`, "didn't move". Now a grab that lands in the inline neighbour goes to `backFromTheSnap`.
Measured 2026-10-13 on the rig, Chromium r1709360, no marker, Chromium force-stopped before each run: that
press went `1 -> 3` in 3 gestures and 2.2 s, 3 runs of 3 (it logs `the app keeps no caret between the seam
and 3`). The whole sequence went `21 -> 1 -> 3 -> 1`, `1 -> 3 -> 1`, `1 -> 3 -> 1`. The controls are
unchanged: Chrome 143 went `21 -> 1 -> 1 -> 2 -> 1 -> 1`, and the debug target's `TextView` went
`11 -> 12 -> 11`, `11 -> 19`. Three of the five rows runs ended their first press before any of this code ran. On a cold
Chromium the `<p>`'s text node reported the block's box, so the END was aimed by its average at x 706,
inside the right edge zone. One run grabbed there and ended `HandleLost`, and two refused with `InEdgeZone`.

**A short phrase that starts late on a line and wraps passes the one-line test, and its box then puts
its first carets on its LAST line.** ` More text after.` after `[4]` is `(32, 997 - 678, 1093)`: two lines,
each across the whole width, so its 17 characters average 38 px over a 96 px box, a ratio of 0.4, which
the one-line test reads as one line. Wrapping did not divide the width, so nothing about the shape
says it wraps. Where Chromium had not measured it, its first caret was placed by that average at x 70
and at the box's bottom, on the second line. A `→ char` from `[4]`'s end then took it for another row,
took the block step, and grew into the next paragraph (`Moved(3, 27)`). A `← char` from its `0..1`
grabbed at y 1121, on the second line, with the handle drawn on the first, and ended `HandleLost`.
Three fixes, all in `HandleLocator`:

- **The neighbour's caret is read at the seam** (`seamCaret`) when the neighbour's box is half again
  taller than the source's one-line box, starts on its row (tops forward, bottoms backward, within half
  its height), and reaches back past the seam. A node that stays on the row never does that.
- **An unmeasured caret inside such a node is placed along the row it continues** (`rowContinuedFrom`):
  from the end of the node before it, by that node's average, and refused where that runs past the box.
  The step is sized by the same average (`seamAverage`).
- **A measured caret a line above a one-line box's bottom gives the row** (`measuredRowAbove`), where
  the row used to stay `bounds.bottom` regardless.

Measured 2026-10-13 on the rig, Chromium r1709360 force-stopped before each run, long-press
`environment`, then the presses below:

| run | before | after |
|---|---|---|
| `→ char` ×3, marker on or off | `21 -> 1 -> 3`, then `3 -> 0` of the next paragraph, via `0..27` (marker off) | `21 -> 1 -> 3 -> 1` of ` More`, the last in 1 gesture, 8 runs of 8 |
| then `← char`, `→ char`, `← char`, `← char`, marker on | `← char` `HandleLost`, then blind | `1 -> 3` (`[4]`'s end) `-> 1 -> 3 -> 1` (`.`), 1 gesture each, 2 runs of 2 |
| `→ char` ×4, `← char` ×2, `→ char`, `← char`, marker off | `← char` from `0..1` grabbed at y 1121, `HandleLost` | `21 -> 1 -> 3 -> 1 -> 2`, `2 -> 1 -> 3`, `3 -> 1`, `1 -> 3`, 2 runs of 2 |
| control: Chrome 143, `→ char` ×3, `← char` ×2 | `21 -> 1 -> 1 -> 2 -> 1 -> 1` | the same |
| control: one-line rows, `→ char`, `← char`, `⇥ word` from `delta`, Chrome 143 | `33 -> 1 -> 33`, `33 -> 4` | the same |
| control: the debug target's `TextView` from `bravo` | `11 -> 12 -> 11`, `11 -> 19` | the same |

On Chromium the rows control's first `→ char` ended `HandleLost` at x 706, the cold `<p>` box defect
above; its `← char` and `⇥ word` were exact.

**`word →` from the word before a block's trailing `.` lands on the block's last caret, and that is
not Chrome's next-word-start hold.** Measured 2026-10-05 on the handset, Wikipedia's `Text_editor`: from
`cost` (`138..142`) the first grow landed on 143, past the period. `landedOnNextWordStart` read it as the
hold, and eleven more reaches along the row announced nothing: `Moved(142, 143)` in 4.8 s and 12 gestures.
Along that row there is no next word. So when a one-character landing from a known boundary is a caret
`blockStep` would leave from (`SelectionDriver.endsItsBlock`), the press ends there, and the next `word →`
takes the block step. An inline node's end is not caught, because its neighbour is on the same row and
`blockStep` declines. Measured on the rig, Chrome force-stopped before each run, a served page whose
row 003 ends `charlie cost.`:

| press | old build | new build |
|---|---|---|
| `word →` from `cost` (`28..32` of 33) | `32 -> 33` in 5.5 s, 12 gestures | `32 -> 33` in 0.35–0.41 s, 1 gesture, 3 runs of 3 |
| `word →` again | not measured | `33 -> 4` of row 004 (`row `), 1.9 s, 4 gestures, 3 of 3 |
| control: `word →` ×2 from `alpha` on the same row | not measured | `13 -> 19`, then `19 -> 20` read as the hold and escalated on to 27, as before |

**The phone confirms both presses (2026-10-05 and 2026-10-10, OnePlus 15, Chrome 154.0.8037.92 with the
extended-selection flag Disabled, `en.m.wikipedia.org/wiki/Text_editor`).** Long-press `cost` seeded
`138..142`, and `word →` logged `landed on 143, one past the boundary 142, and that is the block's outer
caret — taking it as the step`. That is `Moved(142, 143)` in 1 gesture and 353-357 ms, against 12 gestures and
4.8 s before the fix. The second `word →` took the block step. The move onto the next row landed in the
58-character ` is text, …` node (`0..19`), the slide moved it onto `0..1` of `As `, and the word walk held at 3:
`Moved(143, 3, crossedNode=true)` in 5 gestures and 2.1 s. The screenshot showed `cost.` and `As ` selected.
That is the same shape as the rig's `33 -> 4`. The first press has run twice, once per sitting, and the second once.

**A block-level node's `getBoundsInScreen` is the block box, not the text box.** Long-pressing the centre
of an `h1` whose bounds run 56..1218 but whose glyphs end at 547 hits empty space and selects nothing.
Inline nodes hug their text; block nodes do not.

**A selection inside such a node can be announced from the block, and its average then aims nowhere
near the handle.** On the handset (Chrome 154, the extended-selection flag Disabled), `editor` in
Wikipedia's `Text editor` heading was announced from an 11-character node with the heading's
block-wide box. Both per-character asks were refused, and the END was aimed at x 2477 against glyphs
ending near 680. The rig shows the box but not the refusal. On a served
`<h1><span>Text editor</span></h1>` (`28px serif`, `padding-top: 300px`), Chrome 143's node hugs its
text (`average=322.0`), and Chromium 157's has the block's box (`Rect(32, 913 - 688, 979)`, against
glyphs ending at 322). Both answer the second ask, so the box is never used there.

**So where the platform refuses, the toolbar decides whether the average may be used**
(`HandleLocator.averageOutrunsToolbar`). The toolbar's centre tracks the selection's, and the other
edge is inside the box, so `END <= 2·centre − box.left` and `START >= 2·centre − box.right`. Where the
fresh selection was made in this node, both edges are tested, because a box that misplaces one
misplaces both. An average outside those bounds, beyond 24 dp of slack, refuses with nothing touched.
The press ends `ColumnUnknown` ("this block won't say where its text ends"), because the acquire scan
would probe along the row from the toolbar, and a missed probe is a touch on the page. A toolbar
within 24 dp of a screen edge has been pushed in and its centre moved, so the bound it tightens is
dropped. On `editor` it sat at x 32, centred at 311 against the selection's ~241. The tighter rule,
that each edge is on its own side of the centre, is not used for the same reason. Measured
2026-10-05 on the rig with Chromium 157 and the marker file below, Chromium force-stopped before
each run:

| press | before | after |
|---|---|---|
| `char →` from `editor` (`5..11`) | grab at x 706 against a handle drawn at ~344; `HandleLost` in 505 ms, 1 gesture | `ColumnUnknown` in 28-45 ms, 0 gestures, 3 runs of 3 |
| `word →`, `⤓ end` | not measured | `ColumnUnknown`, 0 gestures |
| `⇄ swap`, `char ←` (the START) | grab at x 312, which is the END handle: shrank to `5..9`, then `HandleLost` | `ColumnUnknown` in 28 ms, 0 gestures |

The controls are unchanged. Chrome 143 with the marker, whose box hugs: `char ←`, `char →`, and
after a swap `char ←`, each one character in 1 gesture. `Text` at the left margin, `char →` exact.
Chromium 157 without the marker: `char ←`, `char →`, `⇄ swap`, `char ←`, `char →`, each exact in 1
gesture. The debug target's `TextView` with the marker: `char →`, `char ←`, `word →` (19 -> 25), each
exact. With no toolbar on screen there is no bound and the average is used as before.

**The phone confirms the refusal, and shows that the double refusal happens only on a cold Chrome**
(2026-10-10, OnePlus 15, landscape, Chrome 154 with the extended-selection flag Disabled,
`en.wikipedia.org/wiki/Text_editor`). Chrome was force-stopped before each cold run. In each one,
long-pressing `editor` seeded `5..11` from an 11-character node with the heading's block-wide box
`Rect(326, 596 - 2587, 732)`. The first `→ char` logged `the box's average puts the carets at
1353.7273..2587.0, which the toolbar centred at 1058.0 rules out` and ended `ColumnUnknown` in 22-25 ms
with 0 gestures. The status line read `this block won't say where its text ends`, the selection was
intact and the pad stayed. That happened in 3 cold runs of 3. The next `→ char` on the same selection,
and every first press against a warm Chrome (3 of 3), was answered (`one-line caret measured=823.0
average=2587.0`). That press took the block step onto `0..1` of the `Article` tab in 2 gestures and
1.06-1.08 s. On the way, the move onto the row landed in `Talk` and slid to the neighbour's caret. So
the refusal is the cold first press's, and the press after it works.

**`adb shell run-as dev.mnaoumov.asc touch files/force-refuse-character-rects`, then `rig.ps1 rebind`,
makes every per-character ask refuse** on a debuggable build (it logs `debug: refusing every
per-character ask`), which is how the rig reproduces that phone case. `rm` it and rebind to undo.

**The toolbar bound needs the OTHER edge inside the box, so it is asked only where the anchor is
in the announcing node.** `END <= 2·centre − box.left` holds only while the START is at or right
of `box.left`. An END grown into a later inline node is announced `0..focus` there, with the anchor,
and so the START, in an earlier node to the left. On the handset (2026-10-12, Wikipedia) the END sat
in `environment.`'s 1-character `.` node, `Rect(675, 1810 - 703, 1863)`. Its average, 703, was the
true caret, and a toolbar centred at 585 refused it on every press (`ColumnUnknown`). A 1-character
node's honest rectangle is its whole box, so the per-character ask could not vouch for it either.
`averageOutrunsToolbar` now returns false when the other edge is not known to be in the node, for
the START as well (its bound needs the END at or left of `box.right`). Measured 2026-10-12 on the rig
with the refusal marker, Chrome 143, Chrome force-stopped before each run, a served
`<p>… a <span>debugging environment</span>.<sup>[4]</sup> …</p>` (long-press `environment`, then
`→ char` lands `0..1` of the 3-character `[4]` node, `Rect(460, 1093 - 500, 1133)`, toolbar centred
at 349.5):

| press, END in `[4]` | old build | new build |
|---|---|---|
| `→ char`, `← char`, `⇞ page` | `ColumnUnknown` each, 0 gestures (`carets at 460.0..473.3, which the toolbar centred at 349.5 rules out`) | `1 -> 2 -> 1`, 1 gesture each, 3 runs of 3 |
| `⇞ page` (page at its top) | not measured | no swipe, crossed the anchor onto the heading's offset 0, then `0 -> 1 -> 0` as the START, 2 runs of 2 |
| control: `editor` in `<h1><span>Text editor</span></h1>` on Chromium 157 (box `32..688`) | `ColumnUnknown` | `ColumnUnknown` on the END and, after `⇄ swap`, on the START, 3 runs of 3 |

**The phone confirms it (2026-10-14, OnePlus 15, Chrome 154.0.8037.127, `en.wikipedia.org/wiki/Text_editor`).**
Chrome announced selections the old way (`seeded from a fresh selection`), so the extended-selection
trial was not active. Scroll the page until `debugging environment.[4]` is above the pad, then long-press
`environment`. That seeded `1..12`, and Chrome then announced `0..1` of the 1-character `.` node,
`Rect(675, 1887 - 703, 1940)`. The `[4]` beside it is three 1-character nodes. Every per-character ask
was refused, and `awaitCaretMeasured` waited its whole second each time. No press logged `the box's average
puts the carets at` or ended `ColumnUnknown`:

| press, END in the `.` node | result |
|---|---|
| `⇞ page` | no swipe (`a scroll of -44.0 is under 56.0 px`), the held drag crossed the anchor onto the caption above, now the START, `Moved(1, 0)` in 1.7 s and 1 gesture; `→ char` `0 -> 1`, `← char` `1 -> 0`, 1 gesture each |
| `→ char`, `← char`, `→ char`, `← char` from a fresh selection | `inline: leaving the node for the next one on its row`, onto `0..1` of `[4]`'s `[` (`Rect(700, 1565 - 721, 1618)`), then back onto the `.`, 2 runs of 2, 1 gesture each, 1.6-1.8 s with the 1 s wait; the screenshot showed `environment.` selected |

**On a cold Chrome the heading's first press no longer reaches the refusal, because the caret wait answers it
first.** Chrome was force-stopped before each of 3 runs. Long-press `editor` in the `Text editor` heading
seeded `5..11` from the heading's block-wide box (`average=1218.0`). Each first `→ char` logged `the platform
will not measure the END's caret yet`, then `warm: measured after 100ms` and `one-line caret measured=549.0`.
It then took the block step onto `0..1` of the `Article` tab, `Moved(11, 1)` in 1.1 s and 2 gestures, and the
screenshot showed `editor` and `A` selected. That held in 3 runs of 3. The 2026-10-10 refusal predates the
wait (*On a cold Chromium 157 the second ask can be refused too*), so on the phone the toolbar bound is now
only reached where the wait's second runs out. That did not happen here.

**Long-pressing a link opens Chrome's link context menu**, not a selection.

**`screencap` returns pure black on `FLAG_SECURE` apps** — Tor Browser is one. The "only a screenshot is
evidence" rule cannot be honoured there, so corroborate with the selection event plus the floating
action-mode window and say which evidence you actually have.

**Handle geometry is per-app.** Chrome draws teardrops below the baseline; Google Keep draws circles at
the selection's top-left and bottom-right. Do not hardcode one offset pair.

**Nothing can tell you whether an offset is a word boundary. Only a SNAP can, and a snap reports a
destination rather than classifying an origin.** This is the reasoning behind
`SelectionDriver.knownBoundaries`, and it is worth having in full, because every route out of it was
considered and each one is closed:

- *Ask the app.* Growing snaps to a boundary — but growing from a boundary and growing from mid-word
 both land on one, so the landing says nothing about where it started. The probe also moves the
 selection, so answering "is the edge on a boundary?" requires performing the step being asked
 about.
- *Ask the geometry.* Per-character rects on page content DO exist, once the node has been asked
 twice (the gotcha above), so this reason has changed — but the conclusion has not. A space is not
 distinguishable from a narrow glyph by width, so a rectangle cannot say whether the edge sits on a
 word boundary, and trying to make it would be reading the text by another route.
- *Ask the granularity actions.* `ACTION_NEXT_AT_MOVEMENT_GRANULARITY` with the WORD mask returns
 `true` and moves nothing on page content — one of the earliest NO-GO measurements here.
- *Ask the toolbar.* Its centre tracks the selection's centre, which gives a width in pixels and no
 word structure at all.
- *Ask a screenshot.* Ruled out by the project's premise, and `screencap` is black on `FLAG_SECURE`
 apps anyway.

So there are exactly **two** sources of boundary knowledge, both of them landings rather than
classifications: an offset a **grow** snapped to, and both edges of a selection the user has just
**long-pressed**. A shrink reveals nothing whatsoever — it is character-granular, so where it stops
says only where the finger was. Recording a shrink's landing as a boundary is therefore not an
approximation but a falsehood, and it was one: every character step wrote its landing into the set,
and `word ←` then jumped to a mid-word offset and the pad announced it as a word.

**A fresh selection is distinguishable from a moved handle with no text and no timer** — a long-press
replaces BOTH edges, while a handle drag moves one and leaves the anchor. That is what lets the two
free boundaries be harvested, and it fails safe: an unrecognised long-press seeds nothing, which is
the old behaviour.

**Across a node change, "both edges changed" says nothing, because the offsets restart in the new
node's frame.** A hand drag of the END handle from row 003 into row 015 of a served page logged
`seeded from a fresh selection at 0..27`, with 27 simply where the finger stopped. So a crossing is
judged against what the selection last announced in the node it lands in (`isFreshSelection`). A node it
has not been in announces a drag as `0..focus`, so an announcement there starting at 0 is a drag.
Measured 2026-09-28 on the rig, Chrome, a served page of one-line rows:

| announcement | before the fix | now |
|---|---|---|
| END dragged from `bravo` (row 004) to `bra\|vo` in row 005 | would seed `[0, 17]`, mid-word | `0..19` then `0..17`, nothing seeded; `⇤ word` then says "no known word boundary yet" |
| the same END dragged back into row 004, inside `charlie` | would seed `[14, 21]` | `14..21`, not fresh; the set keeps `[14, 19]` |
| long-press `alpha` in row 005, while row 002's `charlie` is selected | seeded | `[8, 13]`, seeded |
| long-press `row`, the FIRST word of row 006 | seeded | `0..3`, nothing seeded |

The last row is the price. A long-press on a node's first word looks exactly like a drag into it,
so it seeds nothing, which is the fail-safe direction.

**All three of those claims are now measured, not reasoned** (2026-09-23, the rig, against the debug
target's `alpha bravo charlie delta echo` whose boundaries are 5, 11, 19 and 25, so every expected
number was known before the press):

- **The seed.** A long-press on "charlie" logged `seeded from a fresh selection at 12..19:
 boundaries=[12, 19]` — its two boundaries exactly, once, and never again across the thirteen pad
 presses that followed. The fail-safe half showed itself by accident: long-pressing the *same* word a
 second time seeded nothing, because reproducing both edges is indistinguishable from a no-op.
- **The retrace.** Three `char →` (19 → 20 → 21 → 22, one gesture each), then one `⇤ word`:
 `shrink 1: 22 -> target 19, lose 3` and `Moved(fromOffset=22, toOffset=19) in 399ms, 1 gesture(s)`.
 One gesture, straight to the boundary. `no known word boundary yet` appeared nowhere in the sitting.
- **No poisoning, in both directions.** `boundaries=[12, 19]` on every press line of the run and
 nothing else, ever. Four `char ←` landed on 18, 17, 16, 15 and added none of them; a `⇤ word` from
 15 then targeted **12**, not one of those four landings, which is the whole point. The two
 directions are caught by different halves of `recordBoundary` — `char →` by the "moved more than one
 character" test, `char ←` on the END edge by `growsSelection` — and both were exercised.

**A boundary learned by a grow that crossed into the next node survives the next press.** This is
the fourth claim, and it needed a node crossing, so it was measured on Chrome's `ERR_INVALID_URL`
page on 2026-09-24. A long-press on `terms` seeded `[9, 14]` in the 15-character `"chrome://terms/"`
node. One `word →` then grew across into the 81-character wrapped paragraph and landed on `0..1`.
The press after it read:

```
WORD_RIGHT edge=END at 9..14 moving=14 srcLen=15 oneLine=true boundaries=[9, 14]
  grow try 1: handle=(470.0, 795.0) reach=21.0 -> 0..1 bounds=Rect(48, 731 - 584, 863)
WORD_RIGHT edge=END at 0..1 moving=1 srcLen=81 oneLine=false boundaries=[1]
```

So `[9, 14]`, the offsets of the node that was left, are gone, and `1`, the crossing's landing, is
kept. That is the ordering `recordBoundary` exists for: it re-keys before it adds. The controls:

- **A grow inside one node does not re-key.** Three `word →` presses from `4..11` in the first node
 gave `[4, 11]`, then `[4, 11, 13]`, then `[4, 11, 13, 15]`.
- **A node change with no grow clears.** A long-press in the second node seeded `[9, 14]`, and one
 in the first node then seeded `[4, 11]` and not the union. Both nodes are 15 characters long, so
 the bounds in the key are what told them apart. (The key is the node's own identity since
 2026-09-28, which tells them apart too; see *Page, start and end* for why bounds stopped being enough.)
- **A scroll does NOT clear, and that is correct.** `Google` seeded `[0, 6]` on `chrome://version/`.
 After the page scrolled ~90 px, the next press still read `[0, 6]`, and its grab was aimed at the
 new row (y 375). The key is taken from the last *announcement*, and a scroll announces nothing. The
 node and its offsets are unchanged, so keeping the set is the right answer. The earlier plan
 expected a clear here, and that expectation was wrong.

**A crossing is held to the same tests as a grow inside one node, and a CHARACTER step's crossing is
never recorded.** `recordBoundary` used to believe any landing across a node outright, on the theory
that a grow only crosses by snapping. A held `char →` crosses where the finger is. Measured 2026-09-24,
Chrome force-stopped: from `9..15` of `"chrome://terms/"` the grab landed on `0..2`, inside `might`,
and the next press read `boundaries=[2]`. From `9..14`, with 14 seeded by the long-press, it landed on
`0..2` too, three characters on, so no distance test separates it from a snap. After the change,
three presses of three left the set empty. The other crossings now need a known origin and a grow of
more than one character, counted across the seam (`grownAcrossSeam`). The `word →` from `terms`
above still records 1.

**A held character step records NO boundary at all, inside a node as well as across one.** It used to
record its first landing from a known boundary, on the theory that a `TextView` snaps there to the
next word's end. On Chrome that landing is where the grab's 60 px outward detour left the handle.
Measured 2026-09-24 on the error page, Chrome force-stopped: `char →` from 21, the end of
`temporarily` (seeded `[10, 21]`), landed on 23 or 24, inside `down` (22..26), and that offset went
into the set. The next `word →` then started from a "known boundary" mid-word, and a released
walk-back from it recorded 24 as the next word's start. After the change, three presses of three still
ended on 22 in 2 gestures, the set stayed `[10, 21]`, and the `word →` after each landed on 26 twice.
The third run landed on 27. That was a separate defect, a late walk announcement, and it is fixed
(see *From the next word's START* below).

The alternative was to keep the recording and detour the grab toward the anchor, as the word walk
does. It was measured and refused. Chrome then landed on 22 exactly, 4 presses of 4. But on the
`TextView`, the grab from `bravo`'s end announced nothing 2 times of 3 and fell back to the released
path, at 4-7 gestures and 2-9 s a press.

Dropping the recording has cost the `TextView` nothing that was measured. With the outward detour the
grab does not snap there: 6 presses of 6 went 11 -> 12 in one gesture at about 1 s, before and after
the change. The snaps that do happen are recorded by the held snap-through and the released grow.

**A grow snaps only when it STARTS on a word boundary, so a landing counts as a boundary only when
the grow started on one the set already knows.** `SelectionHandleView.updatePosition` snaps only
while `mInWord` is false, and `mInWord` is recomputed from the handle's own offset. From inside a word
the handle follows the finger one character at a time. Measured 2026-09-24 on `alpha bravo charlie
delta echo`: two `char →` took the end to 21, inside `delta`, and a `word →` with a 1.5-character
reach landed on 23. The pad announced that as a word and put 23 into the set. `recordBoundary` now
refuses a grow whose origin is not a known boundary, and logs `not recording <n>`.

**`word →` from such an edge walks the HELD pointer one character at a time until the handle holds
still** (`growToWordEnd`). Once it reaches the word's end, `mInWord` turns false and the handle waits
for the finger to pass the middle of the next word. So a silent push means the edge is on a word's
end, and that is recorded. It takes ONE silent push when the push was sized by a glyph the platform
measured, which cannot stay inside its own cell, and two when it was sized by an average. Same
fixture, same sitting: 21 walked 22, 23, 24, 25, then two silent pushes, so the step ends on 25 in
2.0 s and 6 gestures; with the one-push rule (2026-09-24) the same walk ends on 25 in 2.1 s and 5.
`⇤ word` from there then retraced to 19 in one gesture. From 13 inside `charlie` the walk ended on 19.

**Chrome does not hold at the word's end: it steps on through the space and holds at the next
word's START.** Measured 2026-09-24 on the error page, from 19 inside `temporarily` (10..21): the walk
went 20, 21, 22 one character a push, was silent once at 22, and then jumped to 26, the end of
`down`. So on Chrome the walk stops on a boundary the set already KNOWS, which after a long-press is
the word's own end: three presses of three landed on 21 in 2 gestures. A jump of more than one is
still taken as the step and recorded nowhere.

**From the next word's START, Chrome's walk reads silent and the push lands late.** Measured 2026-09-24
on the error page, Chrome force-stopped: long-press `temporarily` (10..21), `char →` to 22, the start of
`down`, then `word →`. The grab and the second push both read `10..22`. Then the walk let go and handed
the press to the plain grow. By then the second push's announcement of 26, the end of `down`, had
arrived. So the grow started from 26 and went one more, into `or`, and the press reported `26 -> 27`
in 1 run of 3. `releaseThenGrow` now lifts, waits for the announcements to stop, and grows only if the
edge is still where the press began. After the change, 6 runs of 6 reported `22 -> 26` in 2 gestures and
0.8-2.0 s, every one through the late-push branch. The debug target's walk still went `21 -> 25` in 1.

**Where the set knows no end, Chrome's walk stops on the next word's START**, at the first silent
push, which is the one-push rule above. Measured 2026-09-24 on a served copy of the error page's
sentence, Chrome force-stopped each run: long-press `temporarily` (21..32), three `char ←` to 29, then
`word →` with the known-boundary stop switched off for the measurement, which was then the only way to
get a mid-word Chrome edge with no known end (see below; a `char →` from a word's start now gets one). Before: 30, 31, 32, 33, silent once, then a jump
to 37, the end of `down`. After: stopped on 33 in 5 gestures and 1.8 s, three runs of three, recording
33. The next `word →` then grew from 33 to 37 in 2. The stop is a word's start, so the step includes the
space. Shrinking one more to the word's end would assume one space between words, and nothing measures
that.

**A walk back over several characters is sized by the run's outer EDGES, not by its widths added up,
because Chrome's per-character rectangles overlap.** A `char` step from a word boundary, in the
direction of travel, snaps a whole word and walks back. Measured 2026-09-24 on the served sentence,
Chrome force-stopped: `char →` from 21 (the start of `temporarily`) snapped to 32, and the shrink back
aimed at 22 landed on 21, three rounds of three, `Moved(21, 21)` in 12.7 s and 22 gestures. `char ←`
from 32 on the START edge did the mirror. The ten widths of `emporarily` came back as 18, 30, 20, 20,
12, 18, 12, 8, 8, 16, 162 px in all, between carets 150 px apart. Every width is even, i.e. whole page
pixels at 2x, so each rectangle looks rounded out. The finger stopped 12 px short, inside the `t`, and
Chrome rounded the edge back onto the origin. `HandleLocator.characterRun` now returns the run's left
and right edges, and `measuredReach` uses their difference. After the change, three presses of three
each way: `21 -> 22` in 8 gestures and 5-6 s, and `32 -> 31` in 6 gestures and 3.7 s. Most of that time
is the grow's escalation up to the snap. The walk back itself landed in one drag. The debug target's
`TextView` walk-backs (19 -> 12, 14 -> 11, 25 -> 19) still land in one drag.

**Two things made that walk look like a granularity problem when it was not.** It used to land on 22
in one push from 20, which read as Chrome jumping the end. It was the `STEP_PX` = 12 floor on the
walk's reaches, the same floor the held char step had already dropped. The 12 px grab over an 8 px
`l` left the pointer 4 px into the `y`, so the next push, one `y` wide, ended 4 px into the space.
With the floor gone, the grab's usual 60 px slop detour went past the destination first and carried
the handle into `down`: four grabs of four landed on 23, and Chrome kept it there when the pointer
came back. The walk's grab now detours toward the anchor (`grabAndHold(detourBack = true)`). The
debug target's walk is unchanged by both: 21 -> 25 through `delta`, on the hold.

**The same hold catches a released `word →` from a word's end: one character on is the next word's
START, not a step.** Measured 2026-09-24 on the served seven-line paragraph, Chrome force-stopped:
from 43, the end of `of`, a 1.5-glyph reach landed on 44, the start of `selecting`, and the pad used to
report that as the word. A `TextView` never lands there from a boundary; it announces nothing until the
middle is passed. So `growOneUnit` now takes a one-character landing from a known boundary as Chrome's
hold (`landedOnNextWordStart`). It records the hold as a boundary and escalates from the ORIGIN's
handle, so the reaches follow the schedule a silent try would have had. It also records wherever the
resumed try lands, because from the hold Chrome snaps by word. Three presses of three: 43 -> 53
(`selecting`) in 5 gestures and 2.2-2.6 s, and 53 -> 55 over the one-letter ` a` in 2 gestures. The
next press then starts on a known boundary instead of falling into the walk. The one case it gets
wrong is a one-letter word grown from its own START, where +1 really is the word; the press then ends
a space too far.

**A word grow's drag goes straight at its reach: the slop detour must not travel PAST it.** Every
released drag used to detour 32 px (twice the touch slop) beyond the handle before coming back, so a
miss reads as a scroll rather than a tap. Chrome sees that visit. From 40, the end of `frustration`, the
detour passed the middle of `of` (41..43) and snapped to its end, and the return shrank one character at
a time to the finger: `word →` landed on 42, mid-word, three cold runs of three, and 42 went into the
set as a snap. `growOneUnit` now drags word grows with `pastTarget = false`, which keeps the detour at
the reach, or at one pixel past the touch slop when the reach is shorter. Measured 2026-09-24 on the rig,
Chrome force-stopped before each run, three `word →` from `frustration`, three runs of three: 40 -> 41,
escalated to 43 (`of`) in 2 gestures and 0.7 s; 43 -> 53 (`selecting`) in 5; 53 -> 55 (` a`) in 2. The
set held `[29, 40, 41, 43, 44, 53]` and nothing mid-word. The debug target's `TextView` went 11 -> 19 ->
25 -> 30 from `bravo`, every press exact. Two variants were measured and refused. A detour toward the
anchor was exact on Chrome, but on the `TextView` it put the handle back inside `bravo`, and from mid-word
the grow is character-granular (11 -> 12 -> 14, recorded). A 1 px pull-back before the lift changed
nothing on Chrome, so it is not there.

**`word ←` onto the anchor does NOT collapse the selection — a dragged handle has a one-character
floor.** This paragraph used to claim the opposite, reasoned from the desktop's `Ctrl+Shift+Left` and
never measured. Measured 2026-09-23 on the rig, three times from two different starting selections,
identical every time: the retrace picks the anchor correctly, the first drag gets to within one
character of it, and every gesture after that accomplishes nothing while the reach escalates.

```
WORD_LEFT edge=END at 12..19 moving=19 srcLen=30 oneLine=true boundaries=[12, 19]
 shrink 1: 19 -> target 12, lose 7 x 19.0px, reach=-126.35 -> 12..13
 shrink 2: 13 -> target 12, lose 1 x 19.0px, reach=-22.8 -> nothing
 shrink 3..8: identical, reach escalating -33.25 -> -85.5 -> nothing
WORD_LEFT -> Moved(fromOffset=19, toOffset=13) in 3648ms, 8 gesture(s)
```

**The control is what makes it the platform's floor rather than the retrace's arithmetic**: a plain
`char ←` from that same one-character selection also moves nothing and reports `HandleLost` in 627 ms.
So the other half of the old claim — "a char step from a one-character selection has always done the
same" — is false in the same direction. The target app keeps the last character selected and its
handles up; nothing goes blind, and the pad says `moved 19 → 13`, which is honest.

**On Chrome the floor is not a floor: the correction onto the anchor CROSSES it.** Measured
2026-09-24 on the served seven-line paragraph: `word ←` from `23..28` (`small`) reached `23..24` on
the first drag, and the correction onto 23 carried the handle over the anchor to `22..23`, the space
before the word, reported as `HandleLost`. The selection was no longer the one the user made.

**So `word ←` now targets the floor, never the anchor** (`shrinkByWord`): a retrace whose nearest
boundary IS the anchor aims one character short of it, and a press that starts one character from
the anchor touches nothing and ends `AtFloor` ("one character left — the app keeps it"). Measured
after the change, Chrome force-stopped each time, three runs of three: `28 -> 24` in 1 gesture and
0.4-0.9 s, then `AtFloor` in 0 ms and 0 gestures. The `TextView` START edge does the same from
`12..19` after a swap: `12 -> 18` in 1 gesture, then `AtFloor`. A boundary short of the anchor is
still the target: from `23..40`, `word ←` went to 29 and then 28, as before.

The floor applies only while the anchor is known to be in the announcing node. That is the node
of the last fresh selection (`anchorNodeKey`). After a grow into the next node, Chrome announces
`0..1` in THAT node's frame, and the `0` is where the node starts, not the anchor. A shrink from there
legitimately crosses the seam, so it is left alone: from `terms` grown into the paragraph, `word ←`
from `0..1` still crossed back to `9..14`.

The `TextView` END edge does the same, measured 2026-09-24 once the toolbar lookup stopped taking
the handle's own window for the toolbar: from `6..11` (`bravo`), `word ←` went `11 -> 7` in 1 gesture,
and the next one ended `AtFloor` with 0 gestures. `char ←` from a one-character selection is not guarded either. The anchor it would need is the same
unknown across a node seam.

**A walk-back correction that Chrome answers with the SAME offset is a silent one, and has to lengthen.**
Chrome re-announces the selection during a drag and settles back, so `awaitChange` returns a snapshot
rather than nothing. `walkBackTo` used to lengthen its reach only when nothing was announced, so it reset
its silent count and repeated the identical drag. Measured 2026-09-28 on the rig, a served page of
one-line rows: long-press `bravo` (`14..19`), END dragged by hand to 22 inside `charlie`, `⇤ word`. The
first drag landed on 20, one short of the 8 px space, and seven more went to x 340.8 and announced
`14..20` each time (`Moved(22, 20)` in 2.9 s and 8 gestures). An unchanged offset in the same node now
counts as a silent probe. After the change, Chrome force-stopped before each run, three runs of three:
`22 -> 20 -> 20 -> 19`, the third drag 4.4 px longer, in 3 gestures and 1.06-1.11 s. The debug target's
`TextView` retrace (`charlie`, three `char →`, `⇤ word`) still went `22 -> 19` in 1 gesture.

**The handle aim is in dp: `HANDLE_DROP_DP` = 14 and `HANDLE_INSET_DP` = 9. It was once raw pixels
taken off the handset, and on any other density that missed Chrome's handle entirely.**
`HandleLocator` aims at the source node's `bounds.bottom + handleDrop`, and `caret ± handleInset`,
both scaled by the live display density. They used to be the literals 57 and 30 — the gesture spike's
measurement on the handset, which is 560 dpi (3.5x). On the rig (320 dpi, 2x) that put the aim ~30 px
below the handle. Measured 2026-09-23 by scanning a live selection's own handle colour out of a
screenshot — a diagnosis, never something to aim at:

| target | node bottom | end handle | centre | real drop | old aim | outcome |
|---|---|---|---|---|---|---|
| `TextView` (debug target), 320 dpi | 545 | y 545..588 | 566 | 21 px | 602 | grabbed, every step exact |
| Chrome page content, 320 dpi | 767 | y 774..817, x 218..261 | 794 | 27 px | 824 | `HandleLost`, selection destroyed |
| Chrome page content, `wm density 480` | 1151 | y ~1155..1205 | ~1180 | 29 px | 1208 | `HandleLost` |

**Both real devices agree once the pixels are divided by the density.** The handset put the handle
centre 50..57 px below the text, which is 14..16 dp; the rig puts it 27..29 px below, which is 14 dp.
The inset is 30 px there and ~21 px here, 8.5 and ~10 dp. The one outlier is the `wm density 480`
override of a running guest, where the drop stayed at 29 px while the handle grew. That was once read
as "density does not explain it". It is one override of a guest nobody restarted, against two real
devices that agree, so it does not decide the question. At 14 dp the override case is aimed 13 px off
centre, still inside the handle.

**Why a `TextView` never showed it.** Its handle was 35 px from the old aim and every press worked,
because the platform's own `HandleView` takes touches well past its drawn circle. Chrome's composited
handle does not: a ~25 px-radius circle, and a touch outside it lands on the page and collapses the
selection. So **a debug-target pass says nothing about the aim on page content** — measure on Chrome.

**Measured after the change, 2026-09-24, on the rig:** every grab on `chrome://terms/` aimed at
y 795 against the 794 centre. `word →` from `9..14` grabbed and grew across the node's edge into the
wrapped paragraph; `char ←`, `char →` and `word →` after it each moved. Before, every one of those
presses was `HandleLost`. The debug target still steps exactly at y 573 (545 + 28).

**At the handset's density it grabs: measured 2026-09-28 on the `handset` emulator profile** (the
phone's 1272x2772 at 560 dpi, *The AVD* below), Chrome force-stopped before each run, against a
served page of one-line nodes. There the aim is 49 px below the node and 31.5 px outside the caret.
Chrome drew the END handle's circle about 37 px below the node's bottom and 39 px right of the caret,
with a radius of about 39 px, so the aim sat 12-15 px from its centre. Three long-pressed words
(`bravo`, `charlie`, `made`), each followed by `char →`, `char ←` and `word →`: nine presses of nine
grabbed, none `HandleLost`. Every char press moved exactly one character in one gesture (0.58-0.75 s),
and the word presses went `19 -> 25` (to the end of `delta`) and `14 -> 17` (to the end of `by`).
**The real phone confirms it (2026-10-05, OnePlus 15, Chrome 154 with the extended-selection flag
Disabled, `en.m.wikipedia.org/wiki/Text_editor`).** Every `char` and `word` grab was accepted: from
`interactive`, `char →` went `15 -> 16` in 1 gesture and 0.71 s, aimed 49 px below a node bottom of
1341. From `cost`, `char →` went `142 -> 143` (0.72 s) and `char ←` went `143 -> 142` (0.57 s). Across
the paragraph seam, `char ←` and `word →` each moved in 1 gesture. None of these ended `HandleLost`.

### Page, start and end

**Chrome does not auto-scroll a held selection handle. Not the pad's, and not a finger's.** The
page buttons used to drag the handle to the screen's corner and hold it there, waiting for the
target app's edge auto-scroll. Measured 2026-09-28 on the rig, a served page of 400 one-line rows:
the pad's press reported `Moved` after 1560 ms and the page did not move by a pixel. The selection
simply stopped at the last visible row. The control was an injected finger (`input motionevent`)
dragged from the handle to y 1460 and held there five seconds, still and then jiggling by 20 px.
The selection followed the finger, and the page never scrolled. The spike's old `scrollY=3` was
the whole of the auto-scroll evidence, and it does not hold up.

**Above the web contents it does.** A held handle dragged to the screen's top corner, (24, 24), which
is in the status bar and above the omnibox, makes Chrome scroll the page under it to the top. On the
handset (2026-10-12, Wikipedia) a mistaken corner drag from mid-article carried the START to the
article's title. On the rig (2026-10-13, Chrome 143) one from row ~020 reached row 001's offset 0. So
a corner drag is only ever safe at the document's real edge, which is what the next section guards.

**So a page step scrolls with a swipe and then drags the handle across the visible band.**
Scrolling does not move a selection at all; only the drag does. A handle scrolled off screen
cannot be grabbed. So the swipe puts the moving handle at the near end of the band, and the drag
takes it to the far end (`SelectionDriver.pageStep`). The band is the page's outermost scrollable
node with the docked pad cut off: `[0,313][720,1152]` for Chrome on the rig. Measured the same day,
Chrome force-stopped first:

| press | result | time, gestures |
|---|---|---|
| `page ↓` from `bravo` on row 003 | row 014, same column, scroll 346 px | 1.25 s, 2 |
| `page ↓` × 3 more | 11 rows each (a 689 px band), the column kept | 1.22–1.28 s, 2 each |
| `end` from row 002 | the last character of row 400, 36 pages | 56.9 s, 72 |
| `page ↑` × 3, START edge | 11 rows each | ~1.3 s, 2 each |
| `start` from row 310, START edge | offset 0 of row 001, 30 pages | 50.4 s, 60 |
| `char →`, then `word →`, after 4 pages | 19 → 20 → 27, the anchor far off screen | 0.7 s, 1; 1.5 s, 4 |

The swipe stands still for 150 ms before it lifts, so it cannot fling. It scrolls 10–25 px less
than it travels, which is Chrome's touch slop, and the handle is re-located from the node's re-read
bounds after it, never predicted. The swipe starts at the band's far edge, beyond the moving handle,
in the half of the band the handle is not in, a quarter of the way in. It never starts within 42 dp
of the screen's top or bottom. Where nothing scrolls (the debug target's `TextView`), there is no
swipe: `page ↓` there went 11 → 30 in 1 gesture, and `page ↑` on the START edge 6 → 0.

**A planned scroll shorter than twice the touch slop is not swiped at all**
(`PAGE_SCROLL_SLOP_MULTIPLE`). A swipe that lifts inside the slop is a tap on the page, which
clears the selection. The drag then starts where the handle already is, a little short of the band's end.
Measured 2026-10-12 on the rig (Chrome 143, a served page of one-line rows, a hand scroll first so the
handle sat near the band's bottom), `⇞ page` then `→ char`:

| planned scroll | old build | new build |
|---|---|---|
| 13 px | `the selection changed during the scroll`, `HandleLost` in 783 ms; `→ char` `the pad cannot see a selection` | no swipe, 1 gesture, ~0.6 s; `→ char` `6 -> 7`, 2 runs of 2 |
| 23 px | swiped, `scrolled 4 of 23`, 2 gestures, 1.7 s | no swipe, 1 gesture, 0.58 s; `→ char` `6 -> 7` |
| 19, 23, 13 px on the START edge | not measured | no swipe; `→ char` / `← char` `0 -> 1 -> 0` |
| 33 px, and `page ↓` ×2 (348, 768 px) | swiped as before | swiped as before (`scrolled 16 of 33`), `← char` after it exact |

**The phone confirms it (2026-10-14, OnePlus 15, Chrome 154, `en.wikipedia.org/wiki/Text_editor`, the
page scrolled ~600 px by hand).** Chrome announced selections the old way, so the extended-selection
trial was not active. On the phone the slop is about 28 px, so the threshold logged as `56.0 px`. The
band was `Rect(0, 337 - 1272, 2180)`, and `⇞ page`'s near end was y 2033, so a word on the line whose
box ends at y 2013 (handle at 2062) plans a 29 px scroll. Every run below logged `a scroll of … is under
56.0 px, inside the touch slop — no swipe`, and the press then moved in 1 gesture:

| press | result |
|---|---|
| `⇞ page` on the START from `onto` (`1..5`), 2 runs | `scroll=29.0`, no swipe, the drag passed the image and was re-homed onto the hatnote, `Moved(1, 11)` in 0.57-0.63 s; `→ char` `11 -> 12`, 1 gesture |
| `⇄ swap`, `⇞ page` on the END from `onto` | `scroll=29.0`, no swipe, crossed the anchor, now the START, `Moved(5, 15)` in 0.62 s; `→ char` `15 -> 16` |
| `⇟ page` on the START from `use` on the hatnote's first line (handle at y 473 against a near end of 442) | `scroll=31.0`, no swipe, crossed the anchor, now the END, `Moved(8, 9)` in 0.62 s; `← char` `9 -> 8` |
| control: `⇞ page` from `onto` one line lower | `scroll=148.0`, swiped from (954, 361.5), `scrolled 119 of 148` |

**On the handset, the toolbar used to end a page run after one or two pages.** Measured 2026-10-05 on the
OnePlus 15 (portrait, Chrome 154 with the extended-selection flag Disabled) against
`en.m.wikipedia.org/wiki/Text_editor`. The band is `[0,337][1272,2180]`.

| press | result | time, gestures |
|---|---|---|
| `page ↓` ×2 from `interactive` | one band each (scrolls of 899 and 1771 px), the column kept | 1.6 s, 2 each |
| the third `page ↓` | `HandleCovered`: `nowhere to start a scroll clear of the toolbar` | 22 ms, 0 |
| `end` from `interactive` | one page, then the same refusal | 1.6 s, 2 |
| `⤓ end`, then a tap on `← char` 300 ms in | `stopped by a touch during the scroll`, selection intact | 0.75 s, 1 |
| `⇄ swap`, `⤒ start` from `software` | the page's first selectable text, the Wikipedia wordmark | 3.1 s, 4 |
| `⇄ swap` on a selection spanning paragraphs, then `← char` and `⇞ page` | both refused, "can't see the end — nudge its handle once", nothing touched | — |

The refusal had one cause. After the drag, the handle sits at y ≈ 2033..2055, just above the pad.
Chrome puts its toolbar below the selection, in that strip. On the phone the toolbar is wider than
half the screen, so it covers both swipe columns (25 % and 75 %), and `swipeStart` tried only one y.
The rig's toolbar never covered both. The `handset` profile reproduces it. On a served page of 400
one-line rows, Chrome's `Copy / Share / Select all / ⋮` toolbar measured `(121, 2058)-(971, 2226)`,
and the 75 % column is x 954.

**So when the toolbar covers both columns, the swipe starts beside it, on the band's side.** That
point is just inside the toolbar's nearer edge, in the column away from the handle only, because it
is on the handle's own row. It is used only when the swipe's end is still on the screen. Measured
2026-10-05 on the `handset` profile, Chrome force-stopped each run: `page ↓` ×4 from `bravo` moved
one band each, 1.8-1.9 s and 2 gestures. Presses 3 and 4 took the fallback (`scrolled 1655 of 1662`).
`end` went 22 pages to row 400 in 42.6 s and 44 gestures, the fallback included. Before, it stopped
on page 3.

**The swipe also waits for the toolbar, because a swipe placed while it is down lands on it.**
Chrome takes the toolbar down for the drag and puts it back after, below the handle, where the swipe
starts. Inside an `end` sweep the next page's swipe was placed in that gap. On the `handset` profile
it logged `toolbar=null` and `scrolled 0 of 1638`. That reads as the document's edge, so the corner
drag ended the sweep on page 3 with the selection at the screen's corner. Two `page ↓` runs in the
same sitting failed the same way. `pageStep` now places the swipe after
`awaitSelectionOnScreen`. Every measured wait was 25-225 ms.

**`start`'s last drag lands on the first node's offset 0, and Chrome announces that as `0..0`.**
The node does not hold the anchor, so Chrome announces it as `0..focus`, and the focus is 0. The pad
read that as an empty selection. The step ended `HandleLost`, and every press after it said "the pad
cannot see a selection" while Chrome still showed one. That was the wordmark on the phone.
`inMovingFrame` now reads a START-edge `0..0` in a node without the anchor as `0..length`. A caret
that a tap leaves on a node's first character looks the same. So `perform` believes that reading
only while the toolbar is up. Measured 2026-10-05, both profiles: `start` from a fresh selection after
`⇄ swap` ended `Moved(…, 0)` on row 001, and `char →` then went `0 -> 1`, `1 -> 2` and back, 1
gesture each. A tap onto row 005's first character, then `char →`, logged `a caret on a node's first
character and no toolbar` and touched nothing. The rig's `page ↓` ×3 and `end` (26 pages to row 400)
were unchanged.

**A caret just after an image can be announced from the IMAGE's side, as a `0..0` in a node with no
text.** On the handset (2026-10-12, Wikipedia's `Text_editor`) a START `← char` from caret 1 of a figure
caption (`Editors like `) moved exactly one character, and Chrome drew the handle on the caption's
first caret. But the press logged `-> 0..0` and `HandleLost`, and every press after it said "the pad
cannot see a selection". That happened 2 times in 2. The rewrite above skips a snapshot whose node has
no text, so it never applied. The rig reproduces it with a served copy of the thumbnail markup
(`<figure>` as a table, `<a><img></a>`, then a `<figcaption>` opening `Editors like `). A START moved
onto the caption's first caret, whether by hand or by `→ char` back out of the block above, was
announced `0..0` from the image's link: an `android.view.View` with `srcLen=-1` and the image's box.
So for the START edge, `inMovingFrame` now re-homes such a `0..0` onto the first caret of the next
text leaf in tree order (`rehomedOntoText`, via `HandleLocator.textLeafAfter`), and then applies the
rewrite above to it. A page drag still sees the landing as it arrived (`Snapshot.rehomedFrom`), so
its move off a text-less node is unchanged. The held grab's log line now names the landing's node
(class, length and box, never its text). Measured 2026-10-13 on the rig, Chrome 143 force-stopped
before each run:

| press | result |
|---|---|
| START dragged by hand onto the caption's caret 0, then `→ char`, `← char` ×2, `→ char` | `0 -> 1 -> 0`, 1 gesture each, then `0 -> 21` into the lead row above and back `21 -> 0`, each re-homed, 2 gestures |
| START dragged onto caret 1, then `← char`, `→ char`, `← char`, `← char` | `1 -> 0 -> 1 -> 0`, then out into the row above, nothing blind |
| `⇞ page` on the START from the paragraph below (the drag passes the image) | the corner drag's text-less landing re-homed, `Moved(10, 0)` on the lead row; `→ char` / `← char` `0 -> 1 -> 0` |
| control: the debug target's `TextView`, `char →`, `char ←`, `word →` from `bravo` | `11 -> 12 -> 11`, then `11 -> 19` |

On the rig the `1 -> 0` press itself was announced from the caption, so only the hand drag and the
`→ char` from the row above produced the image-side `0..0` there. The re-homing is for the START only,
because the START is the only edge that has been seen announced this way.

**On the phone the re-homing fires, but there the `0..0` is the caret BEFORE the image** (2026-10-14,
OnePlus 15, Chrome 154, `en.wikipedia.org/wiki/Text_editor`). Chrome announced selections the old way,
so the extended-selection trial was not active. Long-press `features`, then `⇞ page`. The drag landed on
the image (`0..0, srcLen=-1`) and moved on to the caption's first caret, `Moved(…, 0, crossedNode=true)`
in 1.9 s and 3 gestures, with the image not selected. `→ char` then went `0 -> 1` in 1 gesture. `← char`
grabbed at x 192.5 and held the pointer at x 161.5, left of the caption's left edge (196). It logged `a caret
announced from a node with no text (in View srcLen=-1 Rect(196, 337 - 1074, 1030)) — reading it as the
first caret of the text after it` and `Moved(1, 0)` in 1 gesture and 0.7 s, and the pad stayed sighted.
But the screenshot showed the whole image selected, with the START handle at the image's bottom-left,
about (160, 1070). So every press after it grabbed at the caption's row (y 1163), where no handle was:

| press, after the re-homed `← char` | result, 2 runs of 2 |
|---|---|
| `→ char` | held grab and 12 grow tries silent, `HandleLost` in 6.3-7.3 s, the selection intact |
| `← char` | held grab and 6 snap-throughs to x 0 silent, `Moved(0, 0)`, "didn't move" |

No press said `the pad cannot see a selection`, so the blindness this entry fixes is gone on the phone.
What remains is that an image-side `0..0` can be either caret, and the pad cannot yet tell which. That
is tracked on its own.

**The phone confirms the toolbar fallback, and finds four defects the rig did not show** (2026-10-05,
OnePlus 15, Chrome 154.0.8037.92 with the extended-selection flag Disabled,
`en.m.wikipedia.org/wiki/Text_editor`). The mobile skin collapses every section after the lead, so
expand a few before measuring. Otherwise the page ends after two bands and every later press is
the document's real edge.

| press | result |
|---|---|
| `page ↓` ×3 from `interactive` | one band each, 1.58–1.59 s and 2 gestures; presses 2 and 3 logged `the toolbar Rect(56, 2136 - 1114, 2290) covers both columns; starting the scroll at (318.0, 2111.5)` |
| the fourth `page ↓`, and `end`'s fourth page | `scrolled 1382 of 1537 — the document's edge` (1540 for `end`), mid-article, then the corner drag |
| `⇄ swap`, `⤒ start` from `interactive` | the wordmark, `Moved(4, 0)` in 1.58 s and 2 gestures |
| `→ char` after that | `nothing announced — the pad cannot see a selection` |
| `⤒ start` from `Rich`, the first word of its line | the grab at x 24.5 announced nothing, `Moved(0, 0)` |

- **A collapsing top bar reads as the document's edge.** The fourth swipe hid Chrome's omnibox (the
 band's top went from 337 to 141), and the scroll came up about 158 px short, against the usual
 31–35 px. The same collapse, landing after the bounds were re-read, made an earlier first press's
 grab miss by the omnibox's 196 px.
- **The wordmark is an `android.widget.Image` with no text**, so its `0..0` comes from a node of length 0, and
 `inMovingFrame`'s rewrite (above) skips any snapshot with `sourceLength <= 0`. Row 001 on the served
 page was text, which is why the profile passed. Fixed on the rig, below. On the phone the move off the image then landed on the header's hidden `Search` button, so the pad was still blind. The walk now skips controls, which is fixed on the rig and confirmed on the phone (after the crossing table below).
- **A START handle at the left margin is not grabbed.** The touch-down falls in OxygenOS's own edge-gesture
 strip, which stock Android's exclusion does not reach. Not fixable from the pad; it now says so, below.
- **An upward page swipe starts at y 361**, where heads-up notifications appear. Straight after one
 `start` press, a WhatsApp chat was in the foreground, with no real finger on the screen.

**So `start` and `end` hold their last drag, and step off an image onto the text beyond it.**
`toDocumentEdge` grabs, holds at the screen's corner, and reads where the edge landed. Where that is a
node with no text (`srcLen <= 0`, or class `android.widget.Image`, whose text Chrome sets to the file
name when nothing else names it), the pointer moves on to the outer caret of the next text node in
tree order, without lifting: the first caret for `start`, the last for `end`. From the corner that move
is a shrink, so it lands on the caret. The tree walk under it (`adjacentTextLeaf`) also skips images
now. Before the change, on the rig against a served page opening with `<a><img alt=…></a>`, the corner
drag was announced `0..1` from the link's text-less `android.view.View` (`srcLen=-1`), and the next
`→ char` ended `HandleLost`. A bare `<img>` was announced `0..0` from the `Image`, with `srcLen=8`.
Measured 2026-10-05 after the change, on the rig, Chrome force-stopped before each run:

| page, press | result |
|---|---|
| `<a><img></a>` first, `⤒ start` on the START edge, then `→ char` ×2, `← char` | `0..0` of row 001, then `0 -> 1 -> 2 -> 1`, 1 gesture each, 3 runs of 3 |
| the same page and a bare `<img>` first, `⤒ start` on the END edge | the move off the image fired 4 of 4, onto row 001's caret 0 (the END edge then went blind; fixed, see the entry below) |
| a bare `<img>` last, `⤓ end` | the corner flapped between the image and row 120, the move landed on `0..38`, then `38 -> 37 -> 36 -> 37`, 2 runs of 2 |
| text only, `⤒ start` and `⤓ end` | unchanged: `0..0` of row 001 and `0..38` of row 120, the char presses after them exact |

The START edge on the bare-`<img>` page never reached the image in four runs: the corner stopped on
row 001's caret 0. The `Image` reading was therefore exercised only through the END-edge and `end` runs.
A `start` now costs 3 gestures and ~2.0 s where the image is met, against 2 and ~1.6 s.

**`start` on the END edge carries the END past the anchor, and from then on the pad moves the START.**
That is the desktop's `Ctrl+Shift+Home` with the focus at the end: the anchor stays, and the selection
becomes the document's start up to it. Chrome's handle drag does the same. Before the fix, Chrome
announced the landing as `0..0` in row 001's frame. Read as an END, that is an empty selection, so the
press ended `HandleLost` and every `→ char` / `← char` after it logged nothing (4 runs of 4). Now a
corner drag on the anchor's side checks whether it crossed (`SelectionDriver.crossedTheAnchor`): in the
anchor's own node by the anchor/focus order turning over, anywhere else by the landing being outside
the anchor's node. When it did, `crossTheAnchor` flips the active edge without `⇄ swap`'s side effects,
and the status line adds `now moving the start`. `end` on the START edge is the mirror. Measured
2026-10-05 on the rig, Chrome 143, a fresh service process before each run:

| page, press | result |
|---|---|
| text only, `⤒ start` on the END from `alpha` (`8..13` of row 004), then `→ char` ×2, `← char` | `Moved(13, 0)`, now the START: `0 -> 1 -> 2 -> 1`, 1 gesture each, 3 runs of 3; the screenshot showed row 001's `r` up to row 004's `alpha` |
| `<a><img></a>` first, the same presses | the move off the image, then the same, 2 runs of 2 |
| `⇄ swap`, `⤓ end` on the START, then `← char` ×2, `→ char` | `Moved(8, 38)` on row 120, now the END: `38 -> 37 -> 36 -> 37`, 2 runs of 2 |
| controls: `⤒ start` on the START, `⤓ end` on the END | unchanged, no flip |
| the debug target's `TextView`, `⤒ start` on the END from `bravo` | the handle stops on 7, one short of the anchor, so no flip; `7 -> 8 -> 9 -> 8` |

**The phone confirms the crossing, but the move off the image still leaves the pad blind there, and a
`start` from the page's top reloads the page** (2026-10-10, OnePlus 15, Chrome 154 with the
extended-selection flag Disabled, `en.wikipedia.org/wiki/Text_editor`). With the page scrolled about
440 px, long-press `interactive` (`4..15`), then `⤒ start` on the END. The scroll fell short (`scrolled -441
of -1084.0 — the document's edge`), and the press logged `the edge crossed the anchor — now moving the
START`. The status line read `moved into another block; now moving the start`, and the screenshot showed
the selection from the wordmark up to the start of `interactive`. But the wordmark is where it started:
the walk beyond it found the header's `Search` button (an `android.widget.Button` whose 6-character text is
the search icon's label, never drawn) and said it `is wrapped and will not measure`, so nothing moved it
on. With `⇄ swap` first, the same corner drag landed on the wordmark, announced as `0..0, srcLen=-1`, which
is how the phone's wordmark reads. The move off the image fired, and it aimed at that `Search` button.
The selection then started on the search icon. In both runs `→ char` and `← char` logged `nothing
announced — the pad cannot see a selection`. So the walk has to skip nodes that are not page text.

With the page already at its top, the same `⤒ start` swiped downward from (954, 361.5), and that is
Chrome's pull-to-refresh: the page reloaded and the selection was gone. The pad read the reload as a
1130 px scroll and reported `Moved(4, 15)`. A hand `input swipe` from the same point showed the refresh
spinner.

**So a page step first asks the page whether it can scroll that way, and where it cannot, it does not
swipe.** `contentBand`'s scroller lists its scroll actions, and Chrome keeps them current: at the page's
top it lists `ACTION_SCROLL_DOWN` and `ACTION_SCROLL_FORWARD` only, mid-page all four, at the bottom
`ACTION_SCROLL_UP` and `ACTION_SCROLL_BACKWARD` only. `pageStep` reads the directional pair where the
node lists one, else forward/backward, and when the step's way is missing it goes straight to the
document-edge corner drag (`the page cannot scroll up — already at the document's edge, no swipe`). A
scroller that lists none keeps the old swipe. Measured 2026-10-12 on the rig (Chrome 143, force-stopped
before each run, a served page of an `<h1>` and 120 one-line rows, `alpha` on row 005 long-pressed with
the page at its top):

| press | old build | new build |
|---|---|---|
| `⤒ start` on the END | swiped down from (540, 285), `scrolled 0 of -385`, `Moved(13, 13)`; the page came back scrolled to row 012 with no selection | no swipe, `now moving the START`, onto the heading's offset 0 (`0..11`), 1 gesture, ~0.6 s, 2 runs of 2 |
| `⤒ start` on the START, `⇞ page` on the START | not measured | no swipe, onto the heading's offset 0, 1 gesture, ~0.6 s, 3 runs of 3; `→ char` / `← char` after it `0 -> 1 -> 0` |
| `⇟ page` at the page's bottom, START on row 108 | not measured | no swipe, crossed the anchor onto row 120's end, then `← char` `33 -> 32` |
| controls: `⇞ page` scrolled 700 px; `⤓ end` from the top | as before | both swiped as before; `end` ran 7 pages and found the edge by the short scroll |

On the old build the first run's swipe landed on Chrome's "Translate page?" bar, which hid the defect.
Serve such a page with `<html lang=en>`.

**The phone confirms it (2026-10-13, OnePlus 15, Chrome 154.0.8037.127 with the extended-selection flag
Disabled, `en.wikipedia.org/wiki/Text_editor` at its very top with the wordmark on screen).** There the
scroller listed `[16908346, 4096]`, i.e. `up=false down=true`. Every press below logged `the page cannot
scroll up — already at the document's edge, no swipe`. No refresh spinner appeared, the page did not
reload, and the selection survived. Long-press `interactive` (`4..15`):

| press | result |
|---|---|
| `⇄ swap`, `⤒ start` | the corner drag landed on the wordmark (`0..0, srcLen=-1`) and moved on to the heading, `Moved(4, 0)` in 0.82 s and 2 gestures; `→ char` `0 -> 1`, 1 gesture |
| `⇄ swap`, `⇞ page` | the same, 0.85 s and 2 gestures; `→ char` `0 -> 1` |
| `⤒ start` on the END | `the edge crossed the anchor — now moving the START`, `Moved(15, 0)` in 0.87 s; `→ char` `0 -> 1`, `← char` `1 -> 0`, 1 gesture each |
| `⇞ page` on the END | the same crossing, `Moved(15, 0)` in 0.86 s |
| control: page scrolled ~550 px by hand, `⇞ page` from `simple` | `up=true down=true` (`[16908344, 16908346, 4096, 8192]`), swiped from (954, 361.5), `scrolled 32 of 61`, one band; `→ char` after it `10 -> 11` |

**So the walk skips controls: a button's text is its label, not page text.** `adjacentTextLeaf` now
passes over any node of a class in `HandleLocator.NOT_PAGE_TEXT_CLASSES`, along with its children: the
image, and Button, ImageButton, ToggleButton, CheckBox, RadioButton, Switch, EditText, Spinner, SeekBar and
ProgressBar. The landing test `holdsNoText` reads the same set, so a corner drag that lands on such a
control moves on too. The rig's served page reproduces the phone's header: `<a><img src=/wordmark.svg></a>`,
then `<button>` holding an icon `<svg>` and a clipped `<span>Search</span>`, then `<h1>Text editor</h1>`,
then 120 one-line rows. Chrome reports that button as `android.widget.Button` with the text `Search`, as on
the phone. An `aria-label` alone comes through as a content description, with no text, so it never
reproduced the defect. Measured 2026-10-12 on the rig (Chrome 143, force-stopped before each run, page
scrolled ~400 px, long-press `alpha` on row 005):

| press | old build | new build |
|---|---|---|
| `⇄ swap`, `⤒ start`, then `→ char`, `← char` | `adjacent: the next node (6 chars …) is wrapped and will not measure`, `HandleLost`; the next press `the pad cannot see a selection` | onto `0..11` of `Text editor`, 3 gestures, ~1.9 s; `0 -> 1 -> 0` (or `-> 2 -> 1`), 1 gesture each, 3 runs of 3 |
| `⤒ start` on the END (the anchor crossed), then `→ char`, `← char` | not measured | `now moving the START`, onto `0..11`; `0 -> 1 -> 0`, 1 gesture each, 2 runs of 2 |
| control: `→ char`, `← char` across rows 004/005 from `delta` | as before | `33 -> 1 -> 33`, the block step, 2 gestures each |

**The phone confirms it (2026-10-12, OnePlus 15, Chrome 154.0.8037.127, `en.wikipedia.org/wiki/Text_editor`,
the page scrolled about 300 px with the omnibox showing).** Long-press `interactive` (`4..15`), then `⤒ start`.
Every run logged `the drag left the edge in a node with no text (0..0, srcLen=-1)` and `adjacent: the next
node (11 chars at Rect(56, 596 - 1218, 732))`, i.e. the `Text editor` heading, not the `Search` button. The
press ended `Moved(…, 0, crossedNode=true)` in 1.9-2.0 s and 3 gestures, and the screenshot showed the
selection starting on the heading's `T`. `→ char` then went `0 -> 1` in 1 gesture and 0.57 s, and `← char`
`1 -> 0` in 1 gesture and 0.70 s, with no `cannot see a selection`.

| press | runs | result |
|---|---|---|
| `⇄ swap`, `⤒ start` on the START, `→ char`, `← char` | 3 of 3 | onto `0..11` of the heading; `0 -> 1 -> 0` |
| `⤒ start` on the END (the anchor crossed), `→ char`, `← char` | 3 of 3 | `now moving the START`, `Moved(15, 0)`; `0 -> 1 -> 0` |

The START handle on the heading's first caret is drawn at the left margin, and every grab there (x 24.5 and
76.5, y 781) logged `is in a side gesture zone` and was answered. So at that height OxygenOS's edge strip
did not swallow the grab, unlike the margin grabs lower down recorded under *A handle at the screen's side
margin*. Chrome's extended-selection flag was at Default for this whole pass: the change to Disabled was lost
to a force-stop straight after it. With the flag at Default this Chrome announced the selection the old way
(`seeded from a fresh selection`), so the server trial that blinded the pad (*The handset IS that 36.0
device*) was not active on the phone that day.

**With the omnibox hidden, the same `⤒ start` opened the Quick Settings shade.** The band's top was the
status bar's bottom (141), and the downward swipe started at y 165.5. OxygenOS pulled its shade down over
the screen, the page scrolled 0, and the grab then refused (`another app's window Rect(0, 0 - 1272, 2772)`),
`Obstructed` with the selection intact.

**So the top guard on a swipe's start is now measured from the status bar, not from the screen's top.**
Before this, an upward page's swipe started no higher than 42 dp from the screen's top: 147 px on the phone,
which is 6 px below a 141 px status bar. Now it starts no higher than 42 dp below the deepest of the status bar
(`statusBars`, visible or not), the top `systemGestures` zone and the `mandatorySystemGestures` zone
(`GestureDispatcher.systemTopInset`). The rig cannot show the shade itself. On stock Android only a swipe that
starts INSIDE the status bar opened it: probes from y 60 and 140 did, and ones from 170, 178 and 190 did not.
The rig's Chrome also never hides its omnibox. So the rig reproduces the band's shape with a served page that
calls `requestFullscreen()` on a tap. That hides the status bar, the band's top reads 159, and the
mandatory gesture zone is `[0,0][720,183]`. Click the `Viewing full screen` hint's `Got it` once, or it is
the foreign window the swipe moves around. Measured 2026-10-13 on the rig, Chrome 143 force-stopped before
each run, 200 one-line rows, three hand scrolls, long-press `alpha`, `⇞ page`, `← char`, `→ char`:

| build | swipe from | result |
|---|---|---|
| old | (540, 173), inside the 183 px gesture zone | scrolled -314 of -331; stock let it through |
| new, 3 runs | (540, 267), 183 + 84 | scrolled about -356 of -377, then `← char` and `→ char` one character each |
| new, omnibox shown (band top 271), 2 runs, then `⇟ page` ×2 | (540, 285), unchanged | as before; `⤓ end` ran to row 200's end |

The phone has not run it yet. Whether 42 dp below the status bar is far enough for OxygenOS's pull-down is
the thing that confirmation measures.

**An ordinary page step can land on the image too, so every page drag is held, not only the corner's.**
When the page reaches its top inside a step whose scroll is not short, there is no corner drag, and the
step's released drag to the band's top landed on the wordmark. It ended `HandleLost`, and the pad could
not see the selection after it: a released drag cannot be taken back off an image, because nothing
locates a handle there. `holdOntoText` now does every page step's drag, to the band's far end or to the
corner, and moves off a text-less landing the same way. Measured 2026-10-12 on the rig (Chrome 143,
force-stopped before each run, the header page above scrolled ~150 px, long-press `alpha` on row 008):

| press | old build | new build |
|---|---|---|
| `⇄ swap`, `⤒ start` (`scrolled -112 of -131`, not short), then `→ char` | `drag to (152.0, 343.0) -> HandleLost`, then `the pad cannot see a selection` | the move off the image (`0..0, srcLen=-1`) onto `Text editor`, then the corner page; `0 -> 1 -> 0`, 1 gesture each, 2 runs of 2 |
| `⇞ page` on the END (the same scroll) | not measured | off the image (`0..1`), crossed the anchor onto `0..11`, now the START; `0 -> 1 -> 0` |
| controls: `⇟ page` ×3, `⇞ page`, `⤓ end` mid-document | as before | one band each, the column kept (`14 -> 14` a row-shape on); `end` 7 pages to row 120's 33, then `33 -> 32 -> 31 -> 32` |
| control: the debug target's `TextView`, `⇟ page`, `← char`, `→ char` | as before | `11 -> 30`, then `30 -> 29 -> 30` |

**The phone confirms it (2026-10-14, OnePlus 15, Chrome 154.0.8037.127, Wikipedia's desktop skin).**
Chrome announced selections the old way (`seeded from a fresh selection`), so the extended-selection
trial was not active. The case is narrow to set up. The step's scroll must reach the page's top, and
it must fall short by no more than a line (`judgeAndDrag`). Otherwise the scroller stops listing `up`,
and the short scroll sends the step to the corner drag. So the handle, measured with the page at its
top, has to sit within a line above the band's near end (y 2033 here). `Text_editor` has its image
there and nothing selectable. On it, `⤒ start` from `interactive` with the page scrolled ~330 px
logged `scrolled -325 of -968.0 — the document's edge` and took the corner drag, which was already
confirmed. `Plain_text` does have text there. Scroll it ~375 px by hand and long-press `encoding` in the
lead (`41..49` of a wrapped 77-character node). The step then logged `scrolled -374 of -461.0`, and the
ordinary drag to (255.5, 484) logged `the drag left the edge in a node with no text (0..0, srcLen=-1)`.
It moved on to the `Plain text` heading's first caret, `Moved(41, 0, crossedNode=true)`:

| press | result |
|---|---|
| `⤒ start` on the START, 2 runs | the ordinary drag moved off the wordmark onto `0..10`, then the corner page (`cannot scroll up`) also moved off it; 2.8 s, 5 gestures; `→ char` `0 -> 1`, `← char` `1 -> 0`, 1 gesture each |
| `⇞ page` on the START, 2 runs | the ordinary drag only, off the wordmark onto `0..10`, 1.9-2.0 s, 3 gestures; `0 -> 1 -> 0`, 1 gesture each |
| control: `⇟ page` ×3 on `Text_editor` from `often` | one band each (`scrolled 1466 of 1494`, `1480 of 1508`, `1613 of 1648`), each drag straight down at its grab's column; `→ char` / `← char` `6 -> 7 -> 6` |

On `Plain_text` the third `⇟ page` landed the END on a collapsed section heading's first caret. That
was announced `0..0` and ended `HandleLost`, and the pad was blind after it. That is tracked on its own.

**A page step mid-document can cross the anchor too, and there the landing's node says nothing by
itself**, because it can be on either side of the anchor, and Chrome announces it as `0..focus` either
way. That `end` run's FIRST page step already carried the START past the anchor, and the edge kept its
name until the corner. A single `⇟ page` did worse. Measured 2026-10-05 on the rig (Chrome 143, a served
page of 120 one-line rows), `⇄ swap` on `alpha` (`8..13` of row 004), then `⇟ page`: the START landed on
row 015 and was still called the START. The next `→ char` grabbed on the START's side of the caret,
touched nothing, and escalated into a word snap: `8 -> 13` in 3 gestures. The `⇥ word` after it said "no
known word boundary yet", and `⇤ word` aimed its grab on the wrong side too.

So every page step toward the anchor checks, and mid-document `crossedTheAnchor` re-reads the anchor's
node (`refresh()`) and compares the two nodes' screen bounds. The landing is past the anchor when its node
lies wholly beyond the anchor's in the direction of travel: by row, or by column on a shared row. Where
the anchor's node cannot be re-read, the edge keeps its name. That happens when the anchor has scrolled
off screen: Chrome's `refresh()` then fails. On these pages that is never a crossing, because a page drag
only reaches an anchor that is within the band. Measured after the change, Chrome force-stopped before
each run:

| page, press | result |
|---|---|
| `⇄ swap`, `⇟ page` from `alpha` on row 004 | `landed in Rect(32, 1033 - 580, 1071), the anchor's node is at Rect(32, 333 - 580, 371) — past the anchor`, now the END: `→ char` `8 -> 9`, `← char` `9 -> 8` (1 gesture), `⇥ word` `8 -> 13` |
| scrolled 600 px, `⇞ page` on the END from `alpha`, 3 runs | past, now the START: `← char` `13 -> 12`, `→ char` `12 -> 13` (1 gesture), `⇤ word` `13 -> 8` |
| `⇟ page` ×2 on the END, then `⇞ page` with the anchor off screen, 2 runs | `the anchor's node cannot be re-read — the edge keeps its name`, then `← char` `13 -> 12`, `→ char` `12 -> 13` as the END |
| the next `⇞ page` from there, 2 runs | past (row 001 against row 004's 463), now the START; `⇞ page` to the document's start, `→ char` `0 -> 1`, `← char` `1 -> 0` |
| `⇄ swap`, `⤓ end`, 2 runs | flipped to the END on the FIRST page, then 6 more grabbed on the END's side (x 172, not 136); `← char` ×2, `→ char` `38 -> 37 -> 36 -> 37` |

The `→ char` from a word's start costs 3 gestures, as it does anywhere on Chrome (the held snap-through
above). In the scrolled run, a `⇥ word` with the START at y 299 ended `HandleCovered` with nothing touched,
three runs of three: Chrome's toolbar sat over that handle near the band's top. That is the toolbar refusal
(*A grab under the floating toolbar*) firing on the rig for the first time.

**The phone confirms all three cases (2026-10-12, OnePlus 15, Chrome 154 with the extended-selection
flag Disabled, `en.wikipedia.org/wiki/Text_editor`).**

| case | result |
|---|---|
| `⇄ swap` on `interactive` (`4..15`), `⇟ page`, 3 runs | `landed in Rect(56, 1754 - 1151, 2010), the anchor's node is at Rect(378, 375 - 721, 445) — past the anchor`, now the END, `Moved(4, 87)` in 1.7 s and 2 gestures; `→ char` `87 -> 88`, `← char` `88 -> 87`, 1 gesture each |
| `⇞ page` on the END from `features` (`27..39`), the page scrolled, 2 runs | the scroll was under the slop, so no swipe; `past the anchor`, now the START, onto offset 0 of the caption above (`Editors like `), `Moved(39, 0)` in 0.55 s and 1 gesture; `→ char` `0 -> 1` and `3 -> 2` were exact, one `→ char` went `1 -> 3` |
| `⇟ page` ×2 on the END from `software`, then `⇞ page` | `the anchor's node cannot be re-read — the edge keeps its name`, `Moved(89, 18)`; `→ char` `18 -> 19`, `← char` `19 -> 18` as the END |

The pass also found three defects, none of them in the crossing:

- **An END in a one-character node refuses.** A long-press on `environment` took the trailing `.` into the
  selection, and that `.` is a node of its own. The END then sat in a node whose honest per-character rectangle
  is the node's whole box. The toolbar bound assumes the START is in that node too, so every press ended
  `ColumnUnknown`.
- **A START `← char` onto the caption's caret 0 was announced `0..0`.** That ended `HandleLost`, and the pad
  stayed blind afterwards, 2 runs of 2.
- **A `⇞ page` with the omnibox already hidden scrolled 56 px of a planned 230.** It read that as the
  document's edge and made the corner drag to (24, 24). Above the web contents, Chrome does auto-scroll a held
  handle, so the START ran to the article's title.

**A handle at the screen's side margin sits inside the system's edge-gesture zone, and only stock
Android lets the grab through.** A START on a line's first character is drawn beyond the caret: on
the phone, with Wikipedia's 16 dp margin, at x 0..56. Gesture navigation claims a 105 px (30 dp)
zone on each side, on the phone and on the `handset` profile alike (`dumpsys window`, `type=systemGestures`).
On stock Android that does not matter, because Chrome excludes its handles from the back gesture:
with the START at the margin, `mSystemGestureExclusion` read `(0,1788,94,1865)`, the handle itself.
Measured 2026-10-05 on the `handset` profile against a served page with a 16 px body margin: from `Source`
(`0..6`, after `⇄ swap`), every grab at x 24.5 or 55.5 landed. `char →` went `0 -> 1`, `char ←` `1 -> 0`,
1 gesture each, and `⤒ start` crossed into the paragraph above, 3 runs of 3. OxygenOS does not
honour the exclusion, it seems: the same grab on the phone announced nothing. It runs an edge gesture
of its own. `dumpsys input` lists an `edge-swipe` spy monitor and `gesture_displayid_0` regions
`[0,141]-[84,2772]` on the left, and `[1188,141]-[1272,278]` plus `[1188,548]-[1272,2772]` on the right.
`gesture_mistouch_prevention_side_enable=1` is set too. A handle drawn at x 0..56 has no pixel
outside 84, and Chrome's touch target ends at the drawn circle, so no aim inside the handle can avoid
the strip. A press there cannot be made to work. The pad now does three things:

- **Every handle touch-down in a side zone is logged** (`the touch-down at (24.5, 2061.0) is in a
  side gesture zone (left 105 px, right 105 px)`). The zone is read from the service's
  `WindowMetrics` (`WindowInsets.Type.systemGestures()`), so it is empty on three-button navigation.
  The OxygenOS strip is narrower than the AOSP zone, so the check flags a little more than OxygenOS takes.
- **The grab is still made**, because stock Android takes it. Refusing every margin grab would break
  the case measured working above.
- **Only one unanswered touch-down per press.** A swallowed grab announces nothing, so the press used
  to escalate twelve drags from the same pixel, reaching inward up to 630 px. From the left edge that
  is the back gesture's own shape. Now, once a zone touch-down has been followed by no announcement,
  every later touch-down in a zone is dropped (`went unanswered — not touching`). The press then ends
  with `the handle is in the screen edge's swipe zone` on the status line, instead of `lost the handle`
  or `didn't move`. A dropped drag now reports itself not completed, and every grab asks first (below),
  so the press stops at the first unanswered touch-down instead of waiting out its tries.

**`adb shell run-as dev.mnaoumov.asc touch files/force-swallow-edge-zone`, then `rig.ps1 rebind`,
drops every handle touch-down in a side zone** on a debuggable build, which is how the rig reproduces
the phone. `rm` it and rebind to undo. With it, on the same page, `char →` from the margin logged
`debug: swallowed it`, then (before the stop-at-once change below) 13 dropped touch-downs, ended `HandleLost` in 3.1 s, and showed the
edge message with the selection intact. A mid-line START (`code`, `7..11`) logged no zone and stepped
`7 -> 8 -> 7`.

**The phone confirms it, at both margins (2026-10-10, OnePlus 15, Chrome 154 with the extended-selection
flag Disabled, `en.wikipedia.org/wiki/Text_editor`).** `mSystemGestureExclusion` read `SkRegion()` with the
START at the margin, so Chrome's exclusion is not there at all, or not where `dumpsys` shows it.

- **`→ char` on a START at the left margin** (`allows`, `6..12`, after `⇄ swap`). The press logged `the
  touch-down at (24.5, 1485.0) is in a side gesture zone`, the held grab announced nothing, and the next
  twelve tries logged `went unanswered — not touching`. It ended `HandleLost` in 3.5 s with the edge
  message, the selection intact and Chrome still in front, 3 runs of 3. The first grab never landed.
- **`⤒ start` on a START at the left margin** (`simple`, `25..31`, page scrolled). The scroll ran first
  (294 px), then the corner drag's one touch-down at (24.5, 1999.0) was flagged and announced nothing.
  The press ended `Moved(25, 25)` in 2.2 s with the edge message and the selection intact. The scroll
  still happens before the handle is found unreachable. From the page's top, `⤒ start` used to set off
  pull-to-refresh instead, which reloaded the page; it no longer swipes there (*Page, start and end*).
- **`→ char` on an END at the right margin** (`is`, `99..101`, its caret at x 1190). The handle is drawn
  at about x 1195..1268, all of it inside OxygenOS's right strip. The grab at (1221.5, 1810.0) was flagged
  and announced nothing, and the press ended with the edge message, selection intact, 3 runs of 3. It took
  2.0 s and 7 gestures. Those were one touch-down, then six held snap-through pushes on a pointer Chrome
  never saw. An END whose aim stayed just short of the zone (x 1161.5, `since`) stepped `80 -> 81` across
  the wrap in 1 gesture.

**So a press that a zone has swallowed stops at once, and once the ROM is known to swallow, a press
whose handle is in a zone touches nothing at all.** Every grab, and every page step's swipe, passes
through `SelectionDriver.uncovered` first, which asks `edgeZoneSwallows`. That is true for an aim in a
side zone when this press's zone touch-down went unanswered, or when the service has learned that this
ROM swallows them (`edgeZoneSwallowsHere`). The press then ends `InEdgeZone` with the edge message and
nothing touched. A held pointer whose zone grab went unanswered refuses `moveHeld` and lifts, so no
snap-through push follows. The verdict is learned from the first zone touch-down of a service
connection: `true` when it went unanswered and the press got nowhere, `false` when it was answered. It
is not kept across a rebind, because "unanswered" can also be a grab that simply missed, and a wrong
`true` would refuse every margin grab after it. For the same reason a press whose aim was a one-line box's
average (`HandleLocator.aimedByAverage`) never teaches `true`: on 2026-10-13 a cold Chromium `<p>`'s END
placed by its block box at x 706, 186 px right of the handle, went unanswered on stock Android, and every
margin grab after it was refused. Under the refusal marker that miss now logs `not learning from this
press`, and the next press grabs again. A miss whose aim was measured still teaches it, and the swallow
marker's left-margin `→ char` still ended `InEdgeZone` and then refused the next one with 0 gestures. So the first margin press of a connection on a
swallowing ROM still pays one touch, and a first page step still pays its scroll. Every later one pays
neither. Measured 2026-10-12 on the rig with the marker below (Chrome 143, a served page of one-line
rows with a 16 px body margin, 60 px zones), a rebind before each run, 3 runs of 3 each:

| press | before | after |
|---|---|---|
| `→ char`, START at the left margin (`code` → `⇄ swap` → `⇤ word` to `0..11`) | `HandleLost` in 3.1 s, 13 "gestures" | `InEdgeZone` in 23-39 ms (the one dropped grab); the next press 8-19 ms, 0 gestures |
| `← char` then `→ char`, END at the right margin (`is`, `24..26`) | not measured | `InEdgeZone` in 28-33 ms, then 10-13 ms with 0 gestures |
| `⇞ page` ×2 then `⤒ start`, START at the left margin, page scrolled | not measured | the first scrolled 346-358 px and ended `Moved(0, 0)` with the edge message in ~2.0 s; the second `⇞ page` and the `⤒ start` ended `InEdgeZone` in 11-17 ms, 0 gestures, no scroll |
| controls, no marker: the same presses | as before | `0 -> 1 -> 0 -> 1` at the left margin and `26 -> 25 -> 26` at the right, 1 gesture each; `⇞ page` crossed into the row above, then `0 -> 1 -> 0`; `a touch-down there was answered` |

The marker drops the grab without dispatching it, so the rig cannot show the phone's own shape: a grab
that IS dispatched and that Chrome never sees. There the held pointer's next push is refused
(`the grab in a side gesture zone went unanswered — lifting rather than pushing on`).

**The phone confirms the stop, the refusal and the controls (2026-10-14, OnePlus 15, Chrome 154,
`en.wikipedia.org/wiki/Text_editor`, the service re-enabled before each case so the verdict was fresh).**
Chrome announced selections the old way (`seeded from a fresh selection`), so the extended-selection trial
was not active.

| press | result |
|---|---|
| `→ char`, START at the left margin (`allows`, `6..12`, after `⇄ swap`) | grab at (24.5, 1485) flagged, announced nothing, then `refusing to touch`: `InEdgeZone` in 523 ms, 1 gesture (was 3.5 s and 13 "gestures"); the next `→ char` `InEdgeZone` in 17 ms, 0 gestures |
| the page scrolled 291 px by hand, then `⤒ start` and `⇞ page` on that START | `InEdgeZone` in 14 ms and 8 ms, 0 gestures, the page did not move, the selection intact |
| `← char`, END at the right margin (`for`, `49..52`, caret 1218) | grab at (1249.5, 2111), an inward horizontal move, announced nothing: `InEdgeZone` in 510 ms, 1 gesture; the next one 17 ms, 0 gestures |
| controls, after the ROM was learned to swallow: `features` (`18..26`) mid-line, END `→ char`, `← char`, then START `→ char`, `← char` | `26 -> 27 -> 26`, `18 -> 19 -> 18`, 1 gesture each, 0.56-0.74 s |

**OxygenOS's strip does not swallow every touch-down in it: one whose first move is vertical gets
through.** With a fresh verdict, `→ char` from the same `for` crosses the wrap, and its grab at the same
(1249.5, 2111) went straight down first. The press logged `a touch-down there was answered` and
`Moved(52, 53)` in 1.1 s and 2 gestures, and the service learned `false`. The `← char` above, from the
same pixel but moving inward along the row, was swallowed. So the strip judges the stroke, not only the
touch-down, and a verdict learned from a vertical grab says nothing about a horizontal one. That is
tracked on its own, along with whether a grab can be made to start vertically on purpose.

**The `lifting rather than pushing on` branch fired once on the phone, and on an ambiguous press.** After
that crossing the END sat on 53, the first caret of `editing` at the left margin, under the pad. The next
`→ char` grabbed at (87.5, 2206) after the 110 ms pass-through wait, announced nothing, and logged
`held: the grab in a side gesture zone went unanswered — lifting rather than pushing on`. It ended
`InEdgeZone` in 660 ms and 2 gestures instead of six pushes. But a grow from a word's start is silent on
Chrome anyway (the left-margin silent-grow defect), so whether OxygenOS swallowed that grab is not known.
The right-margin `→ char` that the branch was written for, a same-row step from an END in the strip, was
not found on this page.

**A grow from the document's first or last caret has nowhere to go. Unless it touches nothing, its
silence teaches the verdict wrong.** Measured 2026-10-13 on the rig (Chrome 143, no marker, so the zone
takes the grab): with the START on `0..22` of a served page's first row, `← char` grabbed at x 14,
travelled to x -4 and announced nothing, because nothing comes before the document's start. The press
ended `InEdgeZone`, the service learned that this ROM swallows, and the next `→ char` from the same
caret was refused in 25 ms. So `SelectionDriver.nothingBeyond` now runs before any char or word grow.
It refuses when the moving edge is on its node's outer caret in the direction of travel and nothing lies
beyond it. On a `TextView` that is always the case, because the selection cannot leave the view. On Blink
it is the case when no text leaf is on screen that way (`textLeafBefore/After`) and the page cannot
scroll that way (the band does not scroll, or its scroller no longer lists that direction). The press
ends `AtDocumentEdge` ("already at the start of the text") with 0 gestures. Where the page could still
scroll, the text beyond is only off screen, and the press goes ahead. Measured after the change, Chrome
force-stopped before each run:

| press | result |
|---|---|
| the repro: scrolled 150 px, `⇞ page` onto row 001's `0`, then `← char`, `⇤ word`, `→ char`, `← char`, 3 runs | `AtDocumentEdge` in 9-23 ms, 0 gestures; then `0 -> 1 -> 0`, 1 gesture each, and the zone grab logged `answered` |
| `⤓ end` onto row 120's `20`, then `→ char`, `⇥ word`, `← char`, `→ char` | `AtDocumentEdge` twice (`down=false`), then `20 -> 19 -> 20` |
| the debug target's `TextView`: `echo` (`26..30`) `→ char`, `⇥ word`; `alpha` swapped, `← char`, `→ char`, `← char` | `AtDocumentEdge`, 0 gestures; then `0 -> 1 -> 0`, and `30 -> 29` from `echo` |
| control: the START on row 002's `0`, row 001 above it, `← char` | no refusal; the block step ran as before |

**A heads-up notification is another app's window above the target, and a touch-down on it goes to the
notification.** An upward page swipe starts a quarter of a drop below the band's top: y 361 on the phone,
327 on the rig. That is where heads-ups appear. On the rig an SMS (`adb emu sms send 5551234 <text>`)
puts up a Messages heads-up that the window list reports as `(32,0)-(688,399)`. Measured 2026-10-05 on
the rig with the old build, Chrome, `⇞ page` with a heads-up up: the swipe from (180, 327) pulled the
heads-up open over the page. It scrolled 0, which read as the document's edge, and the corner drag then
selected everything up to row 001. So `foreignWindowAt` walks the window list from the top and returns
the first window under a point whose package is not the target's. The pad and the mask are skipped.
Three things use it:

- **The swipe starts beside it**, just beyond its band-side edge, the same way it starts beside a
  toolbar that covers both columns (`swipeStart`). The swipe's end must still be on the screen.
- **A grab under it refuses with nothing touched** (`uncovered`). The press ends `Obstructed`, and the
  status line says `a notification is in the way`.
- **Every dispatched touch-down checks it again** (`noteForeignWindow`), because a heads-up can arrive
  mid-press. That touch-down is dropped.

Measured with the fix, Chrome force-stopped before each run, an SMS sent 1.5 s before the press:

| press | result |
|---|---|
| `⇞ page` from `charlie` on row ~050, 3 runs | `another app's window Rect(32, 0 - 688, 399) covers (180.0, 329.0); starting the scroll at (180.0, 413.0)`, scrolled 226-250 px, Chrome still in front |
| `→ char` with the END handle at y 379-383, under the heads-up, 2 runs | `Obstructed` in 21-31 ms, 0 gestures |
| the same `→ char` and `← char` once the heads-up had gone | `19 -> 20 -> 19`, 1 gesture each |
| no heads-up: `⇞ page` ×2, `→ char` near the top, the debug target's `char →`, `char ←`, `word →` | unchanged: the swipe from y 327, every step exact |

**The phone confirms both halves (2026-10-11, OnePlus 15, Chrome 154 with the extended-selection flag
Disabled, `en.wikipedia.org/wiki/Text_editor`, four sections expanded and the page scrolled).** The heads-up
was a Clock timer expiring (`am start -a android.intent.action.SET_TIMER --ei
android.intent.extra.alarm.LENGTH <s> --ez android.intent.extra.alarm.SKIP_UI true`). Its `Time's up`
banner stays up while it rings. The window list reported it as `Rect(56, 141 - 1215, 476)`; the item had
guessed y 140-520.

| press | result |
|---|---|
| `⇞ page` from `represented` (`79..90`), banner up | `another app's window Rect(56, 141 - 1215, 476) covers (318.0, 361.5); starting the scroll at (318.0, 500.5)`, scrolled -122 of -153, Chrome still in front, banner not opened; `Moved(90, 0)` in 1.6 s and 2 gestures |
| `→ char` from `software` (`1..9`, the START), handle aimed at (857.5, 456), banner up | `grab: another app's window ... covers (857.5, 456.0) — refusing to touch`, `Obstructed` in 10 ms, 0 gestures, status `a notification is in the way`, selection intact |
| the same `→ char` once the banner had gone | `1 -> 2` in 1 gesture and 0.58 s |

Three things about driving it. `cmd notification post` does not give a heads-up, because the shell's channel has
importance 3 and no banner option. The `SET_TIMER` intent starts an activity, which takes Chrome's focus
and clears the selection, so start the timer first, bring Chrome back, and select after that. The banner also
hides itself after about a minute, so check that it is on screen before a press that is meant to meet it.

**So a page step forgives a shortfall that the band's own move explains, and settles the band as well
as the node.** `pageStep` re-reads the content band after the scroll, and the document's edge now needs
a shortfall of more than a line PLUS however far the band's top and bottom moved. For the case above
that is 155 px against 98 + 196, so not the edge. A real edge hidden behind a collapse shows up on the
next page, which then scrolls nothing. `awaitBoundsSettled` compares the band along with the node's
bounds, so a settle cannot end while the controls are still sliding. And the grab re-reads the bounds
after the toolbar and the handle fade; when they moved since the settle, it settles again before it
judges or grabs (`the page moved after the scroll settled`). The rig's Chrome never hides its top
controls on these swipes (the band's top stayed at 313 on every page), so what was measured there
(2026-10-05) is only that nothing else changed: `page ↓` ×4 from `bravo` moved one band each in
1.7 s and 2 gestures, and `end` ran 18 pages to row 400 and found the edge (`scrolled 344 of 710`).

**The phone no longer ends mid-article, but the collapse did not come back to test the fix**
(2026-10-10, OnePlus 15, Chrome 154.0.8037.127 with the extended-selection flag Disabled,
`en.wikipedia.org/wiki/Text_editor` on the mobile skin, four sections expanded). Long-press
`interactive`, then `page ↓`: one band each, `scrolled 899 / 1561-1564 / 1474 / 1414-1418 of` the
planned scroll, 1.56-1.61 s and 2 gestures, and the fifth page found the article's real end
(`scrolled 504-508 of 1585`, the footer on screen), in two runs. `end` from the same word went the same
four pages and the edge, `Moved(15, 12)` in 8.1 s and 10 gestures. The band's top stayed at 337 on
every page of all three runs, so Chrome never hid its omnibox under the pad's swipes in this sitting.
A hand `input swipe` did hide it. The `band moved` and `settling again` branches therefore never fired on
the phone, and they are still proven only by the reasoning above.

The system also rebound the service once in the sitting, with no crash (the pad window was destroyed
and re-added 1.3 s later), and that dropped the pad's view of the selection.

**A short scroll still read as the edge on the phone, with the omnibox already hidden, so a shortfall
now needs the page's agreement too.** On the handset (2026-10-12, Chrome 154, Wikipedia, the omnibox
hidden by a hand scroll, band `Rect(0, 141 - 1272, 2180)`), a START `⇞ page` mid-article swiped from
y 165.5 and logged `scrolled -56 of -230.0 — the document's edge`. The band's move was not forgiven,
and the corner drag took the START to the article's title (the auto-scroll above). Why the swipe fell
short is not known: the log did not show the band after the scroll. It does now
(`scrolled … of … (band <before> -> <after>)`). Whatever the cause, the scroller's scroll actions settle
it. Chrome stops listing `ACTION_SCROLL_UP` only at the page's top (the pull-to-refresh guard reads
the same list). So `judgeAndDrag` calls a short scroll the edge only when the scroller has also stopped
listing the step's direction. Where it lists no actions, the shortfall decides alone, as before. The
ordinary drag that follows is aimed at the band as re-read after the scroll. That way a returning omnibox
cannot put the held drag above the web contents.

**`adb shell run-as dev.mnaoumov.asc touch files/force-short-scroll`, then `rig.ps1 rebind`, makes
every page scroll swipe a quarter of its planned distance** on a debuggable build (it logs
`debug: every page scroll swipes a quarter of its distance`). That is how the rig reproduces the
phone's short scroll, since the rig's Chrome never hides its controls. `rm` it and rebind to undo.
Measured 2026-10-13 on the rig (Chrome 143, a served page of 200 one-line rows, scrolled ~1400 px,
long-press `long` on row 025, Chrome force-stopped before each run):

| press | old verdict | new |
|---|---|---|
| `⇞ page` on the START, marker on | `scrolled -76 of -377.0 — the document's edge`, corner drag, `Moved(23, 0)` on row 001; `← char` then `InEdgeZone` | `short, but the page can still scroll up, so not the edge`, one band (row 025 → 020); `← char` / `→ char` `23 -> 22 -> 23`, 1 gesture each, 2 runs of 2 |
| `⇞ page` on the END, marker on | not measured | one band, crossed the anchor, now the START; `27 -> 26 -> 27`, 2 runs of 2 |
| `⤒ start`, marker on | not measured | 10 ordinary pages, each short, then `up=false` → `the document's edge`, corner drag to row 001's offset 0 |
| controls, no marker: `⇞ page` ×2, `⇟ page`, `⤓ end` | as before | one band each (`scrolled -676 of -687.0`); `end` 18 pages to row 200's offset 41 via `cannot scroll down`; `← char` / `→ char` after both exact |

The phone has not run this yet. The rig cannot show which of the two causes the phone had, so the
next handset pass should read the band on that line.

**The anchor scrolling off screen costs nothing.** The announcing node follows the moving edge, so
the moving handle is always the one on screen. The pad never needs to locate the other one.

**A HAND scroll can take the moving edge off screen, and Chrome then answers `refresh()` with a box
squashed flat against the viewport's edge.** A scroll announces nothing, so the pad keeps the
announcement's bounds. `latestWithFreshBounds` re-reads them, and it used to hand the old bounds back
whenever the re-read was empty or failed. On the handset (2026-10-11), after a 1050 px hand scroll
carried the line under the omnibox, `→ char` grabbed at y 1404, the line's row from before the scroll.
It landed on page text and left the selection as a caret in another node. On the rig (2026-10-12, Chrome 143, a
served page of one-line rows) it reproduced on the first try. Long-press `alpha` at y 1000, then
`input swipe 600 1100 600 300 1200`, then `→ char`. The grab went in at y 1051, followed by six held pushes on the
page, 7 gestures. The re-read had succeeded: the node came back as `Rect(32, 271 - 502, 271)`, zero
height, at the web contents' top. Scrolled the other way, below the screen, it came back as
`Rect(32, 1472 - 502, 1472)`. So every press now starts with `SelectionDriver.movingNodeOffScreen`. It
refuses, with nothing touched, when the node cannot be re-read, when its box is empty, or when the box
misses the page's drawn area (`ContentBand.drawn`, the band before the pad is cut off, because a handle
under the pad is reachable). The press ends `OffScreen`, "the selection is scrolled out of view —
scroll it back". Measured after the change, Chrome force-stopped before each run:

| press | result |
|---|---|
| `→ char`, `⇥ word`, `⇟ page` after the 800 px scroll, 3 runs | each refused before its first gesture, 0 gestures, the status line as above |
| the page scrolled back 800 px, then `→ char` / `← char` | `13 -> 14`, `14 -> 13`, 1 gesture each, 3 of 3 |
| control: a 150 px scroll, the row still on screen | the grab aimed at the new row (y 917), `13 -> 14 -> 13` |
| control: the row scrolled down under the pad (handle at y 1243) | `13 -> 14 -> 13`, 1 gesture each; then scrolled below the screen, refused |
| controls: `⇟ page` ×2, `⤓ end` (12 pages to row 120), `← char` ×2 and `→ char` after it, `⤒ start` | as before |
| control: the debug target's `TextView`, `char →`, `char ←`, `word →` from `bravo` | `11 -> 12 -> 11`, then `11 -> 19` |

Both branches refuse: the squashed box and a failed `refresh()`, which the gotcha above records for
an off-screen anchor. The check runs once, when a press starts. Inside a press the pad's own drags
keep the moving handle on screen, so the re-reads there are unchanged.

**The phone confirms it, and takes the same branch as the rig (2026-10-14, OnePlus 15, Chrome 154,
`en.wikipedia.org/wiki/Text_editor`).** Chrome announced selections the old way (`seeded from a fresh
selection`), so the extended-selection trial was not active. Long-press `interactive` in the lead
(`4..15`, node `Rect(378, 1271 - 721, 1341)`), then `input swipe 900 1700 900 700 1200`, which carried
the line under the omnibox. The omnibox stayed shown. `refresh()` succeeded, as on the rig, and returned
the box squashed onto the web contents' top, `Rect(378, 337 - 721, 337)`:

| press | result |
|---|---|
| `→ char` after the scroll, 3 runs | `the moving edge's node has no box on screen (Rect(378, 337 - 721, 337)) — refusing to touch`, nothing dispatched, the toolbar still up; the status line read `the selection is scrolled out of view — scroll it back` |
| `⇥ word` and `⇟ page` after it, 2 runs | the same refusal |
| scrolled back (`input swipe 900 1100 900 2100 1200`), then `→ char`, `← char`, 3 runs | `15 -> 16 -> 15`, 1 gesture each, 0.58-0.74 s |
| control: a 300 px hand scroll that keeps the line on screen, then `→ char`, `← char` | grabbed at the new row (y 1114, the node now at `995..1065`), `15 -> 16 -> 15`, 1 gesture each |

The item's scroll-back swipe started at y 600, and that is where Chrome put the floating toolbar after the
scroll, so it scrolled nothing. Start such a swipe clear of the toolbar.

**A scroll that falls short means the document's edge, and the last drag then goes to the SCREEN's
corner.** The band's corner is not enough, because a document's last rows scroll no higher than the
viewport's bottom, which the docked pad covers: `end` stopped on row 394 of 400 until the target
moved. The down is on the handle, so the moves belong to the page wherever they go.

**Rows of a scrolled page land in identical rectangles, so a node is known by its identity, not by
its bounds.** Rows 047 and 058, both 27 characters long, were both at `(32, 1007 - 424, 1045)`
either side of one page step. The bounds-and-length key read that as "did not move" and stopped
`end` after one page. `nodeKey` now uses `AccessibilityNodeInfo.hashCode`, which is the node's
own id and window, and falls back to the bounds only where an event carries no node. For the same
reason, `Moved` carries `crossedNode`. Offsets from two different nodes compare as nothing: a
page step went `19 → 19` four times running, one row-shape apart.

**Chrome announces a node that does not hold the anchor as `0..focus`.** Grow the END into later
nodes and that reads correctly as `0..19`. Carry the START into an earlier node and Chrome still
says `0..14` for a START on column 14, where the covered range would be `14..27`. `low()` read the
START at column 0, where there is no handle, and the next grab scrolled the page instead.
`SelectionDriver.inMovingFrame` rewrites such an announcement to `focus..length` for the START edge.

**After a swap on a selection spanning nodes, the edge to move is in no announced node, so the
press refuses (`EdgeUnknown`, "nudge its handle once").** The announcement describes the edge that
last moved. A `page ↑` after `end` + `⇄ swap` located the START at the END row's offset 0,
`(14, 1469)`, under the pad, and dragged across the page from there. A fresh selection, or the
user dragging that handle inside one node, lifts the refusal.

**Chrome hides its handles while the page scrolls, and a grab before they fade back finds
nothing.** The eighth page of a `start` sweep grabbed where the handle belonged, announced nothing,
and scrolled the page back. The grab now waits for the toolbar to return, which took 150–250 ms,
and 150 ms more. After that, 36 pages and 30 pages ran without a miss.

**A stop touch also cuts the swipe short, which reads like the document's edge.** A tap on
`← char` 8 s into `end` left one scroll 140 px short, and the corner drag took the selection to
the bottom of the screen. Every press now clears a stop flag at its start. A touch on any direction
button sets it, and a page step checks it after its scroll, before it drags. After the change, a
tap 6 s into `start` ended with `stopped by a touch during the scroll` and the selection where the
last page had left it.

**A zero-length continuation that does NOT itself continue plays fine.** The old edge hold was
`continueStroke(moveTo(end), 0, holdMs, willContinue = false)`, and the 1200 ms hold reported
completed. It is a zero-length link that continues the chain that is refused (the held-pointer
entries above). The page scroll's stillness before the lift is exactly that final stroke.

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

## The test rig

**Emulator first, handset last (owner, 2026-09-27).** A change is developed and proven on the rig
until it fully works there. The OnePlus 15 comes in only afterwards, as a confirmation pass on the
finished behaviour. So "needs the phone" is never a reason for an item to wait before the rig work
is done. Where the handset differs in a way that matters (560 dpi against the rig's 320), reproduce
that difference in an emulator profile first. The handset confirmation is its own short item,
waiting on the owner, minted when the rig work ends.

**`scripts\rig.ps1` is the whole loop in one command.** It boots this project's own emulator, builds,
installs, rebinds the accessibility service, launches the debug target, reports every rectangle the
app logged, and takes a screenshot:

```powershell
.\scripts\rig.ps1 go # the lot, cold; -NoBuild to skip Gradle
.\scripts\rig.ps1 press '→/char'
.\scripts\rig.ps1 log 40
```

`press` matches the label the pad logs (`⇥/word`, `→/char`, `⤓/end`), glyph first. A partial label
works only when it is unique, so `char` alone matches two buttons and `char/right` matches none.

**The pad's rectangles are per foreground app, and only a relayout logs new ones.** Measured
2026-09-24: over the debug target the button row sat at y 1360, and over Chrome it sat at y 1312.
Switching app logged nothing, so `press` aimed at the old row 48 px low and hit `⇟ page` instead of
`⇥ word`. After changing the foreground app, run `rig.ps1 pad`. It rebinds the service, so the pad
logs where it really is (the process survives, and so does the boundary set). Until 2026-10-13 it
rebound only when the log held no pad rectangles at all, so after the first run of a sitting it
rebound nothing: the rectangles stayed the last app's, and the side-zone verdict, which a rebind is
meant to clear, carried over from run to run. It rebinds every time now. Never clear logcat
before a `press`, because that also discards the rectangles it reads.

Each step is also an action of its own — `up`, `down`, `build`, `install`, `rebind`, `target`, `pad`,
`buttons`, `blocks`, `press`, `tap`, `shot`, `log`, `status`, `avd`.

**`install` does not build.** It installs whatever APK is on disk, so after an edit run `build`
first, or `go`. Two measurements on 2026-09-28 ran against the previous APK because of it, and read
as a fix that had not worked.

**`rebind` waits for the system, not a clock.** It removes the service, waits until
`dumpsys accessibility` no longer lists it under `Bound services`, re-adds it, and waits until it is listed again.
Only then does it read the enabled list back. If the device dropped the add, it adds once more after
3 s, and if that is dropped too it throws and names `pad.ps1 on`. The old fixed 400 ms gap was enough on
the rig, but on the OnePlus 15 (2026-10-12) the re-add did not stick, and `pad.ps1 on` straight after
put it back. Measured 2026-10-13 on the rig: a plain rebind took 1.5 s, and a drop forced right after the
rebind was caught and re-added (15 s). The phone has not run the new rebind yet.

**It exists because a measurement should not wait on a phone being unlocked.** Almost everything
measured here before it was measured on the owner's handset, which meant every run waited on a
device being awake, unlocked and parked on the right page; twice a session stalled outright on a
locked screen, which still lists the overlay window and still swallows every injected tap, so the
run looked broken when it was only asleep. It also replaces the same six adb calls retyped a dozen
times a sitting, each one a transcription away from a wrong conclusion.

**The serial is pinned, and that is the safety property.** There is no device auto-discovery in the
script at all: it talks to `emulator-5570` and refuses everything else unless a serial is passed
deliberately. See *Never touch* — a bare adb with several devices attached picks one for you, and
"picks one for you" is how a run lands in another project's suite.

**Drive by the rectangles the app logs, never by numbers read off a screenshot.** The pad logs where
each button landed (`pad button '<label>' at x,y WxH`) and the debug target logs where each text
block landed (`target '<name>' at x,y WxH len=N`), both in raw screen pixels at layout time; `press`
and `blocks` read those lines back. Computing a coordinate from a picture instead is how a tap once
missed the pad by 11 px, landed on the page, and was written down as "gestures pass through the
overlay" — which is the opposite of the truth and cost a whole round of measurement.

**Never read the app's log with `logcat -t`.** `-t <n>` takes the last *n* lines of the WHOLE buffer
before the tag filter is applied — so on a chatty guest the app's own lines fall out of the window
and the answer is empty rather than stale. Measured 2026-09-23: the pad's twelve rectangles sat in
`logcat -d` at 17:30:31 while `-s ASC:I -d -t 80` returned nothing at 17:31:08, and `buttons` printed
"(none logged)". The rig's `Get-Log` now reads the whole tagged buffer (`-s ASC:I -d`) and takes any
tail after the filter; re-checked the same day by flooding the guest with 3000 foreign lines, after
which `-t 80` found 0 pad lines and `buttons` still listed all twelve. An ad-hoc adb read by hand
falls into the same hole, so drop the `-t` there too.

### The AVD

`asc_test`, console port **5570**, **720x1520 at 320 dpi with 2560 MB**, x86_64 on a Play-Store
system image. `.\scripts\rig.ps1 avd` says whether this machine has it and whether its shape still
matches; the image is ~8.7 GB and so lives outside the repo, but the recipe and the check do not.

Both numbers were paid for:

- **The size.** At the phone's own 1344x2992, with Chrome under a software renderer, the guest
 wedged three times in an hour and once took the whole VM down with it. 720x1520 is stable.
- **The port.** Nothing technical forces 5570. It is simply far enough from the 5554 that the first
 emulator on a machine takes that a serial typed from memory cannot reach another project's
 emulator.

**There is no `avdmanager` here and a hand-written `config.ini` boots to a hung QEMU** rather than to
an error, so the AVD is made by cloning a provisioned one: create any x86_64 AVD in Android Studio's
Device Manager, close Studio, copy its directory and `.ini` under `~/.android/avd` while skipping
`snapshots/` and the `*.img.qcow2` backing files, repoint `path` / `path.rel`, then set the shape
above plus `fastboot.forceColdBoot=yes`. `rig.ps1 avd` prints this recipe verbatim when the AVD is
missing, which is where it belongs — the reader who needs it is at a console, not in this file.

Cold boot is ~30 s headless on an unloaded machine (75 s was measured windowed, before the rig
stopped booting that way — below). **On a loaded one it is much worse, and adb goes with
it**: with several other projects' emulators and builds running, `adb devices` itself has been
measured taking over a minute, and an `adb install` that normally takes seconds does not return.
That is contention, not a broken rig — check what else is running before believing a hang.

**But a hang on an IDLE machine is the windowed GPU path, and `-no-window` is the way round it.**
Measured 2026-09-23 with nothing else running and 19 GB free: booted windowed, the guest answered
`getprop` and took a keyevent, then stopped dead during the first `adb install` — `adb devices` still
listed it while every `adb shell` timed out and the qemu process took **0 s of CPU over 12 s**. Taken
down and rebooted, it died again a minute later, this time with the process gone outright. The
emulator's own log names the cause both times:

```
Critical: Failed to load opengl32sw (The specified module could not be found.)
Warning: Software OpenGL failed. Falling back to system OpenGL.
detected a hanging thread 'QEMU2 main loop'. No response for 15024 ms
Showing crashdialog to get consent.
```

`-gpu swiftshader_indirect` alone does NOT help — it is the Qt UI window that needs `opengl32sw`, and
without a window nothing does. Launched as

```
emulator -avd asc_test -port 5570 -no-snapshot-save -no-boot-anim -no-window -gpu swiftshader_indirect -no-metrics
```

it booted in 30 s and then survived an install, four rebinds, six activity launches and about sixty
injected presses without a wobble. `screencap` works headless, so nothing the rig does is lost. The
crash dialog is the reason a wedge looks like a hang rather than a crash: the emulator is waiting for
consent nobody can give, on a window that is minimised. **`scripts\rig.ps1 up` boots with exactly
those flags now**, so the rig is headless by default; boot by hand only to look at the guest, and
expect the wedge above if you do.

**An idle emulator filled F: with one log line. `rig.ps1` now boots it with `RUST_LOG=error` and takes
it down when nobody uses it.** The emulator starts `netsimd`, and netsimd has a loop that, once its
hostapd socket closes, logs `wifi\stats.rs:121 - Frame error: ... needed length of 1 but got 0` with
no rate limit to `%TEMP%\netsimd\netsim_stderr.log`. On 2026-09-24 an emulator `up` had started at
06:01 had written **254 GB** of it by 11:34, and F: was at 0 bytes free. Nothing had used the guest
for hours. netsimd has no flag to quiet it. Its level is `RUST_LOG`, inherited from the emulator's
environment, which is how the Obsidian integration harness capped the same flood. Three parts:

- **The cap.** `Start-Rig` launches the emulator with `RUST_LOG=error`. Measured 2026-09-24: 10
  minutes of a rig session with a press a minute left the log at 585 B, with 0 Rust info/warn lines.
  The control, the same AVD booted by hand without it, wrote 33 in its first minute. netsimd is shared
  and reads its environment once, so an emulator that joins a netsimd another project started gets
  that one's level. `up` warns when it finds a netsimd already running.
- **The idle watchdog.** Every rig command stamps `build\rig\last-used`. `up` and `go` start one
  hidden `rig.ps1 watchdog` process (pid in `build\rig\watchdog.pid`). It runs `down` once the stamp is
  older than 60 minutes, and exits by itself when the emulator goes. `-IdleMinutes <n>` changes the limit,
  and `-IdleMinutes 0` starts none. Adb run by hand does not stamp, so a long hand-driven sitting should
  touch the rig now and then (`rig.ps1 status`). Measured with `-IdleMinutes 2`: down after 2.1 min,
  netsimd gone, pid file removed.
- **`down` stops netsimd too**, but only when no emulator running an AVD is left on the machine,
  because the Obsidian suites' emulators share it. netsimd can outlive its emulator: in the incident,
  qemu restarted it at 11:37 after it was killed, with no `RUST_LOG`. Killing a guest leaves a short-lived
  `emulator -kill <pid> -sleep 20` helper behind, so the helper is not counted as an emulator.

### The handset profile

**`-RigProfile handset` is a second AVD with the OnePlus 15's density**, for the one thing the rig
cannot show: every dp the app aims by is 3.5 px there against 2 px on the rig. It is `asc_handset`
on `emulator-5572`, **1272x2772 at 560 dpi with 4096 MB**. That is the phone's own screen, so Chrome's
and the pad's dp layouts have the phone's shape too, not only its density. Every action takes the
flag (`rig.ps1 go -RigProfile handset`, `press '→/char' -RigProfile handset`), and its watchdog and
heartbeat files are its own, so the two emulators can run side by side. `rig.ps1 avd -RigProfile
handset` prints the recipe, which is the rig's with the shape above. It was cloned from `asc_test`,
skipping the `*.qcow2` overlays, so its userdata starts fresh and Chrome's first run has to be
clicked through once, with the service off, as on the rig.

The rig's note that the phone's full size wedged the guest predates headless boots. Headless, this
profile booted in ~40 s and ran three measurement sittings. Two things were different from the rig:

- **Right after the first boot the guest is busy**, dexopting GMS for about a minute. The first press
  sitting ran into it: Chrome's task was closed under the press and the target activity came to the
  front. Wait a minute after the first `go` before measuring.
- **4 GB is tight.** `top` showed 0.4 GB free and swap in use while idle. Nothing was killed after the
  first minute, but a Chrome that vanishes mid-press on this profile is the memory before it is the app.

### The debug target

`app/src/debug/.../TargetActivity.kt` — three selectable blocks with **known offsets**, so an
expected result is a number rather than a guess, and it cannot reach a release build. Its blocks
must stay `WRAP_CONTENT`: left at `MATCH_PARENT` a 30-character node reported itself 1208 px wide,
every derived handle landed hundreds of pixels right of the real one, and every press failed — which
read exactly like a gesture problem and was a fixture problem.

**It logs its block rectangles on every RESUME, not on creation.** `am start` on an activity already
in the task stack resumes it without running `onCreate`, and while the rectangles were logged there,
`rig.ps1 target` answered `(none logged)` for a target plainly on screen, after the app had merely
been sent behind Chrome — and blamed the APK. Measured 2026-09-24 both ways: resumed (the same
`ActivityRecord` before and after) and force-stopped first, three blocks each time. `Start-Target`
also polls for them for up to 15 s rather than sleeping 2 s, because on a guest booted seconds
earlier the launch splash alone outlasted the sleep. **So `(none logged)` has had three causes** —
`logcat -t` windowing (above), a resume on an APK older than this, and a fixed sleep on a cold guest
— and knowing one of them is exactly what hides the other two.

**What it cannot express: a node crossing.** Each block is one `TextView`, so it is one accessibility
node, and a selection never leaves the view it started in. Anything about a grow carrying the moving
edge into the next node needs a target with an inline node tree.

### A newer Chrome on the guest

The image's Chrome is 143, and Play cannot update it without a signed-in account. A current Chromium
installs beside it as `org.chromium.chrome`. Get `AndroidDesktop_x64/<rev>/chrome-android-desktop.zip`
from the `chromium-browser-snapshots` bucket (`LAST_CHANGE` names the newest revision; ~500 MB), then
`adb install -r` its `apks/ChromePublic.apk`, which runs on the x86_64 guest. Click through its first run
with the service off, as for Chrome, and open pages with `-p org.chromium.chrome`.

### A multi-node target on the guest, with no network

This AVD has Chrome (`com.android.chrome`) and the guest has no usable network, which sounds like the
end of it. It is not: **`chrome://terms` is refused, and Chrome's own `ERR_INVALID_URL` page is three
adjacent inline nodes on one paragraph** — local, offline, identical every time, and reported
individually by `uiautomator dump` once the service is bound:

```
[48,731][258,767] "The webpage at " (15 chars)
[256,731][466,767] "chrome://terms/" (15 chars)
[48,731][584,863] " might be temporarily down or..." (wraps)
```

Two practical notes for driving it:

- **Chrome's first run cannot be clicked through while the pad is up.** The pad's window covers the
 bottom of the screen, which is exactly where the sign-in and notification prompts put their buttons,
 and it swallows every injected tap there. Empty
 `settings put secure enabled_accessibility_services '""'` first, finish the first run, then
 `rig.ps1 rebind`.
- **Aim by the node tree, not by the screenshot**, the same discipline the app's own logged rectangles
 buy elsewhere: `uiautomator dump` gives each inline node's bounds, and the character pitch across a
 node whose box hugs its text is `width / text.length`. `chrome://` URLs are rejected from an intent,
 so the address is typed — `input tap` the omnibox, `input text`, `keyevent 66`.

**The same page is also the WRAPPED target**, which matters because no `TextView` in the debug target
can be one: its third node, `" might be temporarily down or it may have moved permanently to a new web
address."`, is 81 characters over three lines at `[48,731][584,863]`. Long-press its third line at about
`(300, 845)` and the selection lands at offsets 65..68. `chrome://version` gives several more — the
`useragent` row is 110 characters over eight lines — and the two pages together are how the
character-rect priming above was measured.

**A page of your own is served from the host, not typed as a `data:` URL.** An `am start` of a
`data:` URI is refused ("unable to resolve Intent"), and one typed into the omnibox with `input text`
came back as a Google search twice (2026-09-24). What works: a PowerShell `HttpListener` on
`http://127.0.0.1:<port>/` on the host, `adb -s emulator-5570 reverse tcp:<port> tcp:<port>`, and an
`am start -a android.intent.action.VIEW -d http://127.0.0.1:<port>/page.html -p com.android.chrome`.
The guest has no network, but a reversed port is not network. **Keep the text away from the top of
the page** unless the toolbar is what you are measuring: a selection near the top gets its floating
toolbar BELOW it, on top of the handle, and the pad then aims at the handle's bare band.

**Force-stop Chrome between runs of that measurement.** Inline text boxes, once loaded for a node,
stay loaded, so a second run against a warm Chrome is answered honestly on the FIRST ask and proves
nothing: `adb shell am force-stop com.android.chrome` is what makes the measurement mean anything.

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

**The release bundle** is `:app:bundleRelease`, signed with the Play upload key when four
`ascUpload*` Gradle properties (or `ASC_UPLOAD_*` environment variables) name it, and unsigned when
none do. The key and its passwords never enter the repo, which is public; `play/README.md` has the
names, the `keytool` line and the upload order. A partial configuration fails the build by design,
because a bundle signed with nothing is refused by Play loudly while one half-configured is a
mistake that should not reach the Console.

Measured 2026-09-23 with a throwaway key: `apksigner verify` reads the release APK as signed by it,
`jarsigner -verify` accepts the `.aab`, and `aapt2 dump permissions` on the release APK prints the
package line and nothing else — the zero-permission property holds for the build that ships, not
only the debug one.

**Nothing in `play/` is generated except the two PNGs**, which `play/graphics/render.ps1` renders
from their SVGs with a headless Chrome and `.gitignore` keeps out. The launcher icon's vector and
`play/graphics/icon.svg` share one geometry and have to be changed together.

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

The launcher entry (**Selection Pad**) shows the Play-required disclosure, which ends in a real choice:
agree, which opens Accessibility settings, or **No thanks**, which closes the screen and leaves the
service off (both checked on the rig 2026-09-23). On this device the settings link is informational, since the toggle will not be there.

The pad appears as soon as the service connects. Its buttons take **real injected input** —
`adb shell input tap <x> <y>` — which is the right way to test them: the service's own
`dispatchGesture` is a different input path, and O1 was careful about that distinction.

**A tap steps once; holding a direction button arms a run that starts when the finger LIFTS, and any
touch stops it.** Nothing is dispatched while a finger is on the glass, and that is forced rather
than chosen (the held-pointer fix): a real touch ends the target app's tracking of a held pointer, so stepping on
`ACTION_DOWN` started a chain that the button's own `ACTION_UP` cancelled about 100 ms later, every
single press. The stop gesture costs nothing to implement for the same reason — the touch would end
the run's tracking regardless.

The run is still chained off completion rather than driven by a timer. A press is a closed loop, so a
fixed-interval repeat would queue steps faster than they finish and the surplus would vanish on the
`busy` guard; the next step starts when the previous one *ends*, which self-paces for free.

A run stops on the first step that does not move the selection. That is a **safety** rule, and it
matters more now that no finger is resting on a button to act as the brake: a failed step failed by
dragging where no handle was, and a drag that misses lands on the page, which on a link navigates.

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
| `draghold <x1> <y1> <x2> <y2> [dragMs] [holdMs]` | Drag then **hold without lifting** (two chained strokes), for the edge auto-scroll that plain `drag` can never trigger. Chrome on the rig never auto-scrolls a held handle at all; see *Page, start and end* |

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
Chrome numbers. They are **Chrome's**; Keep draws circles at the selection's corners instead. They are also
the HANDSET's raw pixels (560 dpi): the spike never scaled them, so on the rig it aims ~30 px below the
handle. The app works in dp (the `HANDLE_DROP_DP` gotcha above).

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
integration suites, which are worked in other sessions. They are started and stopped by those
suites while this project is working, so their presence in `adb devices` comes and goes — do not
read that churn as anything to do with this project, and never reach for one because it happens to
be the only device attached.

**This project's own emulator is `asc_test` on `emulator-5570`, and `scripts\rig.ps1` talks to that
serial alone.** Everything else needs a serial passed by hand. A physical device is still the right
target for anything about a real ROM's Enhanced Confirmation Mode, real handle geometry, or a real
browser — the rig is for everything else.

**The emulator does not survive a busy machine, and the failure does not look like one.** Measured
2026-09-20 with seven other jobs pinning every core: the guest boots fine, answers `adb shell` three
times, and then stops answering it at all — while its process stays alive and `adb devices` still
reports it. The control is what makes this worth writing down: **the probe was run on a guest with
nothing installed and no accessibility service enabled**, so it is neither this app nor the rebind,
both of which the earlier runs looked like they had caught. `.\scripts\rig.ps1 down` gets out of
that state (it kills by AVD command line and clears the locks a dead emulator leaves); nothing
short of a quieter machine prevents it.
