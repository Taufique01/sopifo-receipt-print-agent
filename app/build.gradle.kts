plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// FCM is enabled only when a Firebase config is present. Without it the app still
// builds and runs (pending-job recovery keeps working), and Diagnostics reports FCM as unavailable.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
}

val apiBaseUrl: String = (project.findProperty("sopifoApiBaseUrl") as String?) ?: "https://app.sopifo.com"
require(apiBaseUrl.startsWith("https://")) { "sopifoApiBaseUrl must be HTTPS" }

android {
    namespace = "com.sopifo.printagent"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sopifo.printagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", hasFirebaseConfig.toString())
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("SOPIFO_KEYSTORE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("SOPIFO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SOPIFO_KEY_ALIAS")
                keyPassword = System.getenv("SOPIFO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds may talk to a local mock backend over `adb reverse` (http://localhost only).
            buildConfigField("boolean", "ALLOW_LOCALHOST_HTTP", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Project keep rules live in src/main/keepRules/rules.keep
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            buildConfigField("boolean", "ALLOW_LOCALHOST_HTTP", "false")
            if (System.getenv("SOPIFO_KEYSTORE") != null) {
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // Exported Room schemas, for the on-device migration test.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.gms.code.scanner)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
