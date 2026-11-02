plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kairosvector.flowchat"
    compileSdk = 34
    // Fork build-only: AGP 8.1.2 defaults to build-tools 33.0.1, which
    // is not in this SDK; 34.0.0 is installed and changes no app behavior.
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.kairosvector.flowchat"
        minSdk = 24
        targetSdk = 34
        // Fork: bumped so this build is distinguishable from upstream v0.4.
        versionCode = 26
        versionName = "0.4-fork6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Fork6: sign release builds with Gradle's built-in debug
            // keystore (~/.android/debug.keystore, alias androiddebugkey).
            // The keystore that signed fork1-5 is gone, so fork6 is signed
            // by this machine's debug key; no secrets live in the repo.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    lint {
        disable += "UseAppTint"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("io.ktor:ktor-client-okhttp:2.3.6")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.emoji2:emoji2-emojipicker:1.5.0")
    testImplementation("junit:junit:4.13.2")
    // The Android stub of org.json cannot run in plain unit tests.
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("com.github.liuyueyi:quick-transfer-core:0.2.13")
}