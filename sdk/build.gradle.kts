plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "sx.proxies.peer"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // R8 shrinking on. The public API + dependencies are preserved by
            // proguard-rules.pro (-keep sx.proxies.peer.**); consumers further
            // shrink via the bundled consumer-rules.pro.
            isMinifyEnabled = true
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

    testOptions {
        unitTests.isReturnDefaultValues = true // android.util.Log -> no-op in JVM tests
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Keystore-backed encrypted SharedPreferences for credentials (v1.1.4+).
    // NOTE: the 1.1.0 line is alpha-only; there is no stable 1.1.x release.
    // The last stable (1.0.0) predates the MasterKey.Builder API this SDK
    // uses, so downgrading would be a source-breaking change. Pinned to the
    // newest alpha and reviewed on each AndroidX security-crypto release —
    // move to the stable 1.1.0/2.x as soon as one ships.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp for HTTP client and WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // NanoHTTPD for local proxy server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// JitPack publishing configuration
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.bolivian-peru"
                artifactId = "android-peer-sdk"
                version = "1.3.1"

                pom {
                    name.set("Proxies.sx Peer SDK")
                    description.set("Android SDK for Proxies.sx peer proxy network - enables bandwidth sharing in Android apps")
                    url.set("https://github.com/bolivian-peru/android-peer-sdk")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
                            id.set("proxies-sx")
                            name.set("Proxies.sx Team")
                            email.set("dev@proxies.sx")
                        }
                    }

                    scm {
                        connection.set("scm:git:github.com/bolivian-peru/android-peer-sdk.git")
                        developerConnection.set("scm:git:ssh://github.com/bolivian-peru/android-peer-sdk.git")
                        url.set("https://github.com/bolivian-peru/android-peer-sdk/tree/main")
                    }
                }
            }
        }
    }
}
