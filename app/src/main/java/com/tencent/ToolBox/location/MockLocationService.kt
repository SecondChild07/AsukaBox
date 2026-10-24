package com.tencent.ToolBox.location

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.tencent.ToolBox.R

/**
 * 模拟位置。
 *
 * 走的是系统官方的 test provider —— 不需要 root，也不需要装 Xposed：
 * 用户只要在「开发者选项 → 选择模拟位置信息应用」里把明日箱选上，本 App 就能
 * 往 GPS / NETWORK 两个 provider 里灌坐标，其它 App 通过 LocationManager 拿到的
 * 就是这里的值。
 *
 * 几个必须知道的限制：
 * - 开关在开发者选项里，是用户手动授权的，App 自己改不了（这是系统的安全设计）
 * - 坐标得持续灌，系统不会记住。所以这里用前台服务每秒推一次，停了就恢复真实定位
 * - 打车类 App 会自己检测 isFromMockProvider()，这类检测不是我们能绕的（也不打算绕）
 */
class MockLocationService : Service() {

    private var worker: Thread? = null

    /** 当前要灌的坐标，摇杆推动时由 [update] 直接改，不用重启服务 */
    @Volatile
    private var currentLat = 0.0

    @Volatile
    private var currentLon = 0.0

    @Volatile
    private var mocking = false

    /** 用户点了「停止」——只有这种情况才把 provider 交还系统 */
    private var userStopped = false

