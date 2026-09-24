# Selection Pad — Privacy Policy

*Effective 2026-09-23. Applies to the Android app **Selection Pad** (`dev.mnaoumov.asc`).*

## The short version

Selection Pad collects nothing, stores nothing and sends nothing. It cannot connect to the
internet at all: the app requests **no Android permissions**, including no internet access, so the
operating system itself prevents it from sending anything off the device.

## What the app accesses, and why

Selection Pad is an **accessibility service**. It uses Android's Accessibility Service API for one
purpose: moving the text selection in other apps, where no keyboard can reach — for example on a
web page in a browser.

While you have the service turned on, it uses that access to:

- **Receive selection-change events** from the app in front of you. From these it uses the
  *position* of the selection: where it starts and ends, as character counts and as rectangles on
  the screen.
- **Look up the on-screen position and size** of the text element holding the selection, and of
  the selection menu the other app shows, so it knows where that app's selection handles are.
- **Perform touch gestures** — a drag on a selection handle, or a long press — once per button you
  press on the pad, to move the selection exactly as your own finger would.

It **does not read the text** you select or any other text on screen. It uses the *length* of a
text element and the *rectangles* its characters occupy, never the characters themselves.

## What the app stores

Nothing. It keeps the position of the current selection in memory only while it is working on
it, and it has no files, database or settings that hold any of the above.

The app writes diagnostic lines to the device's system log (logcat), as Android apps commonly do.
Those lines contain only numbers — offsets, pixel positions and timings — never text, and the app
does not read or transmit them.

## What the app shares

Nothing, with anyone. There is no network access, no analytics, no advertising, no crash
reporting and no third-party code that could do any of these.

## Your control

The service does nothing until you turn it on yourself in **Settings → Accessibility**, after the
app has explained what it does and you have agreed. Turning it off there stops it completely.
Uninstalling the app removes it entirely.

## Children

Selection Pad is not directed at children and collects no information from anyone.

## Changes

If this policy ever changes, the new version will be published at this same address with a new
effective date. Because the app has no internet access, any change to what it can do requires a
new version of the app that you install yourself.

## Contact

Questions about this policy: open an issue on the project's public repository, or use the
developer contact address shown on the app's Google Play listing.
