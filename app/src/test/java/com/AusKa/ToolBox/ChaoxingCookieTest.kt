package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.ChaoxingScripts
import com.AusKa.ToolBox.chaoxing.CookieSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话快照编解码 + 注入脚本的拼接。
 *
 * 这两块都是纯字符串逻辑，没有 WebView、没有 Keystore，可以直接在 JVM 上跑。
 */
class ChaoxingCookieTest {

    @Test
    fun emptyEntriesEncodeToEmpty() {
        assertEquals("", CookieSnapshot.encode(emptyList()))
        assertEquals("", CookieSnapshot.encode(listOf("i.chaoxing.com" to "")))
        assertEquals("", CookieSnapshot.encode(listOf("" to "_uid=1")))
    }

    @Test
    fun roundTripKeepsHostAndCookie() {
        val entries = listOf(
            "i.chaoxing.com" to "_uid=123; fid=456; _tid=abc",
            "mooc1.chaoxing.com" to "UID=123; DSSTASH_LOG=1",
        )
        val raw = CookieSnapshot.encode(entries)
        assertTrue(raw.startsWith(CookieSnapshot.VERSION_HEADER))
        assertEquals(entries, CookieSnapshot.decode(raw))
    }

    @Test
    fun decodeSkipsHeaderBlankAndMalformedLines() {
        val raw = "v1\n\ni.chaoxing.com\t_uid=1\n没有制表符的一行\n\t空域\nmooc1.chaoxing.com\tUID=2"
        val decoded = CookieSnapshot.decode(raw)
        assertEquals(2, decoded.size)
        assertEquals("i.chaoxing.com", decoded[0].first)
        assertEquals("UID=2", decoded[1].second)
    }

    @Test
    fun decodeBlankIsEmptyList() {
        assertEquals(0, CookieSnapshot.decode("").size)
        assertEquals(0, CookieSnapshot.decode("   \n  ").size)
    }

    @Test
    fun namesParsesCookiePairs() {
        assertEquals(listOf("_uid", "fid", "_tid"), CookieSnapshot.names("_uid=1; fid=2; _tid=3"))
        assertEquals(0, CookieSnapshot.names("").size)
        assertEquals(listOf("a"), CookieSnapshot.names("a=1; =2"))
    }

    @Test
    fun totalsAndHosts() {
        val raw = CookieSnapshot.encode(
            listOf(
                "i.chaoxing.com" to "_uid=1; fid=2",
                "passport2.chaoxing.com" to "UID=3",
            )
        )
        assertEquals(3, CookieSnapshot.totalNames(raw))
        assertEquals(listOf("i.chaoxing.com", "passport2.chaoxing.com"), CookieSnapshot.hosts(raw))
    }

    @Test
    fun looksSignedInNeedsKeyCookie() {
        assertFalse(CookieSnapshot.looksSignedIn(""))
        assertFalse(CookieSnapshot.looksSignedIn(CookieSnapshot.encode(listOf("i.chaoxing.com" to "a=1; b=2"))))
        assertTrue(CookieSnapshot.looksSignedIn(CookieSnapshot.encode(listOf("i.chaoxing.com" to "_uid=9"))))
        assertTrue(CookieSnapshot.looksSignedIn(CookieSnapshot.encode(listOf("i.chaoxing.com" to "UID=9; x=1"))))
        assertTrue(CookieSnapshot.looksSignedIn(CookieSnapshot.encode(listOf("passport2.chaoxing.com" to "fid=1"))))
    }

    @Test
    fun pickScriptQuotesLetters() {
        val js = ChaoxingScripts.pickJs("AC")
        assertTrue(js.contains("window.__cs.pick(\"AC\")"))
        assertTrue(js.startsWith("JSON.stringify("))
    }

    @Test
    fun fillScriptEscapesQuotesAndNewlines() {
        val js = ChaoxingScripts.fillJs(2, "他说：\"好\"\n第二行")
        assertTrue(js.contains("window.__cs.fill(2, "))
        assertTrue(js.contains("\\\""))
        assertTrue(js.contains("\\n"))
        // 换行不能原样漏进 JS 字面量，否则整段脚本语法错误
        assertFalse(js.contains("\n第二行"))
    }

    @Test
    fun loginUrlAndUserAgentAreStable() {
        assertTrue(ChaoxingScripts.LOGIN_URL.startsWith("https://passport2.chaoxing.com/"))
        assertTrue(ChaoxingScripts.DESKTOP_UA.contains("Windows NT"))
        assertFalse(ChaoxingScripts.DESKTOP_UA.contains("Mobile"))
    }
}
