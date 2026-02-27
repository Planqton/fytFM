import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "at.planqton.fytfm"
    compileSdk = 36

    defaultConfig {
        applicationId = "at.planqton.fytfm"
        minSdk = 29
        targetSdk = 36
        versionCode = 10400
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Build-Datum und -Zeit als separate BuildConfig Felder
        val dateFormat = SimpleDateFormat("yyyy-MM-dd")
        val timeFormat = SimpleDateFormat("HH:mm:ss")
        val now = Date()
        buildConfigField("String", "BUILD_DATE", "\"${dateFormat.format(now)}\"")
        buildConfigField("String", "BUILD_TIME", "\"${timeFormat.format(now)}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Media3 (MediaSession für Car Launcher)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer)

    // OkHttp for Spotify API
    implementation(libs.okhttp)

    // Coil for image loading
    implementation(libs.coil)

    // NanoHTTPD for local cover image server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}