package com.AusKa.ToolBox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.AusKa.ToolBox.data.Course
import com.AusKa.ToolBox.data.Timetable
import com.AusKa.ToolBox.data.TimetableStore
import com.AusKa.ToolBox.data.UrlStore
import com.AusKa.ToolBox.security.Crypt
import com.AusKa.ToolBox.security.SecretBox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 本地加密存储的抗丢数据能力（真机/模拟器上跑）。
 *
 * 对应用户报的「手机重启后 NFC 链接和课表数据会清除」：现场能查到的只有一条路——
 * Keystore 里的主钥匙被系统重置，或者 prefs 文件被清掉/写坏，于是密文解不开、
 * 旧实现把它当成「没有数据」，界面就空了。这里把那两条路都造出来，验证数据还在。
 */
@RunWith(AndroidJUnit4::class)
class StoreResilienceTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val ttPrefs by lazy {
        ctx.getSharedPreferences(Crypt.decode(Crypt.PREFS) + "_tt", Context.MODE_PRIVATE)
    }

    @Before
    fun clean() {
        TimetableStore.get(ctx).wipeAccount()
        UrlStore(ctx).clear()
    }

    @After
    fun tearDown() {
        TimetableStore.get(ctx).wipeAccount()
        UrlStore(ctx).clear()
    }

    private fun sample(): Timetable = Timetable(
        courses = listOf(
            Course(
                name = "大学英语（三）",
                teacher = "王老师",
                room = "教三 402",
                day = 2,
                startSection = 1,
                endSection = 2,
                weeks = (1..16).toSet(),
                zcd = "1-16周",
            ),
        ),
        year = 2026,
        term = 3,
        studentName = "测试同学",
        startDate = "2026-09-07",
    )

    /** 新写的数据必须是双副本格式，而且 Keystore 和兜底钥匙都能各解一遍 */
    @Test
    fun 新写的课表是双副本() {
        val store = TimetableStore.get(ctx)
        assertTrue(store.save(sample()))
        val stored = ttPrefs.getString("tt_data", "").orEmpty()
        assertTrue("应为 ks2: 双副本，实际 " + stored.take(6), stored.startsWith("ks2:"))
        assertNotEquals(0, SecretBox.probe(stored))
        assertTrue("兜底副本也要能单独解开", SecretBox.fallbackPart(stored) != null)
    }

    /**
     * 模拟「主钥匙失效」：把 ks 那一段换成一串解不开的字节，兜底副本保持原样。
     *
     * 这等价于 Keystore 里的密钥被系统重置 —— 密文还在，主钥匙没了。
     */
    @Test
    fun 主钥匙失效后课表还能读回来() {
        val store = TimetableStore.get(ctx)
        assertTrue(store.save(sample()))
        val stored = ttPrefs.getString("tt_data", "").orEmpty()
        ttPrefs.edit().putString("tt_data", breakKeystorePart(stored)).commit()

        // 重新构造一个 store（等价于重启后再打开）：内存缓存不能参与，必须真从盘上读
        store.reload()
        val back = store.load()
        assertEquals("主钥匙丢了，兜底副本必须顶上", 1, back?.courses?.size ?: 0)
        assertEquals("大学英语（三）", back?.courses?.firstOrNull()?.name)
    }

    /** 模拟 prefs 被清：文件里没有课表了，靠 files/ 下的镜像补回来 */
    @Test
    fun prefs被清后能从镜像恢复() {
        val store = TimetableStore.get(ctx)
        assertTrue(store.save(sample()))
        ttPrefs.edit().remove("tt_data").commit()

        val repaired = store.repairFromMirrorIfNeeded()
        assertTrue("镜像必须能把课表补回来", repaired)
        assertEquals(1, store.load()?.courses?.size ?: 0)
        assertTrue("补回来以后要重新落盘", ttPrefs.getString("tt_data", "").orEmpty().startsWith("ks2:"))
    }

    /** prefs 写坏了（整段不是合法密文）也不能把镜像顶掉 */
    @Test
    fun prefs里是垃圾也能从镜像恢复() {
        val store = TimetableStore.get(ctx)
        assertTrue(store.save(sample()))
        ttPrefs.edit().putString("tt_data", "ks1:这不是base64").commit()

        assertTrue(store.repairFromMirrorIfNeeded())
        assertEquals(1, store.load()?.courses?.size ?: 0)
    }

    /** 签到链接同理：双副本 + 镜像 */
    @Test
    fun 签到链接有双副本和镜像() {
        val store = UrlStore(ctx)
        store.url = "https://render.alipay.com/p/yuyan/test"
        assertEquals("https://render.alipay.com/p/yuyan/test", UrlStore(ctx).url)

        val raw = ctx.getSharedPreferences(Crypt.decode(Crypt.PREFS), Context.MODE_PRIVATE)
            .getString("checkin_url", "").orEmpty()
        assertTrue(raw.startsWith("ks2:"))

        // 模拟 Keystore 主钥匙失效
        ctx.getSharedPreferences(Crypt.decode(Crypt.PREFS), Context.MODE_PRIVATE)
            .edit().putString("checkin_url", breakKeystorePart(raw)).commit()
        assertEquals("https://render.alipay.com/p/yuyan/test", UrlStore(ctx).url)

        // 模拟 prefs 被清
        ctx.getSharedPreferences(Crypt.decode(Crypt.PREFS), Context.MODE_PRIVATE)
            .edit().remove("checkin_url").commit()
        assertEquals("https://render.alipay.com/p/yuyan/test", UrlStore(ctx).url)
    }

    /** 密码这类凭据不走兜底副本：宁可让用户重填，也不降级成能随手解开的盒子 */
    @Test
    fun 密码只走主钥匙() {
        val store = TimetableStore.get(ctx)
        store.baseUrl = "https://jw.example.edu.cn"
        store.username = "20260001"
        store.password = "s3cret"
        val stored = ttPrefs.getString("jw_pass", "").orEmpty()
        assertTrue(stored.startsWith("ks1:"))
        assertFalse("密码不该有兜底副本", stored.startsWith("ks2:"))
        assertEquals("s3cret", store.password)
        assertEquals("20260001", store.username)
    }
}

/**
 * 把双副本密文里的「Keystore 那一段」换成一段解不开的字节，兜底那一段原样保留。
 * 等价于系统把 Keystore 里的密钥重置了：密文还在，主钥匙没了。
 */
private fun breakKeystorePart(sealed: String): String {
    val body = sealed.removePrefix("ks2:")
    val dot = body.indexOf('.')
    if (dot < 0) return sealed
    return "ks2:AAAA" + body.substring(dot)
}
