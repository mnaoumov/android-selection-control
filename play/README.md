# Google Play

Everything the Play listing needs that can live in the repository: the listing text, the privacy
policy, the answers to the Console's declaration forms, the store graphics, and how to build and
sign the bundle.

**Play is not optional for this app.** On a recent Android, an accessibility service installed by
`adb install` is treated as sideloaded, and Enhanced Confirmation Mode then hides its switch in
Accessibility settings entirely — no user setting and no adb command lifts it. A trusted installer
does, and Play is the one that matters. See `AGENTS.md` for the measurement.

| file | what it is |
| --- | --- |
| `listing/en-US/title.txt` | app name, 30 characters at most |
| `listing/en-US/short-description.txt` | 80 characters at most |
| `listing/en-US/full-description.txt` | 4000 characters at most; plain text |
| `privacy-policy.md` | the policy; its URL in the public repository is what the Console asks for |
| `console-answers.md` | Accessibility declaration, Data safety and the other App content forms |
| `review-video.md` | what the declaration's video has to show, and where to record it |
| `graphics/icon.svg` | the 512 × 512 store icon; same geometry as the launcher icon |
| `graphics/feature-graphic.svg` | the 1024 × 500 feature graphic |
| `graphics/render.ps1` | renders both SVGs to the PNGs the Console takes, with a headless browser |

## The upload key

Play App Signing holds the key that signs what users install. This build signs only with the
**upload key**, which proves a bundle came from the developer; if it is lost or leaked it can be
reset through Play support, so it is a credential rather than the app's identity.

It lives **outside the checkout**, and so do its passwords. Create it once:

```powershell
$jbr = "C:\Program Files\Android\Android Studio\jbr"
& "$jbr\bin\keytool.exe" -genkeypair -v -keystore "$env:USERPROFILE\.android\asc-upload.jks" `
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Then give the build its four values in `~/.gradle/gradle.properties` (outside any repository):

```properties
ascUploadStoreFile=C:/Users/<you>/.android/asc-upload.jks
ascUploadStorePassword=...
ascUploadKeyAlias=upload
ascUploadKeyPassword=...
```

Each can instead be an environment variable (`ASC_UPLOAD_STORE_FILE`, `ASC_UPLOAD_STORE_PASSWORD`,
`ASC_UPLOAD_KEY_ALIAS`, `ASC_UPLOAD_KEY_PASSWORD`), which is the form a CI job would use. With none
of them set the release builds **unsigned**; with only some of them set the build stops and names
the missing ones.

**Back the keystore and its passwords up somewhere other than this machine.** A reset is possible,
but it takes a support request and days.

## Building the bundle

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:bundleRelease
# -> app\build\outputs\bundle\release\app-release.aab
```

Raise `versionCode` in `app/build.gradle.kts` before every upload: Play refuses a code it has
already seen, on any track.

Check the zero-permission property on the release build, not just the debug one — it is the
strongest thing the listing says:

```powershell
.\gradlew.bat :app:assembleRelease
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\aapt2.exe" dump permissions `
  app\build\outputs\apk\release\app-release.apk
# package: dev.mnaoumov.asc      <- and nothing under it
```

## The order of a first release

1. Create the app in the Play Console (name *Selection Pad*, app, free).
2. Fill **App content** from `console-answers.md`, the privacy policy URL included.
3. Fill the **store listing** from `listing/` and `graphics/`, plus phone screenshots.
4. Upload the bundle to **internal testing** and install it on a phone from Play.
5. Record the review video on that install (`review-video.md`), upload it to YouTube as unlisted,
   and add the link to the Accessibility declaration. If the Console will not roll out even the
   internal release until the declaration is complete, record it instead on a build installed
   some other way on a device where the switch can be reached — on Android 13 and 14 the App info
   page's ⋮ menu offers *Allow restricted settings* for a sideloaded app.
6. Promote to closed testing, then production. A new personal developer account must first run a
   closed test with testers for a period before production access is granted; the Console states
   the current numbers.
