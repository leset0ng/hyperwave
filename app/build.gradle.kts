plugins { id("com.android.application") }

android {
    namespace = "io.github.leset0ng.hyperwave"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.leset0ng.hyperwave"
        minSdk = 33
        targetSdk = 37
        versionCode = providers.gradleProperty("hyperwaveVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("hyperwaveVersionName").orElse("0.1.0").get()
        testInstrumentationRunner = "io.github.leset0ng.hyperwave.ui.PerformanceInstrumentation"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        val keystorePath = providers.environmentVariable("HYPERWAVE_KEYSTORE_PATH").orNull
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("HYPERWAVE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("HYPERWAVE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("HYPERWAVE_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
}

val buildRust by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/build-rust.sh")
    inputs.files(fileTree(rootProject.file("dsp/src")), rootProject.file("dsp/Cargo.toml"), rootProject.file("Cargo.toml"), rootProject.file("Cargo.lock"), rootProject.file("scripts/build-rust.sh"))
    outputs.file("src/main/jniLibs/arm64-v8a/libhyperwave_dsp.so")
}
tasks.named("preBuild") { dependsOn(buildRust) }
