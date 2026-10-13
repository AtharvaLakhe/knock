plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

group = "com.github.AtharvaLakhe"
version = "1.0.0"

android {
    namespace = "io.github.atharvalakhe.knock"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core:1.16.0")
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.AtharvaLakhe"
            artifactId = "knock"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Knock")
                description.set("Gesture triggers for Android: knock patterns through a pocket, shakes, power-button presses and wrist twists, as Kotlin Flows.")
                url.set("https://github.com/AtharvaLakhe/knock")
                licenses { license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
            }
        }
    }
}
