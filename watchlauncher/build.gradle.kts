plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.watchlauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.watchlauncher"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
