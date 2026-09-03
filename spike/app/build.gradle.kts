plugins {
  id("com.android.application")
}

android {
  namespace = "dev.mnaoumov.asc.spike"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.mnaoumov.asc.spike"

    // isTextSelectable() — the property the first spike was about — is API 33, and that is still the floor.
    // This briefly went to 34 for attachAccessibilityOverlayToDisplay; that API turned out not to
    // work from an ordinary app, and the overlay type that does (TYPE_ACCESSIBILITY_OVERLAY) is
    // API 22, so the raise bought nothing and was reverted.
    minSdk = 33
    targetSdk = 36

    versionCode = 1
    versionName = "0.1-spike"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }
}
