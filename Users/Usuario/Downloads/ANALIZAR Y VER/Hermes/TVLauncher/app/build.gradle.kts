plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tvlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvlauncher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.leanback:leanback:1.2.0-alpha03")
    implementation("androidx.leanback:leanback-preference:1.2.0-alpha03")
    implementation("com.google.android.material:material:1.11.0")
    
    // HTML parsing for Canchallena scraping
    implementation("org.jsoup:jsoup:1.17.2")
    
    // Background sync
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Gson for custom apps persistence
    implementation("com.google.code.gson:gson:2.10.1")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
}