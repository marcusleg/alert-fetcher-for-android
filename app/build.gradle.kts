import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Optional release signing: provide keystore.properties in the project root
// (storeFile, storePassword, keyAlias, keyPassword) or the equivalent
// environment variables (used by CI).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

// The version lives in gradle.properties; release tags must be v<appVersion>.
val appVersion = providers.gradleProperty("appVersion").get()
// Two digits per version part, plus two for pre-releases so that they sort before the final release:
// 1.2.3-alpha.4 → 1020304, 1.2.3-beta.4 → 1020334, 1.2.3-rc.4 → 1020364, 1.2.3 → 1020399.
val appVersionCode = run {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?""").matchEntire(appVersion)
        ?: error("appVersion must look like 1.2.3 or 1.2.3-alpha.4 (or -beta.4, -rc.4), not $appVersion")
    val (major, minor, patch, stage, number) = match.destructured
    val preRelease = when (stage) {
        "" -> 99
        else -> listOf("alpha", "beta", "rc").indexOf(stage) * 30 + number.toInt().also { require(it < 30) { "Pre-release number must be below 30" } }
    }
    ((major.toInt() * 100 + minor.toInt()) * 100 + patch.toInt()) * 100 + preRelease
}

android {
    namespace = "de.marcusleg.alertfetcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.marcusleg.alertfetcher"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
        // OIDC redirect URI scheme (<applicationId>:/oauth2redirect), consumed by AppAuth's redirect activity.
        manifestPlaceholders["appAuthRedirectScheme"] = "de.marcusleg.alertfetcher"
    }

    signingConfigs {
        val storeFilePath = signingValue("storeFile", "SIGNING_STORE_FILE")
        if (storeFilePath != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appAuthRedirectScheme"] = "de.marcusleg.alertfetcher.debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.appauth)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
