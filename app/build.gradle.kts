import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Local-only credentials; deliberately independent of local.properties (Android SDK).
val mapsSecrets = Properties()
val mapsSecretsFile = rootProject.layout.projectDirectory.file("secrets.properties")
if (mapsSecretsFile.asFile.exists()) {
    mapsSecretsFile.asFile.inputStream().use(mapsSecrets::load)
}
val mapsApiKey = mapsSecrets.getProperty("MAPS_API_KEY", "").trim()
val mapsKeyConfigured = mapsApiKey.isNotEmpty() && mapsApiKey != "YOUR_MAPS_API_KEY"

android {
    namespace = "com.trailo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.trailo.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["MAPS_API_KEY"] = if (mapsKeyConfigured) mapsApiKey else "MISSING_MAPS_API_KEY"
        buildConfigField("boolean", "MAPS_KEY_CONFIGURED", mapsKeyConfigured.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.maps.compose)
    implementation(libs.play.location)
    // Maps brings an old Fragment transitively; Activity Result requires a current version.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
