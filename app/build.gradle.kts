import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

// Release signing reads from keystore.properties (gitignored) so real
// credentials never end up in source control. Missing file -> release
// builds fall back to being unsigned rather than failing the build,
// so `assembleDebug` and tests keep working with zero setup.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasSigningConfig = keystorePropertiesFile.exists()
if (hasSigningConfig) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.threeseeds.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.threeseeds.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.3"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":engine"))

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
}
