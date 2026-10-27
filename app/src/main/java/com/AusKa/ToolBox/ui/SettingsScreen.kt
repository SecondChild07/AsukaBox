package com.AusKa.ToolBox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.AusKa.ToolBox.BuildConfig
import com.AusKa.ToolBox.data.AmapKeyStore
import com.AusKa.ToolBox.data.Author
import com.AusKa.ToolBox.chaoxing.AiEndpoints
import com.AusKa.ToolBox.chaoxing.AiProvider
import com.AusKa.ToolBox.chaoxing.AiProviderStore
import com.AusKa.ToolBox.data.ChaoxingStore
import com.AusKa.ToolBox.data.SectionTimesStore
import com.AusKa.ToolBox.data.TimetableStore
import com.AusKa.ToolBox.data.UrlStore
import com.AusKa.ToolBox.glass.GlassPalette
import com.AusKa.ToolBox.glass.LiquidGlass
import com.AusKa.ToolBox.glass.LocalGlassPalette

/**
 * 设置页。
 *
 * 目前只有一条「作者主页」，其余是版本信息。以后加开关（自动签到、清理缓存之类）
 * 直接往 [LiquidGlass] 里塞 [GlassRow] + [GlassDivider] 就行。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val context = LocalContext.current
    val app = context.applicationContext
    val urlStore = remember { UrlStore(app) }
    val ttStore = remember { TimetableStore.get(app) }
    var confirmWipe by remember { mutableStateOf(false) }
    var amapKey by remember { mutableStateOf(AmapKeyStore.get(app)) }
    var editKey by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    // 学习通助手的 AI Key / 模型名，都走 SecretBox 加密存本机
    var aiKey by remember { mutableStateOf(ChaoxingStore.aiKey(app)) }
    var aiModel by remember { mutableStateOf(ChaoxingStore.aiModel(app)) }
    var aiBase by remember { mutableStateOf(ChaoxingStore.aiBase(app)) }
    var editAi by remember { mutableStateOf(false) }
    var aiDraft by remember { mutableStateOf("") }
    var modelDraft by remember { mutableStateOf("") }
    var baseDraft by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    // 供应商列表（内置的那几条 + 自己加的），点一下就切过去
    var providers by remember { mutableStateOf(AiProviderStore.load(app)) }
    var editingProvider by remember { mutableStateOf<AiProvider?>(null) }
    var pvName by remember { mutableStateOf("") }
    var pvBase by remember { mutableStateOf("") }
    var pvModel by remember { mutableStateOf("") }
    // 上课时间：12 节的开始/结束，改完立刻落盘并刷新课表
    var editTimes by remember { mutableStateOf(false) }
    var timeDraft by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    val hasAccount = ttStore.hasAccount

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = "设置",
            color = palette.textPrimary,
            fontSize = 30.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "关于这个箱子",
            color = palette.textTertiary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 22.dp),
        )

        Spacer(Modifier.height(26.dp))

        LiquidGlass(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            shape = RoundedCornerShape(22.dp),
            blurRadius = 20.dp,
            shadow = 6.dp,
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            Column {
                GlassRow(
                    title = "放到桌面",
                    subtitle = "把今天要上的课做成一张小卡片，添到桌面",
                    onClick = { requestWidgetPin(context) },
                    trailingArrow = true,
                )
                GlassDivider()
                // 地图搜索：不填就只能搜内置的 382 条城市/地标
                GlassRow(
                    title = "高德 Key",
                    subtitle = if (amapKey.isBlank())
                        "填了才能在地图上搜任意地点（开放平台免费申请，选「Web服务」）"
                    else
                        "已配置 · 地图搜索可以搜到具体地点",
                    value = if (amapKey.isBlank()) "未填" else "已填",
                    onClick = { keyDraft = amapKey; editKey = true },
                    trailingArrow = true,
                )
                GlassDivider()
                // 学习通助手的答题要用，放在设置里统一管
                GlassRow(
                    title = "AI Key",
                    subtitle = if (aiKey.isBlank())
                        "学习通助手里「AI 答题」要用（OpenAI 兼容接口的 key，只存本机）"
                    else
                        "已配置 · 已用 Keystore 加密存在本机",
                    value = if (aiKey.isBlank()) "未填" else "已填",
                    onClick = {
                        aiDraft = ""
                        modelDraft = aiModel
                        baseDraft = aiBase
                        editAi = true
                    },
                    trailingArrow = true,
                )
                GlassDivider()
                GlassRow(
                    title = "上课时间",
                    subtitle = if (SectionTimesStore.hasCustom(app))
                        "已自定义 · 课表按你设的时间显示"
                    else
                        "课表里每节课的时间，默认 8:00 开始的常见作息",
                    value = "改",
                    onClick = { timeDraft = loadTimes(app); editTimes = true },
                    trailingArrow = true,
                )
                if (hasAccount) {
                    GlassDivider()
                    // 37：存了别人的教务密码，就得给一条能删干净的路
                    GlassRow(
                        title = "清除教务账号",
                        subtitle = "账号、密码、会话、课表一起删掉",
                        onClick = { confirmWipe = true },
                    )
                }
                GlassDivider()
                GlassRow(
                    title = "作者主页",
                    subtitle = Author.URL.removePrefix("https://"),
                    onClick = { openLink(context, Author.URL) },
                    trailingArrow = true,
                )
                GlassDivider()
                GlassRow(title = "版本", value = BuildConfig.VERSION_NAME)
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "开源项目 · MIT License",
            color = palette.textTertiary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 22.dp),
        )

        Spacer(Modifier.height(26.dp))
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("清除教务账号？") },
            text = { Text("学号、密码、登录会话和已导入的课表都会从这台手机删掉，桌面卡片也会变空。这个操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    ttStore.wipeAccount()
                    // 开关的本地状态也得跟着关，否则这一行会一直显示「开」到离开设置页
                    confirmWipe = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("取消") }
            },
        )
    }

    if (editAi) {
        AlertDialog(
            onDismissRequest = { editAi = false },
            title = { Text("AI 答题设置") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Key 只存在这台手机上（Keystore 加密），不会经过任何第三方服务器；答题时由 App 直接请求你填的服务商。",
                        fontSize = 13.sp,
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("供应商", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(
                        "点一下就切过去（地址和模型自动填好）；下面还能自己加中转站或本地模型",
                        fontSize = 11.sp,
                        color = palette.textTertiary,
                    )
                    Column(modifier = Modifier.heightIn(max = 132.dp).verticalScroll(rememberScrollState())) {
                    providers.forEach { p ->
                        val active = p.base == baseDraft && p.model == modelDraft
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    baseDraft = p.base
                                    modelDraft = p.model
                                    showAdvanced = true
                                }
                                .padding(vertical = 7.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.name, fontSize = 13.sp, color = palette.textPrimary)
                                Text(
                                    p.model + " · " + p.base.removePrefix("https://").take(38),
                                    fontSize = 10.sp,
                                    color = palette.textTertiary,
                                )
                            }
                            if (active) {
                                Text("当前", fontSize = 11.sp, color = palette.success)
                            } else {
                                TextButton(onClick = {
                                    providers = providers.filterNot { it.id == p.id }
                                    AiProviderStore.save(app, providers)
                                }) { Text("删", fontSize = 11.sp) }
                            }
                        }
                    }
                    TextButton(onClick = {
                        pvName = ""
                        pvBase = ""
                        pvModel = ""
                        editingProvider = AiProvider("custom-" + System.currentTimeMillis(), "", "", "")
                    }) { Text("＋ 添加供应商", fontSize = 12.sp) }
                    }

                    Spacer(Modifier.height(10.dp))
                    AiKeyFields(
                        keyValue = aiDraft,
                        onKey = { aiDraft = it },
                        keyHint = if (aiKey.isBlank()) "sk-…" else "留空表示不改动已存的 Key",
                        modelValue = modelDraft,
                        onModel = {
                            modelDraft = it
                            // 模型名一改就顺手把地址填好（用户自己改过的不覆盖）
                            AiEndpoints.baseFor(it)?.let { b ->
                                if (baseDraft.isBlank() || AiEndpoints.baseFor(modelDraft) != null) {
                                    baseDraft = b
                                }
                            }
                        },
                        baseValue = baseDraft,
                        onBase = { baseDraft = it },
                        advanced = showAdvanced,
                    )

                    if (showAdvanced) {
                        AiEndpoints.hintFor(modelDraft)?.let { hint ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                hint + " · 地址已按模型名填好，改成中转站也行",
                                fontSize = 11.sp,
                                color = palette.success,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (showAdvanced) {
                            "带视觉能力（glm-4v / gemini / gpt-4o 这类）才能做听力图片题；" +
                                "纯文本模型（deepseek-chat 等）遇到看图题会跳过，并提示你换模型。"
                        } else {
                            "默认用 DeepSeek 的 deepseek-chat。要换 Kimi、通义、智谱这些，" +
                                "点上面的「高级」，模型名一填地址就自动配好。"
                        },
                        fontSize = 12.sp,
                        color = palette.textTertiary,
                    )

                    if (!showAdvanced) {
                        TextButton(onClick = { showAdvanced = true }) {
                            Text("高级：换接口地址和模型名", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val k = aiDraft.trim()
                    if (k.isNotEmpty()) {
                        ChaoxingStore.setAiKey(app, k)
                        aiKey = k
                    }
                    // 只有展开「高级」时才动模型名和地址，避免只改 Key 时把已存的配置清空
                    if (showAdvanced) {
                        val m = modelDraft.trim()
                        ChaoxingStore.setAiModel(app, m)
                        aiModel = m
                        ChaoxingStore.setAiBase(app, baseDraft.trim())
                        aiBase = baseDraft.trim()
                    }
                    editAi = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ChaoxingStore.setAiKey(app, "")
                    aiKey = ""
                    editAi = false
                }) { Text("清空 Key") }
            },
        )
    }

    val target = editingProvider
    if (target != null) {
        AlertDialog(
            onDismissRequest = { editingProvider = null },
            title = { Text("添加供应商") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pvName,
                        onValueChange = { pvName = it },
                        singleLine = true,
                        label = { Text("名字", fontSize = 12.sp) },
                        placeholder = { Text("比如 中转站 / Ollama", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pvBase,
                        onValueChange = { pvBase = it },
                        singleLine = true,
                        label = { Text("接口地址", fontSize = 12.sp) },
                        placeholder = { Text("https://…/v1", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pvModel,
                        onValueChange = { pvModel = it },
                        singleLine = true,
                        label = { Text("模型名", fontSize = 12.sp) },
                        placeholder = { Text("deepseek-chat / qwen2.5:7b", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = pvName.trim().ifBlank { pvModel.trim() }
                    val base = pvBase.trim()
                    val model = pvModel.trim()
                    if (base.startsWith("http") && model.isNotEmpty()) {
                        providers = providers + AiProvider(target.id, name, base, model)
                        AiProviderStore.save(app, providers)
                        baseDraft = base
                        modelDraft = model
                        showAdvanced = true
                    }
                    editingProvider = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingProvider = null }) { Text("取消") }
            },
        )
    }
    if (editTimes) {
        AlertDialog(
            onDismissRequest = { editTimes = false },
            title = { Text("上课时间") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "按你学校的作息改，写成 08:00 这种；留空就用默认值。改完课表马上跟着变。",
                        fontSize = 12.sp,
                        color = palette.textTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    timeDraft.forEachIndexed { index, pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("第 ${index + 1} 节", fontSize = 12.sp, modifier = Modifier.width(58.dp))
                            OutlinedTextField(
                                value = pair.first,
                                onValueChange = { v ->
                                    timeDraft = timeDraft.toMutableList().also {
                                        it[index] = v to it[index].second
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("开始", fontSize = 11.sp) },
                            )
                            Text(" - ", fontSize = 12.sp)
                            OutlinedTextField(
                                value = pair.second,
                                onValueChange = { v ->
                                    timeDraft = timeDraft.toMutableList().also {
                                        it[index] = it[index].first to v
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("结束", fontSize = 11.sp) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    timeDraft.forEachIndexed { index, pair ->
                        val s = SectionTimesStore.parseTime(pair.first)
                        val e = SectionTimesStore.parseTime(pair.second)
                        if (s != null || e != null) {
                            SectionTimesStore.save(app, index + 1, s ?: "", e ?: "")
                        }
                    }
                    editTimes = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    SectionTimesStore.reset(app)
                    timeDraft = loadTimes(app)
                    editTimes = false
                }) { Text("恢复默认") }
            },
        )
    }
    if (editKey) {
        AlertDialog(
            onDismissRequest = { editKey = false },
            title = { Text("高德 Key") },
            text = {
                Column {
                    Text(
                        "在 lbs.amap.com 控制台创建应用 → 添加 Key → 服务平台选「Web服务」，把那串 key 粘进来。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        singleLine = true,
                        placeholder = { Text("例如 a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "留空表示不用联网搜索，地图里只能搜内置的 382 条城市/地标。",
                        fontSize = 12.sp,
                        color = palette.textTertiary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // 从网页复制来的 key 常带首尾空格或折行：存和显示都走 trim，
                    // 不然盘里那份带着空白，界面显示「已填」，请求却永远 INVALID_USER_KEY
                    val k = keyDraft.trim()
                    AmapKeyStore.set(app, k)
                    amapKey = k
                    editKey = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editKey = false }) { Text("取消") }
            },
        )
    }
}

/** Key 输入框；只有展开「高级」时才露模型名和接口地址 */
@Composable
private fun AiKeyFields(
    keyValue: String,
    onKey: (String) -> Unit,
    keyHint: String,
    modelValue: String,
    onModel: (String) -> Unit,
    baseValue: String,
    onBase: (String) -> Unit,
    advanced: Boolean = false,
    modelHint: String = "例如 deepseek-chat / gpt-4o-mini",
) {
    Column {
        OutlinedTextField(
            value = keyValue,
            onValueChange = onKey,
            singleLine = true,
            placeholder = { Text(keyHint) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!advanced) return@Column
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = modelValue,
            onValueChange = onModel,
            singleLine = true,
            placeholder = { Text(modelHint) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = baseValue,
            onValueChange = onBase,
            singleLine = true,
            placeholder = { Text("接口地址，填到 /v1 为止") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 走系统的「请求固定小组件」流程：支持的桌面会直接弹确认框，
 * 不支持（少数第三方 launcher）就提示用户自己去桌面长按添加。
 */
private fun requestWidgetPin(context: android.content.Context) {
    val manager = android.appwidget.AppWidgetManager.getInstance(context)
    val provider = android.content.ComponentName(context, com.AusKa.ToolBox.widget.CourseWidgetProvider::class.java)
    val canPin = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
        manager.isRequestPinAppWidgetSupported
    if (canPin) {
        manager.requestPinAppWidget(provider, null, null)
    } else {
        android.widget.Toast
            .makeText(context, "这个桌面不支持一键添加，长按桌面 → 小组件 里找「明日箱」", android.widget.Toast.LENGTH_LONG)
            .show()
    }
}

/** 读 12 节课的上/下课时间，编辑对话框用 */
private fun loadTimes(context: android.content.Context): List<Pair<String, String>> =
    (1..SectionTimesStore.SECTIONS).map {
        SectionTimesStore.start(context, it) to SectionTimesStore.end(context, it)
    }