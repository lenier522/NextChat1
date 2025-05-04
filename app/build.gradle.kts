plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cu.lenier.nextchat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lenier.amarefree"
        minSdk = 24
        targetSdk = 36
        versionCode = 52
        versionName = "1.2.3"

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

    packaging {
        resources.excludes.add("META-INF/LICENSE.md")
        resources.excludes.add("META-INF/NOTICE.md")
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    implementation(libs.recyclerview)
    implementation(libs.room.runtime)
    implementation(libs.lifecycle.extensions)
    implementation(libs.work.runtime)
    implementation(libs.core)
    implementation(libs.legacy.support.core.utils)
    implementation(libs.gson)
    implementation (libs.update.checker)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.glide)
    implementation(libs.lottie)
    annotationProcessor(libs.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation(libs.audiowave.progressbar)
}