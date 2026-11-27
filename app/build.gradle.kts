plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.nvdapocket"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.nvdapocket"
        minSdk = 24
        targetSdk = 35
        versionCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        versionName = "3.3"
    }
    signingConfigs {
        create("main") {
            storeFile = file("nvdapocket.jks")
            storePassword = "nvdapocket"
            keyAlias = "nvdapocket"
            keyPassword = "nvdapocket"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("main") }
        getByName("release") {
            signingConfig = signingConfigs.getByName("main")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
