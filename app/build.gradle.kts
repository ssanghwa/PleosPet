import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Pleos app key for the server LLM. Kept in local.properties so it never lands in git.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localProp(key: String) = "\"" + (localProps.getProperty(key) ?: "") + "\""

android {
    namespace = "com.example.test"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.test"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PLEOS_CLIENT_ID", localProp("pleos.clientId"))
        buildConfigField("String", "PLEOS_CLIENT_SECRET", localProp("pleos.clientSecret"))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:LLM:2.1.3.2")
    // 3D dog (glTF model + skeletal animation)
    implementation("com.google.android.filament:filament-android:1.75.1")
    implementation("com.google.android.filament:gltfio-android:1.75.1")
    implementation("com.google.android.filament:filament-utils-android:1.75.1")
    // Car API for vehicle speed (provided by the car at runtime). compileSdk 37 ships no android.car.jar.
    compileOnly(files(androidComponents.sdkComponents.sdkDirectory.map {
        it.file("platforms/android-35/optional/android.car.jar")
    }))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}