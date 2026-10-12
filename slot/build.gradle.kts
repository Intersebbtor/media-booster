plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Empty identity shells. Each slot only grants Player Pin the right to post
// notifications in its name, so the system shows separate player cards.
android {
    namespace = "io.github.intersebbtor.playerpin.slot"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    flavorDimensions += "slot"
    productFlavors {
        for (n in 1..4) {
            create("slot$n") {
                dimension = "slot"
                applicationId = "io.github.intersebbtor.playerpin.slot$n"
                resValue("string", "slot_name", "Player Pin $n")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
