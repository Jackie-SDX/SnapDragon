// Plugin versions are declared once, here (with apply false), and
// applied without a version in each module's own build.gradle.kts.
// This keeps every module on the same versions without repeating them.
//
// Versions current as of Sept 2026. Android Studio may prompt to
// upgrade the Android Gradle Plugin when you open this project — that
// prompt is normal and safe to accept.
plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("android") version "2.4.10" apply false
    kotlin("plugin.compose") version "2.4.10" apply false
    id("com.android.application") version "9.1.1" apply false
}

