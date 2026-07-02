plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.ecommerce.core"
    compileSdk = Versions.COMPILE_SDK

    defaultConfig {
        minSdk = Versions.MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8088/api/\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 生产环境请通过 gradle.properties 的 baseUrl 属性覆盖
            val releaseUrl = project.findProperty("baseUrl") as String? ?: "https://api.example.com/api/"
            buildConfigField("String", "BASE_URL", "\"$releaseUrl\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    api(Deps.coreKtx)
    api(Deps.lifecycleRuntimeKtx)
    api(Deps.lifecycleViewModelKtx)
    api(Deps.lifecycleViewModelCompose)
    api(Deps.activityCompose)
    api(Deps.splashscreen)

    api(platform(Deps.composeBom))
    api(Deps.composeUi)
    api(Deps.composeUiGraphics)
    api(Deps.composeUiToolingPreview)
    api(Deps.composeMaterial3)
    api(Deps.composeMaterialIcons)
    api(Deps.navigationCompose)
    debugApi(Deps.composeUiTooling)

    api(Deps.hiltAndroid)
    kapt(Deps.hiltCompiler)
    api(Deps.hiltNavigationCompose)

    api(Deps.retrofit)
    api(Deps.okhttp)
    api(Deps.okhttpLogging)
    api(Deps.kotlinxSerialization)
    api(Deps.retrofitKotlinxSerialization)

    api(Deps.coilCompose)
    api(Deps.datastore)
    api(Deps.securityCrypto)
}

kapt {
    correctErrorTypes = true
}
