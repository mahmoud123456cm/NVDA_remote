plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- signing: the key and passwords come from environment variables (CI) or ~/.gradle/gradle.properties (your computer) ----
fun secret(env: String, prop: String): String? = System.getenv(env) ?: (project.findProperty(prop) as String?)
val ksFile: File? = secret("KEYSTORE_PATH", "keystorePath")?.let { file(it) }?.takeIf { it.exists() }
val hasKeystore: Boolean = ksFile != null && secret("KEYSTORE_PASSWORD", "keystorePassword") != null &&
    secret("KEY_ALIAS", "keyAlias") != null && secret("KEY_PASSWORD", "keyPassword") != null

gradle.taskGraph.whenReady {
    if (!hasKeystore && System.getenv("CI") == "true" && allTasks.any { it.project == project && it.name.contains("Release", ignoreCase = true) }) {
        throw GradleException("No signing key. Add the KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD secrets to the GitHub repository (see SIGNING.md). A release signed with the wrong key could not update installed copies.")
    }
}

android {
    namespace = "com.example.nvdapocket"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.nvdapocket"
        minSdk = 24
        targetSdk = 35
        versionCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        versionName = "3.6"
        // the offline speech engine ships native code; keep to the phone architectures that matter
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    signingConfigs {
        if (hasKeystore) create("main") {
            storeFile = ksFile
            storePassword = secret("KEYSTORE_PASSWORD", "keystorePassword")
            keyAlias = secret("KEY_ALIAS", "keyAlias")
            keyPassword = secret("KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            // The signing key is never stored in the repository. Without one, local builds fall back to the debug key
            // (fine for trying the app; it cannot update an app signed with your real key). CI refuses to do that, see below.
            signingConfig = if (hasKeystore) signingConfigs.getByName("main") else signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // offline speech recognition for the silent wake-word listening
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
