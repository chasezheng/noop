// Root build file — declares plugin versions once; applied per-module in app/build.gradle.kts.
// Keep these versions aligned with the shared contract:
//   Android Gradle Plugin 8.x · Kotlin 1.9.x · KSP matched to the Kotlin version · Room 2.6.x.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // KSP version is <kotlinVersion>-<kspVersion>; must track the Kotlin version exactly.
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    // The `:calbench` harness is plain JVM: it compiles the app's pure analytics sources without
    // the Android plugin, so it needs the JVM flavour of the same Kotlin version.
    id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
}
