plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)

}

android {
    namespace = "com.fifth.pdr_ext"
    compileSdk = 34
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                targets.add("PDR_EXT")
            }
        }
        minSdk = 24
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
    externalNativeBuild {
        cmake {
            // CMakeLists.txt 위치: app/src/main/cpp/PDR_EXT/src/CMakeLists.txt
            path = file("src/main/cpp/PDR_EXT/src/CMakeLists.txt")
            // version = "3.22.1" // 필요 시 명시
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}