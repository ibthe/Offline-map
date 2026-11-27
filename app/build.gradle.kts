plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localMapAssets = file("src/main/assets").listFiles()
    ?.filter { it.extension in setOf("mbtiles", "db", "bin") }
    ?.sortedBy { it.name }
    ?.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
    ?: "no-map-assets"
val mapDataVersion = (project.findProperty("mapDataVersion") as String?)
    ?: "local-${localMapAssets.hashCode()}"

android {
    namespace = "app.georgiamap"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.georgiamap.offline"
        minSdk = 24
        targetSdk = 34
        // CI uses a monotonic versionCode; map asset copying is controlled by MAP_DATA_VERSION
        versionCode = (project.findProperty("vcode") as String?)?.toIntOrNull() ?: 1
        versionName = "3.4.0"
        buildConfigField("String", "MAP_DATA_VERSION", "\"$mapDataVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // keep the big tile database uncompressed inside the APK (fast copy on first launch)
    androidResources {
        noCompress += listOf("mbtiles", "db", "bin")
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    (project.findProperty("routerTestGraph") as String?)?.let {
        systemProperty("router.test.graph", it)
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
