# 交接说明：把听力题的语音转写改成「手机本地跑」（不依赖任何第三方 API）

> 这份文档写给接手的 AI / 开发者。目标是让 `明日箱`（AsukaBox）的学习通助手在**不填任何 API Key、不联网到转写服务**的前提下，把听力作业的音频转成文字。

---

## 1. 项目与仓库

| 项 | 值 |
|---|---|
| 仓库 | `C:\Users\31629\Documents\deepseek-harness\default-workspace\AsukaBox`（GitHub: `SecondChild07/AsukaBox`） |
| 分支 | `main` |
| 本地已提交未推送的最新 commit | `4464f10`（GitHub 上仍是 `e3806b7`，当时网络不通） |
| 技术栈 | Android 原生 + Kotlin + Jetpack Compose，minSdk 24 / targetSdk 37 |
| 构建 | `$env:JAVA_HOME='D:\Android\jbr'; $env:ANDROID_HOME='D:\Adsdk'; $env:ANDROID_SDK_ROOT='D:\Adsdk'; .\gradlew.bat --offline --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest` |
| 单测 | 当前 131 条，必须保持全绿 |

## 2. 现状（已经做完的部分）

学习通助手的答题链路已经能跑：扫描课程 → 抓作业/考试列表 → 打开作答页 → 抽题 → 调模型 → 回填 → 核验，全程不提交。

**听力题的转写现在是「云端」实现，需要用户自己填一套 STT 服务：**

- 入口：`app/src/main/java/com/AusKa/ToolBox/ui/SettingsScreen.kt` 里「听力转写（可选）」三个输入框 + 四个服务商快捷标签（智谱 / SiliconFlow / OpenAI / Groq）
- 配置存储：`app/src/main/java/com/AusKa/ToolBox/data/ChaoxingStore.kt`
  - `sttBase(context)` / `setSttBase`
  - `sttKey(context)` / `setSttKey`（走 `SecretBox.seal/open`，Keystore 加密）
  - `sttModel(context)` / `setSttModel`
- 调用点：`app/src/main/java/com/AusKa/ToolBox/chaoxing/ChaoxingScreen.kt`
  - `private fun transcribeAudio(raw, base, apiBase, key, sttModel): String` —— 下载音频、切段、逐段 POST、拼字符串；结果按「原始 audio 描述」缓存在 `audioCache`
  - `private fun resolveAudioUrl(objectId, referer): String?` —— 把超星 `audioplay.html` 的 objectid 换成真实 mp3 地址
  - `solvePage()` 里：`val heard = if (hasAudio && sttConfigured) transcribeAudio(...) else ""`，之后把 `heard` 塞进 `QuizPrompt.user(q, course, heard)`
- 音频切段：`app/src/main/java/com/AusKa/ToolBox/chaoxing/AudioChunks.kt`
  - `fun wavChunks(raw: ByteArray, maxSeconds: Int = 26): List<ByteArray>` —— `MediaExtractor` + `MediaCodec` 解码到 PCM，自己写 WAV 头切段；返回空列表表示解码失败
  - **注意：它保留源采样率/声道，没有重采样**

## 3. 目标

把「转写」换成本地推理：

```
作答页 → 取音频 mp3（已有）
      → AudioChunks 解成 PCM（已有）
      → 本地 ASR 模型（新增）
      → 文案 → 现有 QuizPrompt 链路（不动）
```

外部依赖从「一个 HTTP 服务 + Key」变成「一个随 APK 分发的模型」。

## 4. 选型建议（按推荐顺序）

### 方案 A（首选）：sherpa-onnx + 中文 Paraformer / Zipformer 小模型

- 优点：官方提供 Android AAR（`sherpa-onnx`），Kotlin/Java 直接调，不需要自己写 JNI；支持 int8 量化模型；纯离线
- 模型：`sherpa-onnx-paraformer-zh-small`（约 40–80 MB，中文识别够用；英文听力建议再挂一个 multi-lingual zipformer 或用 whisper 方案）
- 输入：16 kHz / 单声道 / float32 PCM —— **需要加一步重采样**（见第 6 节）
- 许可：Apache-2.0（模型和代码都可商用，注意 README 里标注来源）
- 依赖形态（以官方最新 release 为准，下面只是位置示意）：
  ```kotlin
  // app/build.gradle.kts
  dependencies {
      implementation(files("libs/sherpa-onnx-android.aar"))   // 或 Maven 坐标
  }
  ```
- 模型放 `app/src/main/assets/asr/`，首次启动解压到 `context.filesDir/asr/`

### 方案 B：whisper.cpp（JNI）

- 优点：多语种、时间戳、社区成熟
- 代价：要自己编 `.so`（需要 NDK），模型体积大（tiny 75 MB 中文偏弱，base 142 MB 起步），首包加载慢
- 适合：愿意为多语种付包体成本

### 方案 C：Vosk（中文小模型 ~42 MB）

- 优点：体积小、Java API 简单（`org.vosk:vosk-android`）
- 缺点：准确率明显低于前两个，尤其带口音/背景噪声的英语听力

**结论：先做 A，若中文/英文混合听力表现不够，再考虑 B。**

