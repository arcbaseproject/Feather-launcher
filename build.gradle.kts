buildscript {
    dependencies {
        // AGP 9 compiles Kotlin itself; this only pins the Kotlin compiler version.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
}
