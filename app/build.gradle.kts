plugins {
  id("com.android.application")
}

/*
 * The upload key, and why none of it is in this repo.
 *
 * Play App Signing holds the key that signs what users install; this build only signs with the
 * UPLOAD key, which Play uses to check that a bundle came from us. An upload key can be reset
 * through Play if it is lost or leaked, so it is a credential rather than an identity — but it is
 * still a credential, and the repo is public. So the keystore and its passwords live outside the
 * checkout, and the build reads four values, each from a Gradle property (normally set in
 * `~/.gradle/gradle.properties`) or else from an environment variable:
 *
 *   ascUploadStoreFile      ASC_UPLOAD_STORE_FILE      path to the .jks
 *   ascUploadStorePassword  ASC_UPLOAD_STORE_PASSWORD
 *   ascUploadKeyAlias       ASC_UPLOAD_KEY_ALIAS
 *   ascUploadKeyPassword    ASC_UPLOAD_KEY_PASSWORD
 *
 * With none of them set a release still builds, unsigned — which is what a clone on another
 * machine gets, and Play refuses such a bundle outright rather than accepting the wrong one.
 * Setting only some of them is a mistake worth stopping on, so that fails the build.
 */
fun uploadSetting(property: String, variable: String): String? =
  providers.gradleProperty(property).orElse(providers.environmentVariable(variable)).orNull
    ?.takeIf { it.isNotBlank() }

val uploadSettings = mapOf(
  "storeFile" to uploadSetting("ascUploadStoreFile", "ASC_UPLOAD_STORE_FILE"),
  "storePassword" to uploadSetting("ascUploadStorePassword", "ASC_UPLOAD_STORE_PASSWORD"),
  "keyAlias" to uploadSetting("ascUploadKeyAlias", "ASC_UPLOAD_KEY_ALIAS"),
  "keyPassword" to uploadSetting("ascUploadKeyPassword", "ASC_UPLOAD_KEY_PASSWORD"),
)

val uploadKeyConfigured = uploadSettings.values.all { it != null }

if (!uploadKeyConfigured && uploadSettings.values.any { it != null }) {
  val missing = uploadSettings.filterValues { it == null }.keys.joinToString()
  throw GradleException("The upload key is only partly configured; missing: $missing")
}

android {
  namespace = "dev.mnaoumov.asc"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.mnaoumov.asc"

    // TYPE_ACCESSIBILITY_OVERLAY is API 22 and dispatchGesture is API 24; the floor here is set by
    // nothing more exotic than wanting a modern baseline. The pad build briefly raised a spike to 34 for
    // attachAccessibilityOverlayToDisplay, which turned out not to work from an ordinary app.
    minSdk = 33
    targetSdk = 36

    // Play refuses a second upload with a versionCode it has seen, so this goes up by one for every
    // bundle that leaves the machine — including one that only ever reaches the internal track.
    versionCode = 1
    versionName = "0.1"
  }

  signingConfigs {
    if (uploadKeyConfigured) {
      create("upload") {
        storeFile = file(uploadSettings.getValue("storeFile")!!)
        storePassword = uploadSettings.getValue("storePassword")
        keyAlias = uploadSettings.getValue("keyAlias")
        keyPassword = uploadSettings.getValue("keyPassword")
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      if (uploadKeyConfigured) signingConfig = signingConfigs.getByName("upload")
    }
  }
}
