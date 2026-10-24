package com.AusKa.ToolBox

import com.AusKa.ToolBox.net.extractLoginError
import com.AusKa.ToolBox.net.loginSucceeded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录判定的那两句。
 *
 * 这块是全项目最容易因学校差异出错的地方：判定太松会把失败当成功（后面拉课表报一堆看不懂的错），
 * 太严会把成功当失败（明明进去了却说密码不对）。所以从 `JwglxtSession` 里抽出来单测。
 */
class LoginJudgeTest {

    @Test
    fun `重定向到 initMenu 算成功`() {
        assertTrue(
            loginSucceeded(
                "https://jw.example.edu.cn/xtgl/index_initMenu.html?jsdm=xs",
                "<html>whatever</html>",
            ),
        )
    }

    @Test
    fun `正文里带 initMenu 也算成功（有些部署不 302）`() {
        assertTrue(loginSucceeded("https://jw.example.edu.cn/xtgl/login_slogin.html", "<script>location='index_initMenu.html'</script>"))
    }

    @Test
    fun `还是登录页就是失败`() {
        // 最终 URL 和正文都还是登录页 —— 最典型的「密码不对」
        assertFalse(
            loginSucceeded(
                "https://jw.example.edu.cn/xtgl/login_slogin.html",
                """<form action="login_slogin.html"><input name="csrftoken" value="x"></form>""",
            ),
        )
    }

    @Test
    fun `没有登录页痕迹就算通过（宽松兜底）`() {
        // 有些部署登录成功直接返回一个首页，既没 initMenu 也没 login_slogin
        assertTrue(loginSucceeded("https://jw.example.edu.cn/xtgl/index.html", "<div>欢迎</div>"))
    }

    @Test
    fun `能从 tips 里抠出服务端提示`() {
        val body = """<div id="tips">用户名或密码不正确，请重新输入！</div>"""
        assertEquals("用户名或密码不正确，请重新输入！", extractLoginError(body))
    }

    @Test
    fun `能从 bg_danger 块里抠提示`() {
        val body = """<div class="alert bg_danger">验证码错误</div>"""
        assertEquals("验证码错误", extractLoginError(body))
    }

    @Test
    fun `能从 JSON 响应里抠 message`() {
        val body = """{"status":"0","message":"账号已被锁定，请联系教务处"}"""
        assertEquals("账号已被锁定，请联系教务处", extractLoginError(body))
    }

    @Test
    fun `抠不到就给能指导动作的兜底文案`() {
        val msg = extractLoginError("<html><body>error</body></html>")
        assertTrue(msg.contains("账号或密码"))
        assertTrue(msg.contains("统一身份认证"))
    }
}
