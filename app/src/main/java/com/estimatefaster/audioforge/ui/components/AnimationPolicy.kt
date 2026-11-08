package com.estimatefaster.audioforge.ui.components

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** 系统“移除动画”把 animator duration scale 设为 0；任何大于 0 的值都视为开启动画。 */
fun animationsEnabled(animatorDurationScale: Float): Boolean =
    animatorDurationScale > 0f

/**
 * 读取并监听 `Settings.Global.ANIMATOR_DURATION_SCALE`。
 *
 * 只用于决定装饰性无限动画是否播放（例如状态胶囊的呼吸圆点）；一次性过渡动画
 * 由 Compose 自身的 MotionDurationScale 处理。用户在系统设置里切换“移除动画”后，
 * ContentObserver 会更新状态并触发重组，不需要重启页面。
 */
@Composable
fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember(context) {
        mutableStateOf(animationsEnabled(readAnimatorDurationScale(context)))
    }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = animationsEnabled(readAnimatorDurationScale(context))
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    return enabled
}

private fun readAnimatorDurationScale(context: Context): Float =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
