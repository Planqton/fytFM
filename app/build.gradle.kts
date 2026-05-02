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
        versionCode = 10505
        versionName = "1.5.5"

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
        aidl = true
        viewBinding = true
    }

    buildTypes {
        debug {
            isDebuggable = false
            isJniDebuggable = false
        }
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
    packagingOptions {
        pickFirst("lib/*/libc++_shared.so")
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        // Robolectric layout-inflation tests need the real resource table.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
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

    // Fragment KTX (for viewModels delegate)
    implementation(libs.androidx.fragment.ktx)

    // ViewModel (replaces Hilt for now due to AGP compatibility issues)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // DocumentFile for SAF (Storage Access Framework)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Media3 (MediaSession für Car Launcher)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer)

    // OkHttp for Spotify API
    implementation(libs.okhttp)

    // Coil for image loading
    implementation(libs.coil)

    // LAME MP3 Encoder for DAB recording
    implementation("com.github.naman14:TAndroidLame:1.1") {
        exclude(group = "com.android.support")
    }

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}