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
        versionCode = 4
        versionName = "0.4.0-persistent-bridge"
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


val jemRecDir = rootProject.file("third_party/JemRec")
val shellServerDir = File(jemRecDir, "shellserver")
val shellServerJar = File(shellServerDir, "jemrec-capture.jar")

val buildShellServer = tasks.register<Exec>("buildShellServer") {
    description = "Build pinned JemRec shell-side cellular audio daemon"
    workingDir = shellServerDir
    environment("ANDROID_PLATFORM", "35")
    environment("BUILD_TOOLS", "35.0.0")
    environment("JEMREC_JDK_MAJOR", "17")
    commandLine("./build.sh")
    inputs.dir(File(shellServerDir, "src"))
    inputs.file(File(shellServerDir, "build.sh"))
    outputs.file(shellServerJar)
}

val copyShellServer = tasks.register<Copy>("copyShellServer") {
    dependsOn(buildShellServer)
    from(shellServerJar)
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "vorlen-call-capture.jar" }
}

tasks.named("preBuild") { dependsOn(copyShellServer) }
