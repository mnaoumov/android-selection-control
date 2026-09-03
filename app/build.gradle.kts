plugins {
  id("com.android.application")
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

    versionCode = 1
    versionName = "0.1"
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
