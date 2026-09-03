pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "android-selection-control"

// `spike/` is deliberately NOT included. It is a standalone Gradle build and a throwaway diagnostic
// harness — the thing that measured every mechanism this app rests on — and none of it is app code.
include(":app")
