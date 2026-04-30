import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

val hostJniDir = layout.buildDirectory.dir("host-jni")
val cppSourceDir = layout.projectDirectory.dir("src/main/cpp")

val cmakeConfigureHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Configure the CMake host build of jni_cb_term"
    inputs.dir(cppSourceDir)
    outputs.dir(hostJniDir)
    commandLine(
        "cmake",
        "-S",
        cppSourceDir.asFile.absolutePath,
        "-B",
        hostJniDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Debug",
    )
}

val cmakeBuildHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Build libjni_cb_term for the host JVM"
    dependsOn(cmakeConfigureHost)
    inputs.dir(hostJniDir)
    commandLine(
        "cmake",
        "--build",
        hostJniDir.get().asFile.absolutePath,
        "--target",
        "jni_cb_term",
    )
    outputs.dir(hostJniDir)
}

android {
    namespace = "org.connectbot.terminal"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {}
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            debugSymbolLevel = "full"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }

    packaging {
        jniLibs {
            keepDebugSymbols.add("**/*.so")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { testTask ->
                testTask.dependsOn(cmakeBuildHost)
                testTask.jvmArgs("-Djava.library.path=${hostJniDir.get().asFile.absolutePath}")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.ui)

    // Jetpack Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(composeBom)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.mockk)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

