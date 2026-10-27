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

// ---------------------------------------------------------------------------
// 把语音模型打进 APK（可选）。
//   瘦包（默认）：53 MB，模型首次使用时在线下载 / 或从手机里导入。
//   胖包：-PWITH_ASR=true，把 asr-assets/asr/sensevoice-model.zip（约 229 MB）放进 assets，
//         首次用到时自己解压到 filesDir，全程不联网。包体约 280 MB。
// asr-assets 已被 .gitignore 排除（模型不进仓库）。
// ---------------------------------------------------------------------------
val asrAssetsDir = rootProject.file("asr-assets")
val withAsr: Boolean = run {
    val fromGradle = providers.gradleProperty("WITH_ASR").orNull
    val fromLocal = secret("WITH_ASR")
    (fromGradle ?: fromLocal)?.trim()?.equals("true", ignoreCase = true) == true &&
        File(asrAssetsDir, "asr/sensevoice-model.zip").exists()
}

// 从 keystore 里读出证书 SHA-256，注入 BuildConfig，供运行时签名校验使用。
// 换签名不用改代码，Guard 自动跟着变。
val certSha256: String = if (hasReleaseKey) runCatching {
    val ks = runCatching { KeyStore.getInstance("JKS") }.getOrElse { KeyStore.getInstance("PKCS12") }
    keyStoreFile!!.inputStream().use { ks.load(it, storePass!!.toCharArray()) }
    val cert = ks.getCertificate(keyAliasName!!) as java.security.cert.X509Certificate
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}.getOrDefault("") else ""

android {
    namespace = "com.AusKa.ToolBox"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.AusKa.ToolBox"
        minSdk = 24
        targetSdk = 37
        versionCode = 210
        versionName = "2.1"

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
            // 本地语音转写带着 onnxruntime 的 so（arm64 一个架构就 ~30 MB），
            // 四个架构全打进去包体直接翻倍，正式包只留真机用得上的两个；
            // 模拟器（x86_64）调试请用 debug 包，debug 不裁剪。
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            // release 用正式 keystore；没配（本地没 release.jks）时退回 debug，能跑起来
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
            optimization {
                enable = true
                packageScope = setOf(
                    "androidx.**", "kotlin.**", "kotlinx.**",
                    "com.AusKa.ToolBox.**",
                )
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // debug 用自己的 debug 签名：开发机不需要正式 keystore，
            // 也因为 Guard 对 debug 构建跳过签名校验（见 Guard.signatureOk）
            signingConfig = signingConfigs.getByName("debug")
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

    // 胖包：把模型 zip 并进 assets（目录里必须真的有 asr/sensevoice-model.zip，否则当没开）
    sourceSets {
        getByName("main") {
            if (withAsr) assets.srcDir(asrAssetsDir)
        }
    }

    // 模型 zip 不要再压缩一遍：onnx 权重已经没什么可压的，压了反而装得慢、解压要两遍
    androidResources {
        noCompress += "zip"
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
    // 单测里用真实 org.json 实现（android.jar 里的是 stub，调用会抛 "not mocked"）
    testImplementation("org.json:json:20240303")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 手机本地语音转写（听力题）：sherpa-onnx 的 Android 封装，Kotlin 直接调 com.k2fsa.sherpa.onnx.*
    // 官方只发 GitHub Release 的 AAR（本机 GitHub 不通），这里用 Maven Central 上的同源重打包，
    // 代码包名/接口与官方一致，含 arm64-v8a / armeabi-v7a / x86 / x86_64 四个 .so（已按 16 KB 页对齐）
    implementation("com.bihe0832.android:lib-sherpa-onnx:8.6.10")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
