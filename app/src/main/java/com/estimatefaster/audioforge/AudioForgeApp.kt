package com.estimatefaster.audioforge

import android.app.Application
import com.estimatefaster.audioforge.app.AppContainer

/**
 * App 入口。Phase 0 只挂最小 AppContainer；P2 会把仓库/客户端/用例接进来。
 */
class AudioForgeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (BuildConfig.DEBUG) {
            // P0-05 冒烟：Debug 启动即做一次 Room 写入/回读，结果进 logcat 与首页调试卡。
            container.startRoomProbe()
        }
    }
}
