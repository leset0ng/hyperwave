plugins { id("com.android.application") }

android {
    namespace = "ng.lst.hyperwave"
    compileSdk = 37
    defaultConfig {
        applicationId = "ng.lst.hyperwave"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "ng.lst.hyperwave.ui.PerformanceInstrumentation"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
}

val buildRust by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/build-rust.sh")
    inputs.files(fileTree(rootProject.file("dsp/src")), rootProject.file("dsp/Cargo.toml"), rootProject.file("Cargo.toml"), rootProject.file("Cargo.lock"), rootProject.file("scripts/build-rust.sh"))
    outputs.file("src/main/jniLibs/arm64-v8a/libhyperwave_dsp.so")
}
tasks.named("preBuild") { dependsOn(buildRust) }
