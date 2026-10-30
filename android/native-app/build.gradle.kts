plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.exomind.androbody.nativeapp"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.exomind.androbody"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.6.9-androbody-migration-candidate"
        buildConfigField("String", "SOURCE_REVISION", "\"${providers.gradleProperty("sourceRevision").orElse("unrecorded").get().replace(Regex("[^a-zA-Z0-9._-]"), "_")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "applicationIdentity"
    productFlavors {
        create("androbody") {
            dimension = "applicationIdentity"
        }
        create("legacyMigration") {
            dimension = "applicationIdentity"
            applicationId = "org.exomind.andromorph"
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = false
            ndk { abiFilters += "arm64-v8a" }
        }
        create("verification") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".verification"
            matchingFallbacks += "debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

dependencies { add("verificationImplementation", "androidx.compose.ui:ui-test-manifest") }
