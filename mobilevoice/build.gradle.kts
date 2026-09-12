import java.security.MessageDigest

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.huigu.phone10.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.huigu.phone10.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.3.6"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.github.gkonovalov.android-vad:silero:2.0.10")
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.2.1")
}

// O loads this small Android-native helper through its supported Java.loadJar API.
// Generate it from the same source as the JVM tests, never hand-maintain a binary.
val workerAssets = layout.buildDirectory.dir("generated/operitWorkerAssets")
val compileOperitWorker by tasks.registering(JavaCompile::class) {
    source(fileTree("src/main/java") { include("**/OperitRequestWorker.java", "**/OperitScriptDispatch.java") })
    classpath = files(android.bootClasspath)
    destinationDirectory.set(layout.buildDirectory.dir("operitWorkerClasses"))
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}
val dexOperitWorker by tasks.registering(JavaExec::class) {
    dependsOn(compileOperitWorker)
    classpath = files("${android.sdkDirectory}/build-tools/${android.buildToolsVersion}/lib/d8.jar")
    mainClass.set("com.android.tools.r8.D8")
    inputs.files(compileOperitWorker.flatMap { it.destinationDirectory })
    outputs.dir(workerAssets)
    doFirst {
        val output = workerAssets.get().asFile.also { it.mkdirs() }
        args = listOf("--min-api", "26", "--lib", android.bootClasspath.first().absolutePath,
            "--output", output.resolve("phone10-operit-worker.jar").absolutePath) +
            compileOperitWorker.get().destinationDirectory.get().asFile.walkTopDown()
                .filter { it.extension == "class" }.map { it.absolutePath }.toList()
    }
    doLast {
        val output = workerAssets.get().asFile
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(output.resolve("phone10-operit-worker.jar").readBytes())
            .joinToString("") { "%02x".format(it) }
        output.resolve("phone10-operit-worker.sha256").writeText(hash)
    }
}
android.sourceSets.getByName("main").assets.srcDir(workerAssets)
tasks.named("preBuild").configure { dependsOn(dexOperitWorker) }

// About text is generated from the same documents shipped with the source.
val aboutAssets = layout.buildDirectory.dir("generated/erpanAboutAssets")
val generateErpanAbout by tasks.registering {
    val guide = file("使用说明.md")
    val notices = file("THIRD_PARTY_NOTICES.md")
    val license = rootProject.file("LICENSE")
    inputs.files(guide, notices, license)
    outputs.dir(aboutAssets)
    doLast {
        val output = aboutAssets.get().asFile.also { it.mkdirs() }
        output.resolve("erpan-guide.md").writeText(guide.readText())
        output.resolve("erpan-notices.txt").writeText(notices.readText() + "\n\n" + license.readText())
    }
}
android.sourceSets.getByName("main").assets.srcDir(aboutAssets)
tasks.named("preBuild").configure { dependsOn(generateErpanAbout) }
