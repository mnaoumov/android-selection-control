# The review video

Play asks an app that uses the Accessibility API, and is not an `isAccessibilityTool`, for a short
video showing the prominent disclosure and the feature that needs the API. It is uploaded to
YouTube as **unlisted** and the link goes in the declaration form (see `console-answers.md`).

It has to show, in this order, with nothing cut between the steps:

1. **Launching the app from the launcher.** The disclosure screen is the first thing it shows.
2. **The disclosure, readable.** Scroll it slowly, top to bottom, so the whole text is on screen at
   some point. It must be the app's own screen, during normal use — not a settings page, not the
   privacy policy.
3. **The affirmative choice.** Press *"I understand — open Accessibility settings"*. Showing
   *"No thanks"* first, then relaunching and agreeing, is better still: it proves the choice is a
   real one.
4. **Turning the service on**, in the Accessibility settings page the button opened: the
   **Selection Pad** row, its switch, and Android's own confirmation dialog.
5. **The feature.** Open a web page in Chrome, long-press a word, and use the pad: a few character
   steps each way, a word step, the swap button, and a held button that keeps stepping until a
   touch stops it. Then copy the selection with the page's own menu, to show the point of it.
6. **Turning it off** again in Accessibility settings, and the pad disappearing.

About a minute and a half is plenty. No narration is needed; captions are optional.

## Where to record it

**On a phone, with the app installed from Play** — an internal-testing release is enough. Step 4
cannot be recorded any other way on a recent Android: an `adb install` is treated as sideloaded,
and Enhanced Confirmation Mode then hides the service's switch entirely (`AGENTS.md` has the
measurement). A build installed by Play is not blocked, and it is also exactly what a reviewer will
run.

Record with the phone's own screen recorder, or over adb:

```powershell
adb -s <device-serial> shell screenrecord --bit-rate 8000000 /sdcard/review.mp4   # Ctrl+C to stop; 3 min cap
adb -s <device-serial> pull /sdcard/review.mp4
```
