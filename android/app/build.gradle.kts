plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val releaseStoreFile = providers.gradleProperty("MASS_MATE_RELEASE_STORE_FILE")
val releaseStorePassword = providers.gradleProperty("MASS_MATE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.gradleProperty("MASS_MATE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.gradleProperty("MASS_MATE_RELEASE_KEY_PASSWORD")

fun releaseSigningMissingProperties(): List<String> {
    return listOfNotNull(
        "MASS_MATE_RELEASE_STORE_FILE".takeIf { releaseStoreFile.orNull.isNullOrBlank() },
        "MASS_MATE_RELEASE_STORE_PASSWORD".takeIf { releaseStorePassword.orNull.isNullOrBlank() },
        "MASS_MATE_RELEASE_KEY_ALIAS".takeIf { releaseKeyAlias.orNull.isNullOrBlank() },
        "MASS_MATE_RELEASE_KEY_PASSWORD".takeIf { releaseKeyPassword.orNull.isNullOrBlank() },
    )
}

fun releaseSigningConfigured(): Boolean = releaseSigningMissingProperties().isEmpty()

android {
    namespace = "dev.ztripez.massmate"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "dev.ztripez.massmate"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured()) {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

gradle.taskGraph.whenReady {
    val isReleaseBuild = allTasks.any { task -> task.name.contains("Release") }
    if (!isReleaseBuild) return@whenReady

    val missingProperties = releaseSigningMissingProperties()
    if (missingProperties.isNotEmpty()) {
        throw GradleException(
            "Release signing is not configured. Use `flutter build apk --debug` for prototype APKs. " +
                "Missing signing properties: ${missingProperties.joinToString()}.",
        )
    }

    val releaseKeystore = file(releaseStoreFile.get())
    if (!releaseKeystore.isFile) {
        throw GradleException(
            "Release signing keystore does not exist: ${releaseKeystore.absolutePath}",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
