object Versions {
    const val COMPILE_SDK = 34
    const val MIN_SDK = 26
    const val TARGET_SDK = 34

    const val CORE_KTX = "1.12.0"
    const val LIFECYCLE = "2.7.0"
    const val ACTIVITY_COMPOSE = "1.8.2"
    const val COMPOSE_BOM = "2024.02.00"
    const val NAVIGATION_COMPOSE = "2.7.7"
    const val HILT = "2.50"
    const val HILT_NAVIGATION = "1.2.0"
    const val RETROFIT = "2.9.0"
    const val OKHTTP = "4.12.0"
    const val KOTLINX_SERIALIZATION = "1.6.2"
    const val RETROFIT_KOTLINX_SERIALIZATION = "1.0.0"
    const val COIL = "2.5.0"
    const val DATASTORE = "1.0.0"
    const val SECURITY_CRYPTO = "1.1.0-alpha06"
    const val SPLASHSCREEN = "1.0.1"
}

object Deps {
    // AndroidX Core
    const val coreKtx = "androidx.core:core-ktx:${Versions.CORE_KTX}"
    const val lifecycleRuntimeKtx = "androidx.lifecycle:lifecycle-runtime-ktx:${Versions.LIFECYCLE}"
    const val lifecycleViewModelKtx = "androidx.lifecycle:lifecycle-viewmodel-ktx:${Versions.LIFECYCLE}"
    const val lifecycleViewModelCompose = "androidx.lifecycle:lifecycle-viewmodel-compose:${Versions.LIFECYCLE}"
    const val activityCompose = "androidx.activity:activity-compose:${Versions.ACTIVITY_COMPOSE}"
    const val splashscreen = "androidx.core:core-splashscreen:${Versions.SPLASHSCREEN}"

    // Compose
    const val composeBom = "androidx.compose:compose-bom:${Versions.COMPOSE_BOM}"
    const val composeUi = "androidx.compose.ui:ui"
    const val composeUiGraphics = "androidx.compose.ui:ui-graphics"
    const val composeUiTooling = "androidx.compose.ui:ui-tooling"
    const val composeUiToolingPreview = "androidx.compose.ui:ui-tooling-preview"
    const val composeMaterial3 = "androidx.compose.material3:material3"
    const val composeMaterialIcons = "androidx.compose.material:material-icons-extended"
    const val navigationCompose = "androidx.navigation:navigation-compose:${Versions.NAVIGATION_COMPOSE}"

    // Hilt
    const val hiltAndroid = "com.google.dagger:hilt-android:${Versions.HILT}"
    const val hiltCompiler = "com.google.dagger:hilt-compiler:${Versions.HILT}"
    const val hiltNavigationCompose = "androidx.hilt:hilt-navigation-compose:${Versions.HILT_NAVIGATION}"

    // Network
    const val retrofit = "com.squareup.retrofit2:retrofit:${Versions.RETROFIT}"
    const val okhttp = "com.squareup.okhttp3:okhttp:${Versions.OKHTTP}"
    const val okhttpLogging = "com.squareup.okhttp3:logging-interceptor:${Versions.OKHTTP}"
    const val kotlinxSerialization = "org.jetbrains.kotlinx:kotlinx-serialization-json:${Versions.KOTLINX_SERIALIZATION}"
    const val retrofitKotlinxSerialization = "com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:${Versions.RETROFIT_KOTLINX_SERIALIZATION}"

    // Image
    const val coilCompose = "io.coil-kt:coil-compose:${Versions.COIL}"

    // DataStore
    const val datastore = "androidx.datastore:datastore-preferences:${Versions.DATASTORE}"

    // Security
    const val securityCrypto = "androidx.security:security-crypto:${Versions.SECURITY_CRYPTO}"
}
