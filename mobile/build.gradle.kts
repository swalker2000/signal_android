plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.lpkm.signal"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.lpkm.signal"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

val syncDashboardHtml = tasks.register<Copy>("syncDashboardHtml") {
    from(rootProject.file("ktm_lcd.html"))
    from(rootProject.file("round_gauges.html"))
    into("src/main/assets")
}

tasks.named("preBuild").configure {
    dependsOn(syncDashboardHtml)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}