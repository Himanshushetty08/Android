plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ultraviolette.s3service"  // Use your actual package namespace here
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ultraviolette.s3service"  // Change to your app ID
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "s3service-${buildType.name}.apk"
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true  // ✅ Enable AIDL support
    }

    // ✅ FIXED: Correct Kotlin DSL syntax for sourceSets
    sourceSets {
        getByName("main") {
            aidl {
                srcDirs("src/main/aidl")
            }
        }
    }
}

dependencies {
    // Android UI
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime:2.10.5")
    implementation("com.amazonaws:aws-android-sdk-s3:2.22.+")
    implementation("com.amazonaws:aws-android-sdk-core:2.22.+")
    implementation("com.jakewharton.timber:timber:5.0.1")
    // Room
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)

    // Lifecycle
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.viewmodel)

    // OkHttp for networking (file upload chunks)
    implementation(libs.okhttp)

    // Timber for logging
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
