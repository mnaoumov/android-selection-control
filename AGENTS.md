# android-selection-control

Android app giving **cursor and selection control where no keyboard can reach** — text that is
selectable by touch but *not* editable: a web page in a browser, a read-only view.

Tracked centrally in a private tracker. Five items are closed and hold the history: **the first spike**
(NO-GO — the accessibility selection actions stop at the editable-buffer boundary), **the gesture spike** (GO —
`dispatchGesture` drives the target app's own selection UI, plus the per-app observation matrix),
**The pad build** (the pad itself, built and measured), **the held-pointer fix** (the held pointer, and why a
character step now costs one gesture), and **the swap-edge fix** (the swap button, and the one reading of "where
the moving edge is" that both edges now share). Read the gesture spike for the mechanism, the pad build for what the pad
does, and the held-pointer fix for everything a continued stroke will and will not tolerate.

Open work is split by shape, one item each in that same store, and named there rather than here:
driving the page / start / end buttons to completion; locating a handle that the aimed-at drop misses;
and Play distribution, which ECM makes mandatory rather than optional. The test rig is now a repo asset — see *The test rig* below.

A handle inside a **wrapped** node was on that list and is no longer: the row it needs comes from the
platform's own per-character rectangle, which Chrome does supply for page content once the node has
been asked twice. The gotcha that said otherwise had stood since the first spike and was the costliest
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

**A second trap sits on top of it: do not cache the "no".** The rung memoises per announcement so a step
pays one IPC rather than two, and the first version cached the refusal too. That is invisible and total:
the second press on an unchanged selection returned the memo without asking, so the answer Chrome was by
then ready to give could never arrive, and the whole thing read as a permanent refusal. Only a successful
answer is cached now.

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
it declines on the FIRST ask and answers on a later one (the gotcha above), so a one-line node warmed
by a press or by the primer is sized by a real glyph there too. It must be asked for the
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
glyphs of the whole run (`HandleLocator.characterWidths`), not by one glyph times a count. Over 24
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
miss there costs the user their selection.

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
 the bounds in the key are what told them apart.
- **A scroll does NOT clear, and that is correct.** `Google` seeded `[0, 6]` on `chrome://version/`.
 After the page scrolled ~90 px, the next press still read `[0, 6]`, and its grab was aimed at the
 new row (y 375). The key is taken from the last *announcement*, and a scroll announces nothing. The
 node and its offsets are unchanged, so keeping the set is the right answer. The earlier plan
 expected a clear here, and that expectation was wrong.

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

Two consequences worth knowing before reading a log: seven of those eight gestures are spent
discovering a floor that is already known, which is most of a 3.6 s press; and the run ends with the
selection one character wide rather than empty, so the next command starts from 12..13 and not from a
caret.

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

**Not yet re-measured on the handset.** There the aim moved from 57 px to 49 px and from 30 to 31.5,
well inside the ~48 px-radius handle the gesture spike measured, but no press has confirmed it.

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
logs where it really is (the process survives, and so does the boundary set). Never clear logcat
before a `press`, because that also discards the rectangles it reads.

Each step is also an action of its own — `up`, `down`, `build`, `install`, `rebind`, `target`, `pad`,
`buttons`, `blocks`, `press`, `tap`, `shot`, `log`, `status`, `avd`.

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
