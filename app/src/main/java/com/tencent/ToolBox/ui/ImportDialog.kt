package com.tencent.ToolBox.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tencent.ToolBox.data.Timetable
import com.tencent.ToolBox.data.TimetableStore
import com.tencent.ToolBox.glass.GlassButton
import com.tencent.ToolBox.glass.GlassButtonKind
import com.tencent.ToolBox.glass.GlassTextField
import com.tencent.ToolBox.glass.LocalGlassPalette
import com.tencent.ToolBox.net.JwglxtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 正方课表导入向导。
 *
 * 一次会话串起三步（验证码和 cookie 必须是同一个 [JwglxtSession]，所以它活在 remember 里）：
 * 打开登录页 → 需要验证码就展示图片 → 提交登录 → 拉课表。
 */
@Composable
fun ImportDialog(
    store: TimetableStore,
    onDismiss: () -> Unit,
    onImported: (Timetable) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()

    var base by remember { mutableStateOf(store.baseUrl) }
    var user by remember { mutableStateOf(store.username) }
    var pass by remember { mutableStateOf(store.password) }

    val initialTerm = remember {
        val saved = store.termKey.split('-')
        val y = saved.getOrNull(0)?.toIntOrNull()
        val t = saved.getOrNull(1)?.toIntOrNull()
        if (y != null && t != null) y to t else defaultTerm()
    }
    var year by remember { mutableStateOf(initialTerm.first) }
    var term by remember { mutableStateOf(initialTerm.second) }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var captchaImg by remember { mutableStateOf<ImageBitmap?>(null) }
    var captchaText by remember { mutableStateOf("") }
    var needCaptcha by remember { mutableStateOf(false) }

    var session by remember { mutableStateOf<JwglxtSession?>(null) }
    var page by remember { mutableStateOf<JwglxtSession.LoginPage?>(null) }

    fun shiftTerm(delta: Int) {
        // 3(秋) → 12(春，下一年的前半年) → 3 …
        if (delta > 0) {
            if (term == 3) term = 12 else { term = 3; year += 1 }
        } else {
            if (term == 12) { term = 3; year -= 1 } else term = 12
        }
    }

    suspend fun loadCaptcha(s: JwglxtSession) {
        val bytes = runCatching { s.loadCaptcha() }.getOrNull()
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        withContext(Dispatchers.Main) {
            captchaImg = bmp?.asImageBitmap()
            needCaptcha = true
        }
    }

    fun run(captcha: String?) {
        if (base.isBlank() || user.isBlank() || pass.isBlank()) {
            error = "教务地址、学号、密码都要填"
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val s = session ?: JwglxtSession(base).also {
                        session = it
                        val p = it.openLoginPage()
                        page = p
                        if (p.needCaptcha) loadCaptcha(it)
                    }

                    if (needCaptcha && captcha.isNullOrBlank()) {
                        withContext(Dispatchers.Main) { loading = false }
                        return@withContext
                    }

                    val p = page ?: s.openLoginPage()
                    s.login(user, pass, captcha, p)

                    val tt = s.fetchTimetable(year, term)

                    withContext(Dispatchers.Main) {
                        store.baseUrl = base
                        store.username = user
                        store.password = pass
                        store.termKey = "$year-$term"
                        store.save(tt)
                        loading = false
                        onImported(tt)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading = false
                    error = e.message?.takeIf { it.isNotBlank() } ?: "导入失败，检查网络或教务地址"
                    // 验证码错了：保留 session 换一张图
                    if (needCaptcha) {
                        captchaText = ""
                        session?.let { s ->
                            scope.launch {
                                withContext(Dispatchers.IO) { loadCaptcha(s) }
                            }
                        }
                    } else {
                        session = null
                        page = null
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!loading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.padding(horizontal = 22.dp)) {
            SolidGlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = "导入教务课表",
                        color = palette.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "支持正方教务系统（jwglxt）",
                        color = palette.textTertiary,
                        fontSize = 11.sp,
                    )

                    Spacer(Modifier.height(16.dp))

                    GlassTextField(
                        value = base,
                        onValueChange = { base = it.trim() },
                        placeholder = "教务地址，如 http://jw.xxx.edu.cn",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardType = KeyboardType.Uri,
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassTextField(
                        value = user,
                        onValueChange = { user = it.trim() },
                        placeholder = "学号",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        placeholder = "密码",
                        modifier = Modifier.fillMaxWidth(),
                        isPassword = true,
                    )

                    Spacer(Modifier.height(12.dp))

                    // 学期选择
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "学期",
                            color = palette.textTertiary,
                            fontSize = 12.sp,
                            modifier = Modifier.width(40.dp),
                        )
                        ArrowChip("‹") { shiftTerm(-1) }
                        Text(
                            text = termLabel(year, term),
                            color = palette.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        ArrowChip("›") { shiftTerm(1) }
                    }

                    if (needCaptcha) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlassTextField(
                                value = captchaText,
                                onValueChange = { captchaText = it },
                                placeholder = "验证码",
                                modifier = Modifier.weight(1f),
                                height = 44.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .size(width = 92.dp, height = 44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            session?.let { s ->
                                                scope.launch {
                                                    withContext(Dispatchers.IO) { loadCaptcha(s) }
                                                }
                                            }
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                val img = captchaImg
                                if (img != null) {
                                    Image(
                                        bitmap = img,
                                        contentDescription = "验证码",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.size(width = 92.dp, height = 44.dp),
                                    )
                                } else {
                                    Text("点击刷新", color = palette.textTertiary, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = it,
                            color = palette.danger,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    GlassButton(
                        text = if (loading) "连接中…" else if (needCaptcha) "确认并导入" else "登录并导入",
                        onClick = { run(if (needCaptcha) captchaText.trim() else null) },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassButton(
                        text = "取消",
                        kind = GlassButtonKind.Ghost,
                        onClick = { if (!loading) onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                        height = 44.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArrowChip(text: String, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = palette.accent, fontSize = 16.sp)
    }
}
