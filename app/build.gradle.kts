plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mkhdov.flux"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mkhdov.flux"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("SUPABASE_URL") ?: ""}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${project.findProperty("SUPABASE_ANON_KEY") ?: ""}\"")
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"${project.findProperty("FIREBASE_APPLICATION_ID") ?: ""}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${project.findProperty("FIREBASE_API_KEY") ?: ""}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${project.findProperty("FIREBASE_PROJECT_ID") ?: ""}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${project.findProperty("FIREBASE_SENDER_ID") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.viewpager2)
    implementation(libs.swiperefreshlayout)
    implementation(libs.firebase.messaging)

    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)

    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.runtime)

    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.lib.recurrence)

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
