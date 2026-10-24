package com.tencent.ToolBox.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tencent.ToolBox.data.TimetableStore
import com.tencent.ToolBox.data.UrlStore

/** 底部两个常驻入口 */
enum class NavTab { Functions, Settings }

/**
 * 全局导航。
 *
 * 只有两层：底部 tab（功能 / 设置）+ 一个可压栈的功能详情页。
 * 没引 Navigation Compose —— 这点层级用不上那套 graph，一个 [rememberSaveable] 的
 * 路由字符串就够了，还省掉一个依赖。
 */
@Composable
fun ToolboxRoot(
    activity: ComponentActivity,
    store: UrlStore,
) {
    var tabName by rememberSaveable { mutableStateOf(NavTab.Functions.name) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }

    val tab = NavTab.valueOf(tabName)
    val timetableStore = remember { TimetableStore.get(activity.applicationContext) }

    BackHandler(enabled = detailId != null) { detailId = null }

    AnimatedContent(
        targetState = detailId,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(300)) { it / 3 } + fadeIn(tween(220))) togetherWith
                    fadeOut(tween(140))
            } else {
                fadeIn(tween(220)) togetherWith
                    (slideOutHorizontally(tween(300)) { it / 3 } + fadeOut(tween(160)))
            }
        },
        label = "detail",
    ) { detail ->
        if (detail == null) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                        },
                        label = "tab",
                    ) { current ->
                        when (current) {
                            NavTab.Functions -> FunctionsScreen(
                                onOpenFeature = { detailId = it.id },
                            )

                            NavTab.Settings -> SettingsScreen()
                        }
                    }
                }

                BottomNav(
                    current = tab,
                    onSelect = { tabName = it.name },
                    modifier = Modifier
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 14.dp),
                )
            }
        } else {
            FeatureDetail(
                id = detail,
                activity = activity,
                store = store,
                timetableStore = timetableStore,
                onBack = { detailId = null },
            )
        }
    }
}

/** 功能详情：顶栏返回 + 具体功能内容 */
@Composable
private fun FeatureDetail(
    id: String,
    activity: ComponentActivity,
    store: UrlStore,
    timetableStore: TimetableStore,
    onBack: () -> Unit,
) {
    val feature = featureList.firstOrNull { it.id == id }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Spacer(Modifier.height(14.dp))
        GlassTopBar(
            title = feature?.name ?: "功能",
            subtitle = feature?.desc,
            onBack = onBack,
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f)) {
            when (id) {
                "nfc" -> SignScreen(activity = activity, store = store)
                "timetable" -> TimetableScreen(store = timetableStore)
                else -> Unit
            }
        }
    }
}
