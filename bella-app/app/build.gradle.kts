import java.time.LocalDate
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Підпис release. Секрети беруться з bella-app/keystore.properties (не в git) або зі змінних середовища.
// Якщо нічого не задано, release збирається непідписаним (app-release-unsigned.apk).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signing(prop: String, env: String): String? = keystoreProps.getProperty(prop) ?: System.getenv(env)

// Версія: назву (1.1.0) піднімаємо вручну разом зі змінами. Номер збірки = кількість комітів у git:
// росте з кожною зміною і однаковий локально й у CI, тож новіший APK завжди встановлюється поверх старішого.
// Можна перевизначити змінною BELLA_BUILD_NUMBER.
val appVersionName = "1.21.2"
val buildNumber: Int = System.getenv("BELLA_BUILD_NUMBER")?.toIntOrNull()
    ?: try { providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt() } catch (e: Exception) { 1 }

android {
    namespace = "ua.bella.finance"
    compileSdk = 34
    defaultConfig {
        applicationId = "ua.bella.finance"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = appVersionName
        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now()}\"")
    }
    signingConfigs {
        val storePath = signing("storeFile", "BELLA_KEYSTORE")
        val storePass = signing("storePassword", "BELLA_STORE_PASSWORD")
        val alias = signing("keyAlias", "BELLA_KEY_ALIAS")
        if (storePath != null && storePass != null && alias != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = signing("keyPassword", "BELLA_KEY_PASSWORD") ?: storePass
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
