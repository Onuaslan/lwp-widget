plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.onur.aquarea"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.onur.aquarea.widget"
        minSdk = 26
        targetSdk = 35
        // Jede GitHub-Version bekommt eine eigene, steigende Versionsnummer. Sonst halten manche
        // Launcher (z.B. HyperOS) alte Widget-Daten im Cache und blenden das Widget aus.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Zum privaten Installieren reicht die Debug-Signatur.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

dependencies {
    implementation(project(":api"))
}
