plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.supernova.anchor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.supernova.anchor"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Disable backup for security-sensitive data
        manifestPlaceholders["allowBackup"] = false
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("ANC_KEYSTORE_FILE") ?: "release.keystore")
            storePassword = System.getenv("ANC_KEYSTORE_PASSWORD") ?: "your_keystore_password"
            keyAlias = System.getenv("ANC_KEY_ALIAS") ?: "your_key_alias"
            keyPassword = System.getenv("ANC_KEY_PASSWORD") ?: "your_key_password"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }
        
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Sign the release APK
            // signingConfig = signingConfigs.getByName("release")
            // Uncomment above line for signing to create signed APKs
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    
    lint {
        // Don't abort build on lint errors during development
        abortOnError = false
        // Report warnings but don't fail the build
        warningsAsErrors = false
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.cardview:cardview:1.0.0")
    implementation(libs.material)
    implementation(libs.playServicesLocation)
    implementation(libs.androidx.compose.material.icons.extended)
    
    // Security - Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    
    // Debug
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}