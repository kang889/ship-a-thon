plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.studentmemory.copilot"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.studentmemory.copilot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "BACKEND_URL", "\"${providers.gradleProperty("backendUrl").orElse("").get()}\"")
        for ((field, property) in mapOf("FIREBASE_API_KEY" to "firebaseApiKey", "FIREBASE_APP_ID" to "firebaseAppId",
            "FIREBASE_PROJECT_ID" to "firebaseProjectId", "REVENUECAT_PUBLIC_KEY" to "revenuecatPublicKey")) {
            buildConfigField("String", field, "\"${providers.gradleProperty(property).orElse("").get()}\"")
        }
        externalNativeBuild { cmake { cppFlags += "-std=c++20" } }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild { cmake { path = file("../../../CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { buildConfig = true }
    sourceSets["main"].assets.srcDir("../../../data")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.google.firebase:firebase-auth:23.2.0")
    implementation("com.revenuecat.purchases:purchases:8.16.0")
}
