# Play Console answers

What to enter in the Play Console forms, written down once so the answers are consistent with the
listing, the in-app disclosure and the privacy policy — and so the next release does not have to
re-derive them. Each answer says why, because a reviewer's follow-up question is answered from the
reason, not from the checkbox.

## App content → Accessibility API (the Permissions Declaration)

**Is the app an accessibility tool (`isAccessibilityTool`)?** No.

The flag is for apps whose primary purpose is helping people with disabilities — screen readers,
switch access, braille. Selection Pad helps anyone who finds dragging a selection handle
imprecise, and that audience certainly includes people with motor impairments, but claiming the
flag would be arguable, and the ordinary declaration lane exists for exactly this kind of app. The
service config deliberately does not set it (`app/src/main/res/xml/accessibility_service_config.xml`).

**Core functionality that uses the API** (the free-text field):

> Selection Pad moves the text selection in other apps, where no keyboard can reach — for example
> on a web page in a browser, where Android offers no cursor keys and the only way to adjust a
> selection is to drag its handle by finger. The app shows a small floating pad of buttons. Each
> press uses the Accessibility Service API to (1) read where the current selection starts and ends,
> from TYPE_VIEW_TEXT_SELECTION_CHANGED events and the on-screen bounds of the source node and of
> the selection toolbar window, and (2) dispatch a drag gesture (dispatchGesture) on the selection
> handle that the other app already shows, moving it by one character or one word. The pad itself
> is a TYPE_ACCESSIBILITY_OVERLAY window. No other API can do this: the accessibility selection
> actions do not work on non-editable text such as web page content, and an input method is only
> invoked for editable fields.

**Data the service accesses, and what happens to it:**

> Only positions: selection offsets, text lengths and on-screen rectangles. The service never reads
> the text content. Nothing is stored and nothing is transmitted — the app requests no permissions
> at all, including no INTERNET permission, so it has no network access.

**Prominent disclosure:** shown by the launcher activity (`MainActivity`) during normal use, before
the user is sent to Accessibility settings. It states what is accessed and why, and requires an
affirmative choice: *"I understand — open Accessibility settings"* or *"No thanks"*. Declining
leaves the service off. The review video shows it (see `review-video.md`).

**Video link:** the unlisted YouTube upload of the review video.

## App content → Data safety

- **Does the app collect or share any of the required user data types?** No.
- **Is all user data encrypted in transit?** Not applicable — the app transmits nothing. (The form
  only asks this when something is collected; answering "no data collected" skips it.)
- **Account deletion:** not applicable — there are no accounts.

Why "no" is accurate rather than convenient: Play defines *collection* as transmitting data off the
device. Data processed only on the device is not collected, and this app cannot transmit — it has
no INTERNET permission, which is enforced by the kernel rather than promised.

## App content → other declarations

| form | answer | why |
| --- | --- | --- |
| Privacy policy | URL of `play/privacy-policy.md` in the public repository | required for every app, and for any app using the Accessibility API |
| Ads | No ads | there is no ad code |
| App access | All functionality is available without special access | no login, no account |
| Content rating | Utility / productivity questionnaire; no violence, no user-generated content, no sharing of location or personal information | it is a text-selection tool |
| Target audience | 18 and over | not designed for children; avoids the Families policy, which the app would not meet anyway since it does not target them |
| News app | No | |
| Government app | No | |
| Financial features | None | |
| Health | None | |

## Store listing

- **App name, short and full description:** `listing/en-US/`.
- **App icon, 512 × 512 PNG:** rendered from `graphics/icon.svg` (see `README.md`).
- **Feature graphic, 1024 × 500 PNG:** rendered from `graphics/feature-graphic.svg`.
- **Phone screenshots:** at least two; see `README.md` for how they are taken.
- **Category:** Tools.
- **Contact email:** the developer account's.
