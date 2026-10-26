<img src="docs/banner.png" alt="明日箱">

# 明日箱 / AsukaBox

校园日常里那几件小事，收进一个盒子：NFC 签到、课表与桌面小组件、虚拟定位、学习通作业助手。

纯 Compose 手写，Liquid Glass 外观，没有第三方 UI 库。

<img src="docs/shots.png" width="100%">

**[下载 APK](https://github.com/SecondChild07/AsukaBox/releases/latest)** · 2.0 · Android 7.0+

---

## NFC 签到

贴一下任意 NFC 标签，读出链接，拉起支付宝签到页。

没有标签也行：长按「签到」手动输一次，之后点按钮就签。链接用 Keystore 加密存在本地。

## 课表

从正方教务（zfsoft / jwglxt）拉本学期课表：学期切换、周次与日期、单双周、当前周自动定位。

打开 App 自动同步一次，失败保留旧数据。

桌面小组件默认 3×2，按卡片高度自动切「两行版 / 单行版」，再挤就让掉标题和日期行，尽量把当天所有课塞进去。往上拖高一点，整天的课就都露出来了。

## 虚拟定位

在开发者选项里把明日箱设成「模拟位置信息应用」，之后能把定位钉在任意坐标。

地图选点、搜索城市、粘贴坐标都行。搜具体地点（小区、地铁站）要在设置里填自己的高德 Key —— [lbs.amap.com](https://lbs.amap.com) 免费申请，服务平台选「Web 服务」。

## 学习通助手

登录一次，之后打开就是清单：**未完成 N / 已完成 N**，每条写明课程、考试还是作业。

点一条就进作答页，检测到是答题页会自动开始填（也可以手动点「开始答题」）。填完不提交 —— 提交按钮永远留给你自己点。

- AI 答题只要一个 Key：设置 → AI Key 填进去就能用，默认走 OpenAI 官方的 `gpt-4o-mini`。想换服务商（DeepSeek、Gemini、智谱这些）点对话框里的「高级」，改接口地址和模型名。
- 只有**一套模型**，文字题图片题共用。听力作业的题面常是图片，填了纯文本模型（deepseek-chat 这类）会被跳过并提示「当前模型是纯文本模型」，不会乱填。
- 音频走 whisper 兼容接口转写，转写文本会和题干一起交给模型。
- 扫描走原生 HTTP：读课程页里的签名参数，并发抓作业/考试列表。20 门课大约 6 秒。
- 会话 Cookie 用 Keystore 加密存在本地，过期了重新登录一次。

---

## 构建

需要 JDK 17+、Android SDK 36。

```bash
./gradlew :app:assembleDebug
```

Release 签名写在 `local.properties`（不进仓库），没有就用 debug 签名：

```properties
RELEASE_STORE_FILE=release.jks
RELEASE_STORE_PASSWORD=***
RELEASE_KEY_ALIAS=***
RELEASE_KEY_PASSWORD=***
```

## 发版

改 `versionCode` / `versionName`，提交，打 tag：

```bash
git tag v2.0 && git push origin main --tags
```

CI 构建 release、签名、挂到 Release。

## 数据

| 内容 | 存法 |
|---|---|
| 教务账号密码、课表、签到链接、学习通 Cookie、AI Key、高德 Key | Android Keystore AES-256-GCM |
| 学期、同步时间、作业清单缓存 | 明文 SharedPreferences |

没有自家服务器，也没有任何上报。除了登录教务、拉地图瓦片、访问学习通和你填的 AI 服务商，App 不联网。

## 权限

- `NFC` — 读标签
- `ACCESS_MOCK_LOCATION` — 进「模拟位置信息应用」列表
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — Android 10~13 前台服务的硬性要求；App 不读你的位置
- `POST_NOTIFICATIONS` — 虚拟定位的前台服务通知

## 边界

读自己的标签、导自己的课表、改自己的定位、做自己的作业 —— 这些是工具。

替别人签到是欺诈，用虚拟定位打考勤是作弊，登录别人的教务是入侵，替别人交作业是代写。这些不在项目范围内。

作业助手只填空、不提交，是给你自己核对用的；考试场景请遵守学校规定。

## License

MIT

## 致谢

地图用 [Leaflet](https://leafletjs.com) + 高德瓦片；玻璃质感的思路参考 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)。
