import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Same release key as the app (keystore.properties, not in git). Falls back to debug key.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Empty identity shells. Each slot only grants Media Booster the right to post
// notifications in its name, so the system shows separate player cards.
android {
    namespace = "io.github.intersebbtor.mediabooster.slot"
    compileSdk = 36

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    flavorDimensions += "slot"
    productFlavors {
        for (n in 1..3) {
            create("slot$n") {
                dimension = "slot"
                applicationId = "io.github.intersebbtor.mediabooster.slot$n"
                resValue("string", "slot_name", "Media Booster $n")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Readable release file names: MediaBooster-Slot1-1.0.0.apk
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { out ->
            val n = variant.flavorName?.removePrefix("slot")
            (out as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName?.set("MediaBooster-Slot$n-${android.defaultConfig.versionName}.apk")
        }
    }
}
