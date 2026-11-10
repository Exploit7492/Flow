import com.diffplug.spotless.LineEnding

buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

// Top-level build file
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("com.android.test") version "9.3.1" apply false
    id("androidx.baselineprofile") version "1.5.0-rc01" apply false
    id("com.diffplug.spotless") version "8.10.0"
    alias(libs.plugins.room) apply false
}

spotless {
    // Adopt formatting incrementally from the commit that introduced linting (dcb3d696e). The
    // historical value 52c4928e was removed from this repository when its history was rewritten
    // after a storage mirror loss, which made spotless abort with a jgit MissingObjectException
    // before it could check anything. Its pre-lint parent 4d6f59f8 is the same baseline: it is
    // the last revision before the formatter was adopted.
    ratchetFrom("4d6f59f80d40adee608ea8000eb1092bb1a2d0ef")
    lineEndings = LineEnding.UNIX

    val ktlintConfig =
        mapOf(
            "ij_kotlin_packages_to_use_import_on_demand" to "**",
        )

    kotlin {
        target(
            "app/src/**/*.kt",
            "benchmark/src/**/*.kt",
        )
        targetExclude(
            "**/build/**",
            "**/generated/**",
        )
        ktlint("1.8.0").editorConfigOverride(ktlintConfig)
    }

    kotlinGradle {
        target(
            "*.gradle.kts",
            "app/*.gradle.kts",
            "benchmark/*.gradle.kts",
        )
        targetExclude(
            "**/build/**",
        )
        ktlint("1.8.0").editorConfigOverride(ktlintConfig)
    }
}

tasks.register("ktlintCheck") {
    group = "verification"
    description = "Checks Kotlin formatting with ktlint."
    dependsOn("spotlessCheck")
}

tasks.register("ktlintFormat") {
    group = "formatting"
    description = "Formats changed Kotlin files with ktlint."
    dependsOn("spotlessApply")
}
