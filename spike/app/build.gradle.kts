plugins {
  id("com.android.application")
}

android {
  namespace = "dev.mnaoumov.asc.spike"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.mnaoumov.asc.spike"

    // isTextSelectable() — the property this whole spike is about — is API 33.
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
