<img src="docs/icon.png" width="96" alt="明日箱">

# 明日箱 / AsukaBox

Android 小工具箱，四个功能：NFC 签到、课表导入、虚拟定位、桌面小组件。

Liquid Glass 外观，纯 Compose 手写，没引第三方 UI 库。

<img src="docs/screenshot.png" width="240"> <img src="docs/screenshot-dark.png" width="240"> <img src="docs/screenshot-timetable.png" width="240">

**[下载 APK](https://github.com/SecondChild07/AsukaBox/releases/latest)** · 2.3 MB · Android 7.0+

---

## NFC 签到

贴一下任意 NFC 标签，读出里面的链接，拉起支付宝签到页。

没有标签也行：长按「签到」手动输入一次，之后点按钮就签。链接 AES 加密存在本地。

## 课表

从正方教务（zfsoft / jwglxt）拉本学期课表。

学期切换、周次与日期对应、当前周自动定位、单双周、课余量提示。
打开 App 自动同步一次，同步失败保留已有数据。

账号密码用 Android Keystore 的 AES-256-GCM 存，`adb cat` 看到的是密文。

## 虚拟定位

在开发者选项里把明日箱选成「模拟位置信息应用」，之后能把系统定位钉在任意坐标。

地图选点（高德瓦片），支持搜索城市、粘贴坐标。

搜索具体地点（小区、酒店、地铁站）需要在设置里填一个自己的高德 Key —— 到 [lbs.amap.com](https://lbs.amap.com) 免费申请，服务平台选「Web 服务」，每天 5000 次。不填也能用，只是搜不到 POI 级。

## 桌面小组件

3×2 卡片，显示今天的课和地点。没课写「今日无课」。

设置页 → 放到桌面，或者长按桌面添加。

---

## 构建

需要 JDK 17+、Android SDK 36。

```bash
./gradlew :app:assembleDebug
```

Release 需要签名配置，写在 `local.properties`（不进仓库）：

```properties
RELEASE_STORE_FILE=release.jks
RELEASE_STORE_PASSWORD=***
RELEASE_KEY_ALIAS=***
RELEASE_KEY_PASSWORD=***
```

没有这些配置也能构建，会自动落到 debug 签名。

## 发版

改 `versionCode` / `versionName`，提交，打 tag：

```bash
git tag v1.0.12 && git push origin main --tags
```

CI 会构建 release、签名、挂到 Release 上。

## 数据

| 内容 | 存法 |
|---|---|
| 教务账号密码、课表、签到链接 | Android Keystore AES-256-GCM |
| 高德 Key | 同上 |
| 学期、同步时间 | 明文 SharedPreferences |

没有任何上报。除了登录教务和拉地图瓦片，App 不联网。

## 权限

- `NFC` — 读标签
- `ACCESS_MOCK_LOCATION` — 进「模拟位置信息应用」列表（声明了也拿不到，系统只认声明）
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — Android 10~13 的前台服务硬性要求；App 不读取你的位置
- `POST_NOTIFICATIONS` — 虚拟定位的前台服务通知

## 边界

读自己的标签、导自己的课表、改自己的定位 —— 这些是工具。

替别人签到是欺诈；用虚拟定位打考勤是作弊；自动登录别人的教务是入侵。这些不在这个项目的主张范围内，作者不为这类用法负责。

## License

MIT

## 致谢

地图用 [Leaflet](https://leafletjs.com) + 高德瓦片。玻璃质感的实现思路参考了 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)。