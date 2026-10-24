plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val releaseSigningKeys = listOf(
    "REELSHARE_UPLOAD_STORE_FILE",
    "REELSHARE_UPLOAD_STORE_PASSWORD",
    "REELSHARE_UPLOAD_KEY_ALIAS",
    "REELSHARE_UPLOAD_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningKeys.associateWith { key ->
    providers.environmentVariable(key).orNull?.takeIf { it.isNotBlank() }
}
val releaseSigningRequested = releaseSigningValues.values.any { it != null }
require(!releaseSigningRequested || releaseSigningValues.values.all { it != null }) {
    "Set all four REELSHARE_UPLOAD_* environment variables to sign a release."
}
if (releaseSigningRequested) {
    require(file(releaseSigningValues.getValue("REELSHARE_UPLOAD_STORE_FILE")!!).isFile) {
        "REELSHARE_UPLOAD_STORE_FILE must point to an existing keystore file."
    }
}

android {
    namespace = "com.reelshare.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.reelshare.app"
        minSdk = 26
        targetSdk = 37
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseSigningRequested) create("upload") {
            storeFile = file(releaseSigningValues.getValue("REELSHARE_UPLOAD_STORE_FILE")!!)
            storePassword = releaseSigningValues.getValue("REELSHARE_UPLOAD_STORE_PASSWORD")!!
            keyAlias = releaseSigningValues.getValue("REELSHARE_UPLOAD_KEY_ALIAS")!!
            keyPassword = releaseSigningValues.getValue("REELSHARE_UPLOAD_KEY_PASSWORD")!!
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningRequested) signingConfig = signingConfigs.getByName("upload")
        }
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:creator"))

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.timber)
    implementation(libs.coil.compose)
    implementation(libs.work.runtime)

    testImplementation(libs.junit)
}


kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
