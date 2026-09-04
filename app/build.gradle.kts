plugins {
    id("com.android.application")
}

android {
    namespace = "com.yagay.dualsignal"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yagay.dualsignal"
        minSdk = 31
        targetSdk = 37
        versionCode = 21
        versionName = "1.7.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
