plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("com.noop.calbench.MainKt") }

// The app's own sources, compiled a second time for a plain JVM rather than copied or moved.
//
// An include list and not a whole-tree add: `com.noop.analytics` is database-free by contract, but a
// handful of its files reach for `WhoopRepository`, a `DeviceRegistry` or a `Context`, and everything
// under `com.noop.data` beyond the entity value types is Room. Each exclusion below is one of those,
// or a file whose only dependency is one of those. Anything the list gets wrong fails to compile
// here, which is the point: the harness cannot quietly diverge from the shipped model, because it IS
// the shipped model. If a future analytics file on the calorie path gains an Android import, this
// build breaks — which is the signal, not the problem.
sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.srcDir("src/shim/kotlin")
        kotlin.setIncludes(
            listOf(
                "android/**",
                "com/noop/calbench/**",
                "com/noop/analytics/**",
                "com/noop/data/Entities.kt",
                "com/noop/data/OuraRespScale.kt",
                "com/noop/data/DeviceBrandCatalog.kt",
                "com/noop/data/PairedDevice.kt",
                "com/noop/data/V18AuxCodec.kt",
                "com/noop/protocol/DeviceFamily.kt",
                "com/noop/protocol/Streams.kt",
                "com/noop/protocol/Whoop5RawImu.kt",
                "com/noop/protocol/ParsedFrame.kt",
            ),
        )
        kotlin.exclude(
            "com/noop/analytics/CalorieBasisRescore.kt",
            "com/noop/analytics/DayCycleIntelligenceIntegration.kt",
            "com/noop/analytics/HydrationStore.kt",
            "com/noop/analytics/IntelligenceEngine.kt",
            "com/noop/analytics/IntelligencePersistence.kt",
            "com/noop/analytics/NapDetector.kt",
            "com/noop/analytics/NapPrefs.kt",
            "com/noop/analytics/PhysiologicalStepCycleEngine.kt",
            "com/noop/analytics/RegistryDayOwnerSource.kt",
            "com/noop/analytics/SleepStageHealer.kt",
            "com/noop/analytics/WorkoutSport.kt",
        )
    }
}

dependencies {
    // Room's entity annotations only. `room-common` is a plain JVM jar, so `Entities.kt` compiles
    // here unchanged; nothing in this module opens a Room database.
    implementation("androidx.room:room-common:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // Android ships `org.json` inside android.jar, so `:app` never declares it for its main source
    // set. On a plain JVM it has to come from somewhere.
    implementation("org.json:json:20240303")
    implementation("org.xerial:sqlite-jdbc:3.41.2.2")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
