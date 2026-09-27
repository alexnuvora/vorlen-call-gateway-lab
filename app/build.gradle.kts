plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.vorlen.callgateway.lab"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.vorlen.callgateway.lab"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-audio-lab"
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
}
