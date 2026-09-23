plugins { id("com.android.application") }
android {
    namespace = "com.fscallingline"
    compileSdk = 35
    defaultConfig { applicationId = "com.fscallingline"; minSdk = 29; targetSdk = 35; versionCode = 9; versionName = "0.7.1-dev" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies { implementation("com.journeyapps:zxing-android-embedded:4.3.0"); implementation("androidx.core:core:1.13.1") }