## 5. 需要新增的代码

1. `app/src/main/java/com/AusKa/ToolBox/chaoxing/LocalAsr.kt`
   ```kotlin
   internal object LocalAsr {
       /** 懒加载：首次调用解压模型 + 建 recognizer，之后复用 */
       fun isReady(context: Context): Boolean
       fun prepare(context: Context)                      // 可放到后台线程，配进度回调
       /** 16k mono float PCM 样本，返回识别文本 */
       fun transcribe(context: Context, samples: FloatArray): String
   }
   ```
2. `AudioChunks` 增加重采样：
   ```kotlin
   fun toMono16k(chunk: ByteArray, srcRate: Int, channels: Int): FloatArray
   ```
   （线性插值即可；或调 `android.media.AudioTrack` 不可行，别绕）
3. `transcribeAudio` 里加分支：
   ```kotlin
   if (LocalAsr.isReady(context)) {
       // 本地：chunk → PCM → transcribe → 拼
   } else {
       // 云端：保持现有实现
   }
   ```
4. 设置页加一个开关：「听力转写方式：本地 / 云端」。默认本地（如果没有云端 Key）。

## 6. 关键实现细节 / 坑

- **采样率**：sherpa-onnx 与 whisper.cpp 都要 16 kHz 单声道。`AudioChunks` 现在输出的是源采样率（常见 44.1k / 48k 双声道），务必重采样，否则识别结果是乱码。
- **内存**：一段 3 分钟 44.1k 双声道 PCM ≈ 31 MB，8 段一起解会 OOM。**逐段处理，处理完立刻释放**。
- **耗时**：中端机 int8 paraformer 大约是 0.3–1 倍实时。一份听力作业 8 段共 ~22 分钟音频，可能跑 10 分钟以上 → 必须放后台线程 + 在 `solveNote` 上显示进度（「转写 3/8 段」）。
- **首次加载**：解压模型 + 加载 ~1–3 秒，别在主线程做；建议 App 启动后空闲时 `prepare()`。
- **多说话人 / 噪声**：听力材料常有背景音，识别率会掉；保留「云端兜底」开关。
- **包体**：模型进 assets 会让 APK 从 13 MB 涨到 ~55–95 MB。若用户介意，可做「首次进入听力页再下载模型」（需要自建下载源或 GitHub Release 附件）。
- **不要**把这些模型塞进 `app/src/main/res`（会参与资源压缩），放 `assets` 或用 `androidResources.noCompress`。

## 7. 验收标准

必须用真实基准，不许自评「应该可以」：

1. 打开账号下那份 **《四级听力 Unit 4》**（新时代大学英语（三）），它能抽出 3 个填空块（7 / 8 / 10 空，共 25 空）。
2. 跑完自动回填后，与官方答案逐题比对：

   | 段落 | 官方答案 |
   |---|---|
   | Section A（1–7） | `C A B D A C B` |
   | Section B（8–15） | `C A B D B A D A` |
   | Section C（16–25） | `D A C B C D A D C B` |

3. 正确率 ≥ 90%（25 题里 ≥ 23 题）才算通过。
4. 转写文本人工抽查 1 段：能读出新闻大意，专有名词基本对。
5. 全流程**不点提交**，页面停在 `work/dowork`。
6. 单测保持 131 条全绿，新增 `LocalAsr` 相关至少 3 条（PCM 长度、重采样比例、空输入返回空串）。

## 8. 调试手段（这套已经验证过，直接用）

- 取 WebView 调试口：
  ```
  adb shell pidof com.AusKa.ToolBox
  adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
  curl http://127.0.0.1:9222/json/list
  ```
  然后可以用 CDP `Runtime.evaluate` 在页面里跑 JS，核对 DOM（选项 class 是 `check_answer` / 多选是 `check_answer_dx`，填空是 `ueditor_N` iframe）
- 模拟器：`emulator.exe -avd Medium_Phone_API_37.0 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -port 5558`
- 日志 tag：`CSScan`（扫描/答题）、`CSAi`（接口）

## 9. 交接清单

- [ ] 拿到仓库（GitHub 或直接拷贝本目录，含未推送的 `4464f10`）
- [ ] 能跑通 `:app:assembleDebug`（JDK 用 `D:\Android\jbr`，SDK 用 `D:\Adsdk`；本机是离线 Gradle 缓存，换机器需联网重新同步依赖）
- [ ] 选型定稿（A / B / C），确认模型文件与许可证
- [ ] 实现 `LocalAsr` + 重采样 + 分支接线
- [ ] 用第 7 节基准跑一遍，把「25 题对几题」写进 PR 描述
- [ ] 更新 README 的「学习通助手」一节：说明听力题本地转写、包体变化

## 10. 明确不要做的事

- 不要把转写结果写进日志或上报（音频内容属于用户隐私）
- 不要为了省事把模型塞进 Git 仓库（超过 50 MB 用 Release 附件或 LFS）
- 不要「猜」答案：转写失败就留空并在界面上说明，这条规则现在有，别改掉
- 不要做自动提交 —— 用户明确要求只填、不提交
