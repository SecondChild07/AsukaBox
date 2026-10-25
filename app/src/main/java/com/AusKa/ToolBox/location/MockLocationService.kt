package com.AusKa.ToolBox.location

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
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.AusKa.ToolBox.R

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

    /** 唤醒锁每次申请的有效期；push 循环里会续期，所以给短一点 */
    private val WAKE_LOCK_MS = 30 * 60 * 1000L

    /** 每 12000 次 push（100ms 一次 = 20 分钟）续一次唤醒锁 */
    private val WAKE_LOCK_RENEW_EVERY = 12000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null 是 START_STICKY 被系统重启时的常态。
        // 之前这里什么都不做：既不 startForeground 也不 startLoop，Android 8+ 几秒内会再杀一次，
        // 于是「重启—杀死」循环，而 isRunning 还是 true、界面照样说正在模拟。
        if (intent == null) {
            startForegroundSafely("")
            if (!mocking) startLoop()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                currentLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                currentLon = intent.getDoubleExtra(EXTRA_LON, 0.0)
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                lastError = ""
                pushedCount = 0L
                overwrittenCount = 0
                lastPushAt = 0L
                startForegroundSafely(label)
                startLoop()
            }

            ACTION_UPDATE -> {
                currentLat = intent.getDoubleExtra(EXTRA_LAT, currentLat)
                currentLon = intent.getDoubleExtra(EXTRA_LON, currentLon)
                // 服务没在跑（被回收过）时改坐标是空操作，退化成一整套启动
                if (!mocking) {
                    startForegroundSafely("")
                    startLoop()
                }
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

    /** 只把推送线程停下，不动 provider 状态（但 isRunning 得跟着撒谎的代价太大，要重置） */
    private fun stopLoopWorkerOnly() {
        mocking = false
        // 被动销毁时 UI 还靠这个静态变量判断「正在模拟」，不重置就会一直显示运行中
        isRunning = false
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
                // 每 10 次（5 秒）自检一次：读回来看看有没有被真实位置盖掉
                if (ticks % 10 == 0) {
                    runCatching { verify(manager) }
                }
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

    /** 唤醒锁是有期限的，快到期时重新拿一次，否则深睡后线程会被挂起 */
    private fun renewWakeLock() {
        val lock = wakeLock
        if (lock == null || !lock.isHeld) acquireWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AsukaBox:mockLocation").apply {
                setReferenceCounted(false)
                // 只申请 30 分钟，push 循环里每 20 分钟续一次 —— 一次申请 12 小时的做法到期不会自动续，
                // 跑满 12 小时后手机一深睡，坐标就不推了
                acquire(WAKE_LOCK_MS)
            }
        }
    }

    private fun stopLoop() {
        mocking = false
        isRunning = false
        worker?.interrupt()
        worker = null
    }

    /**
     * 把 gps / network 注册成 test provider。
     * 参数刻意对齐真实 provider 的属性（照 GoGoGo 的做法）——定位 SDK 会按
     * power/accuracy 这些属性做筛选，参数对不上就会被当成「不可信的源」丢掉。
     */
    private fun ensureProviders(manager: LocationManager) {
        SPECS.forEach { spec ->
            runCatching {
                manager.addTestProvider(
                    spec.name,
                    spec.requiresNetwork,
                    spec.requiresSatellite,
                    spec.requiresCell,
                    spec.hasMonetaryCost,
                    spec.supportsAltitude,
                    spec.supportsSpeed,
                    spec.supportsBearing,
                    spec.power,
                    spec.accuracy,
                )
            }.onFailure { if (lastError.isEmpty()) lastError = "addTestProvider(${spec.name})：${it.javaClass.simpleName} ${it.message}" }
            runCatching { manager.setTestProviderEnabled(spec.name, true) }
                .onFailure { if (lastError.isEmpty()) lastError = "setTestProviderEnabled(${spec.name})：${it.javaClass.simpleName} ${it.message}" }
        }
    }

    private fun releaseProviders(manager: LocationManager) {
        SPECS.forEach { spec ->
            runCatching { manager.setTestProviderEnabled(spec.name, false) }
            runCatching { manager.removeTestProvider(spec.name) }
        }
    }

    private fun push(manager: LocationManager, lat: Double, lon: Double) {
        SPECS.forEach { spec ->
            val location = Location(spec.name).apply {
                latitude = lat
                longitude = lon
                accuracy = spec.locationAccuracy
                altitude = 42.0
                bearing = 0f
                speed = 1.2f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = 1f
                    speedAccuracyMetersPerSecond = 0.1f
                    bearingAccuracyDegrees = 1f
                }
                // 告诉上层「有卫星」——高德这类 SDK 会看卫星数判断 GNSS 是否可用，
                // 数不到就降级去用网络定位，那等于白模拟
                if (spec.satellites > 0) {
                    extras = Bundle().apply { putInt("satellites", spec.satellites) }
                }
            }
            runCatching { manager.setTestProviderLocation(spec.name, location) }
                .onFailure { if (lastError.isEmpty()) lastError = "setTestProviderLocation(${spec.name})：${it.javaClass.simpleName} ${it.message}" }
        }
        pushedCount++
        lastPushAt = System.currentTimeMillis()
        if (pushedCount % WAKE_LOCK_RENEW_EVERY == 0L) renewWakeLock()
    }

    /**
     * 自检：读回各 provider 的 last known location，看还是不是我们写的那个坐标。
     * 如果不是，说明有别的东西（GNSS 硬件、高德自己算的网络定位）把它盖掉了。
     */
    // 只读我们自己注册的 test provider，且整体包在 runCatching 里；SDK 仍要求显式声明
    @android.annotation.SuppressLint("MissingPermission")
    private fun verify(manager: LocationManager) {
        var bad = 0
        var sample = ""
        SPECS.forEach { spec ->
            runCatching {
                val last = manager.getLastKnownLocation(spec.name) ?: return@runCatching
                val dLat = kotlin.math.abs(last.latitude - currentLat)
                val dLon = kotlin.math.abs(last.longitude - currentLon)
                if (dLat > 0.0005 || dLon > 0.0005) {
                    bad++
                    if (sample.isEmpty()) {
                        sample = "${spec.name} ${"%.5f".format(last.latitude)},${"%.5f".format(last.longitude)}"
                    }
                }
            }
        }
        if (bad > 0) {
            overwrittenCount += bad
            lastError = "有 $bad 个 provider 被真实位置盖掉了（最新：$sample）"
        }
    }

    companion object {
        const val ACTION_START = "com.AusKa.ToolBox.action.MOCK_START"
        const val ACTION_STOP = "com.AusKa.ToolBox.action.MOCK_STOP"
        const val ACTION_UPDATE = "com.AusKa.ToolBox.action.MOCK_UPDATE"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_LABEL = "label"

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 2001

        /**
         * 100ms 一次 —— 和 GoGoGo（影梭）一样。
         * 之前用 1000ms，真实的定位回调有整整一秒的窗口能挤进来，
         * 表现就是「刚打开显示模拟位置、一两秒后跳回真实」。
         */
        private const val INTERVAL_MS = 100L

        /**
         * provider 的属性必须和真实的一致，否则定位 SDK 会把它当不可信的源丢掉。
         * gps：高功耗、精确定位、需要卫星；network：低功耗、粗略、需要基站
         */
        private data class ProviderSpec(
            val name: String,
            val requiresNetwork: Boolean,
            val requiresSatellite: Boolean,
            val requiresCell: Boolean,
            val hasMonetaryCost: Boolean,
            val supportsAltitude: Boolean,
            val supportsSpeed: Boolean,
            val supportsBearing: Boolean,
            val power: Int,
            val accuracy: Int,
            val locationAccuracy: Float,
            val satellites: Int,
        )

        private val SPECS = listOf(
            ProviderSpec(
                LocationManager.GPS_PROVIDER,
                false, true, false, false, true, true, true,
                Criteria.POWER_HIGH, Criteria.ACCURACY_FINE,
                1f, 7,
            ),
            ProviderSpec(
                LocationManager.NETWORK_PROVIDER,
                true, false, true, true, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_COARSE,
                30f, 0,
            ),
        )

        /** 最近一次成功推送的时间戳，界面用它算「N 秒前」 */
        @Volatile
        var lastPushAt: Long = 0L
            private set

        /** 自检发现被真实位置盖掉的累计次数 */
        @Volatile
        var overwrittenCount: Int = 0
            private set

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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
