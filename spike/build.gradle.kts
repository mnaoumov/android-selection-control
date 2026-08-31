// AGP 9.0+ has built-in Kotlin support, so `org.jetbrains.kotlin.android` is deliberately absent —
// adding it back is an error, not a redundancy. See https://kotl.in/gradle/agp-built-in-kotlin
plugins {
  id("com.android.application") version "9.3.2" apply false
}
