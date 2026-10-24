import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// 签名配置：刻意不写进仓库。
// 本地在 local.properties（已 gitignore）或环境变量里提供下面四个值即可：
//   RELEASE_STORE_FILE=release.jks
//   RELEASE_STORE_PASSWORD=******
//   RELEASE_KEY_ALIAS=******
//   RELEASE_KEY_PASSWORD=******
// 没配置时 release 会退回用 debug 签名 —— 能编译、能装，但不适合分发。
// ---------------------------------------------------------------------------
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String): String? =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(key)?.takeIf { it.isNotBlank() }

val storePath = secret("RELEASE_STORE_FILE")
val storePass = secret("RELEASE_STORE_PASSWORD")
val keyAliasName = secret("RELEASE_KEY_ALIAS")
val keyPass = secret("RELEASE_KEY_PASSWORD") ?: storePass
val keyStoreFile = storePath?.let { file(it) }
val hasReleaseKey = keyStoreFile?.exists() == true && !storePass.isNullOrBlank() && !keyAliasName.isNullOrBlank()

// 从 keystore 里读出证书 SHA-256，注入 BuildConfig，供运行时签名校验使用。
// 换签名不用改代码，Guard 自动跟着变。
val certSha256: String = if (hasReleaseKey) runCatching {
    val ks = runCatching { KeyStore.getInstance("JKS") }.getOrElse { KeyStore.getInstance("PKCS12") }
    keyStoreFile!!.inputStream().use { ks.load(it, storePass!!.toCharArray()) }
    val cert = ks.getCertificate(keyAliasName!!) as java.security.cert.X509Certificate
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}.getOrDefault("") else ""

android {
    namespace = "com.Auska.ToolBox"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.Auska.ToolBox"
        minSdk = 24
        targetSdk = 37
        versionCode = 105
        versionName = "1.0"

        // 空字符串 = 未配置签名，运行时跳过签名校验
        buildConfigField("String", "CERT_SHA256", "\"$certSha256\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = keyStoreFile
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // debug 与 release 用同一个签名，否则 Guard 会把自己拦下来
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
            optimization {
                enable = true
                packageScope = setOf(
                    "androidx.**", "kotlin.**", "kotlinx.**",
                    "com.tencent.**", "com.Auska.ToolBox.**",
                )
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 别把依赖清单和 Kotlin 元数据打进包里：
    // META-INF/*.version 会明示每个第三方库的版本号，等于给攻击者一份 CVE 索引
    packaging {
        resources {
            excludes += "META-INF/*.version"
            excludes += "META-INF/*.kotlin_module"
            excludes += "kotlin/**"
            excludes += "META-INF/androidx*"
        }
    }

    lint {
        // 虚拟定位是这个 App 的核心功能之一，不是只在 debug 里用的测试钩子；
        // 而且这条权限只是「声明」，好让系统把它列进「选择模拟位置信息应用」
        disable += "MockLocation"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
