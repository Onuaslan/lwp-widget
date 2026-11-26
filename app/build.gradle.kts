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

    // Fester Schlüssel aus den Repo-Secrets: dann lässt sich jede neue Version einfach drüberinstallieren.
    // Ohne Secrets (z.B. lokal) wird mit dem Debug-Schlüssel signiert.
    val keystoreFile = System.getenv("SIGNING_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystoreFile != null) {
            create("fixed") {
                storeFile = keystoreFile
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "lwp"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("fixed") ?: signingConfigs.getByName("debug")
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
