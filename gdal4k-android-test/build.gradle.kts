plugins { id("com.android.application") version "8.11.2" }
repositories { google(); mavenCentral() }
android {
    namespace = "dev.gdal4k.contract.app"
    compileSdk = 35
    defaultConfig { applicationId = "dev.gdal4k.contract.app"; minSdk = 28; targetSdk = 35 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs { useLegacyPackaging = false } }
}
dependencies { implementation(project(":gdal4k-contract")) }
