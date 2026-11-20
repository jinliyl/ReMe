plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}
android {
    namespace = "io.agentscope.reme.diary"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "io.agentscope.reme.diary"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { release { isMinifyEnabled = false } }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { jvmToolchain(17) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity)
    implementation(libs.lifecycle)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.room)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work)
    implementation(libs.navigation)
    implementation(libs.coroutines)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.espresso)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
