plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.vorlen.callgateway.lab"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.vorlen.callgateway.lab"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-lab"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