    /** 服务被系统回收时置 true，让推送线程别去 releaseProviders */
    @Volatile
    private var keepAlive = false

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                currentLon = intent.getDoubleExtra(EXTRA_LON, 0.0)
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                lastError = ""
                pushedCount = 0L
                startForegroundSafely(label)
                startLoop()
            }

            ACTION_UPDATE -> {
                currentLat = intent.getDoubleExtra(EXTRA_LAT, currentLat)
                currentLon = intent.getDoubleExtra(EXTRA_LON, currentLon)
            }

            ACTION_STOP -> {
                userStopped = true
                stopLoop()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 只有用户主动点「停止」才真的把 provider 交还系统。
        // 被系统回收（国产 ROM 很爱干这个）时保持 mock 状态，位置冻结在最后一点，
        // 好过整块跳回真实位置 —— 配合 START_STICKY 起来之后接着推。
        if (!userStopped) {
            keepAlive = true
            stopLoopWorkerOnly()
        } else {
            stopLoop()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    /** 只把推送线程停下，不动 provider 状态 */
    private fun stopLoopWorkerOnly() {
        mocking = false
        worker?.interrupt()
        worker = null
    }

    private fun startForegroundSafely(label: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "模拟位置",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "正在把定位固定到某个坐标"
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }

        val text = if (label.isBlank()) "正在模拟位置，点此回到明日箱" else "正在模拟：$label"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("模拟位置中")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        /*
         * 前台服务类型按版本分：
         * - 29..33：location（这段只要求 FOREGROUND_SERVICE 权限）
         * - 34+：specialUse（location 类型在 34 上强制要求精确定位权限，而我们其实不读定位，用 specialUse 更准确）
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startLoop() {
        stopLoop()
        userStopped = false
        keepAlive = false
        val manager = getSystemService(LocationManager::class.java) ?: return
        mocking = true
        isRunning = true
        acquireWakeLock()

        worker = Thread({
            ensureProviders(manager)
            var ticks = 0
            while (mocking) {
                push(manager, currentLat, currentLon)
                ticks++
                // 每 20 次（10 秒）重申一次：有些 ROM 会把 test provider 状态悄悄重置
                if (ticks % 20 == 0) {
                    runCatching { ensureProviders(manager) }
                }
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            // 用户主动停止才交还；被动销毁时保留 mock 状态，位置冻结而非跳回真实
            if (!keepAlive) releaseProviders(manager)
        }, "mock-location").apply {
            isDaemon = true
            start()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AsukaBox:mockLocation").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }
    }

    private fun stopLoop() {
        mocking = false
        isRunning = false
        worker?.interrupt()
        worker = null
    }

    /** 把 gps / network 两个 provider 注册成 test provider（重复调用不会报错） */
    private fun ensureProviders(manager: LocationManager) {
        // 先把系统里存在、但我们还没纳入的 provider 全部拉进来 —— fused 就是这一步补上的
        runCatching {
            manager.allProviders
                .filter { it != LocationManager.PASSIVE_PROVIDER && it !in PROVIDERS }
                .forEach { PROVIDERS.add(it) }
        }
        PROVIDERS.forEach { name ->
            runCatching {
                manager.addTestProvider(
                    name,
                    false,  // requiresNetwork
                    true,   // requiresSatellite
                    false,  // requiresCell
                    false,  // hasMonetaryCost
                    true,   // supportsAltitude
                    true,   // supportsSpeed
                    true,   // supportsBearing
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE,
                )
            }.onFailure { if (lastError.isEmpty()) lastError = "addTestProvider($name)：${it.javaClass.simpleName} ${it.message}" }
            runCatching { manager.setTestProviderEnabled(name, true) }
                .onFailure { if (lastError.isEmpty()) lastError = "setTestProviderEnabled($name)：${it.javaClass.simpleName} ${it.message}" }
        }
    }

    private fun releaseProviders(manager: LocationManager) {
        PROVIDERS.forEach { name ->
            runCatching { manager.setTestProviderEnabled(name, false) }
            runCatching { manager.removeTestProvider(name) }
        }
    }

    private fun push(manager: LocationManager, lat: Double, lon: Double) {
        PROVIDERS.forEach { name ->
            val location = Location(name).apply {
                latitude = lat
                longitude = lon
                accuracy = 3f
                altitude = 42.0
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearing = 0f
                    speed = 0f
                    verticalAccuracyMeters = 1f
                    speedAccuracyMetersPerSecond = 0.1f
                    bearingAccuracyDegrees = 1f
                }
            }
            runCatching { manager.setTestProviderLocation(name, location) }
                .onFailure { if (lastError.isEmpty()) lastError = "setTestProviderLocation($name)：${it.javaClass.simpleName} ${it.message}" }
        }
        pushedCount++
    }

    companion object {
        const val ACTION_START = "com.tencent.ToolBox.action.MOCK_START"
        const val ACTION_STOP = "com.tencent.ToolBox.action.MOCK_STOP"
        const val ACTION_UPDATE = "com.tencent.ToolBox.action.MOCK_UPDATE"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_LABEL = "label"

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 2001
        private const val INTERVAL_MS = 1000L

        /**
         * 默认只覆盖 gps / network 两个。运行时会再加上自检通过的额外 provider
         * （有些 ROM 上还有 fused / passive，高德之类的 SDK 会优先读 fused）
         */
        private val PROVIDERS = mutableListOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )

        /** 服务是否正在跑，给界面用 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 最近一次失败原因（空串表示没出错），界面上直接显示出来便于排查 */
        @Volatile
        var lastError: String = ""
            private set

        /** 已经成功灌了多少次坐标，界面用它判断「真的在推」 */
        @Volatile
        var pushedCount: Long = 0L
            private set

        /**
         * 本 App 是否已被选为「模拟位置信息应用」。
         *
         * 这个开关只能由用户在开发者选项里打开，所以拿不到就是 `false`，
         * 界面据此显示「去开发者选项」的引导。
         */
        fun canMock(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ops.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(),
                        context.packageName,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    ops.checkOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(),
                        context.packageName,
                    )
                }
            }.getOrDefault(AppOpsManager.MODE_ERRORED)
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun start(context: Context, lat: Double, lon: Double, label: String) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LON, lon)
                putExtra(EXTRA_LABEL, label)
            }
            context.startForegroundService(intent)
        }

        /** 只换坐标，不动服务（摇杆每动一下就调一次） */
        fun update(context: Context, lat: Double, lon: Double) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LON, lon)
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** 开发者选项里「选择模拟位置信息应用」的入口 */
        fun openDeveloperOptions(context: Context) {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}
