# Android Selection Control

Cursor and selection control for Android text that **no keyboard can reach** — text that is
selectable by touch but not editable: a web page in a browser, a reading view, a rendered document.

Android gives you a caret and arrow keys only inside an editable field. Everywhere else, adjusting a
selection means dragging a handle with your finger and hoping it lands on the right character. This
app gives that text the same precise, one-character-at-a-time control an editable field has.

## How it works

The obvious approach does not work, and establishing that is most of what this repo knows.

**Accessibility selection actions stop at the editable-text boundary.** `ACTION_SET_SELECTION` and
the movement-granularity actions are exactly the API for this, and Chrome's page nodes refuse them:
they report `sel=false edit=false`, `performAction` returns `false`, and granularity-with-extend
returns `true` while moving nothing. That was measured on a real device, not inferred, and it rules
out the entire action-based route.

**So the app drives the target app's own selection UI instead.** An accessibility service
synthesises the touch gestures a person would make — `dispatchGesture` performs the long-press that
starts a selection, then drags the resulting handle by an exact number of pixels. Where the handles
are comes from `TYPE_VIEW_TEXT_SELECTION_CHANGED` events (character offsets plus the source's screen
bounds) combined with the node tree. **No screenshot analysis is involved.**

That gesture half generalises across surfaces — it was measured working in Chrome, Obsidian's
editing and reading views, Google Keep, Google Docs and Gecko via Tor. The *observation* half does
not generalise as cleanly: Google Docs, for instance, fires no selection event at all, so the app
falls back to reading the node tree.

A floating pad provides the controls: move by character or by word, move either edge, and swap which
edge is moving.

## Repository layout

| path | what it is |
| --- | --- |
| `app/` | the product — the accessibility service, the handle locator and the floating pad |
| `spike/` | a throwaway, adb-driven diagnostic service. **Not app code**, never promoted |

The two are **separate Gradle projects** with separate wrappers; the root `settings.gradle.kts`
deliberately does not include `spike/`. The spike is kept because it is how every mechanism above
was measured, and how the next one will be.

## Building

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then enable **Android Selection Control** in Settings → Accessibility.

`minSdk` is 33, `targetSdk` 36, application id `dev.mnaoumov.asc`.

### One thing that will surprise you

On recent Android, an accessibility service installed by `adb install` is blocked from being enabled
by **Enhanced Confirmation Mode**, which keys on install provenance: a sideload leaves
`installerPackageName=null` and the toggle refuses. This is a property of how the APK was installed,
not of the app. `AGENTS.md` has the full findings and the workarounds.

## Permissions

The service declares `canPerformGestures` — that is the whole mechanism — and
`flagRetrieveInteractiveWindows`, which is needed to locate the floating selection toolbar when a
node's bounds do not hug its text.

It deliberately does **not** claim `isAccessibilityTool`. This is an ordinary app on the ordinary
distribution lane; claiming that flag would be arguable and risky.

The app requests **no INTERNET permission** and has never had one.

## Status

Working and measured on a physical device. The selection loop closes: a press moves the selection by
exactly one character or one word, and the app verifies the result rather than assuming it.

`AGENTS.md` carries the build and run mechanics, the measured behaviour of each surface, and the
gotchas — including several claims that looked obvious and turned out to be false when measured.

## Licence

MIT. See [LICENSE](LICENSE).
