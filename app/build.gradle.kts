plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "bd.newsnest.live"
    compileSdk = 34
    defaultConfig {
        applicationId = "bd.newsnest.live"
        minSdk = 24; targetSdk = 34
        versionCode = 1; versionName = "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("com.github.pedroSG94.RootEncoder:library:2.5.5") }
