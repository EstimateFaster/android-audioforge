package com.estimatefaster.audioforge.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.core.settings.AppSettings
import com.estimatefaster.audioforge.core.settings.SecureKeyStore
import com.estimatefaster.audioforge.core.voice.VoicePool
import com.estimatefaster.audioforge.domain.jobs.GenerationGraph
import com.estimatefaster.audioforge.feature.onboarding.OnboardingViewModel
import com.estimatefaster.audioforge.feature.settings.SettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 接线 1/2：AppContainer 懒加载装配 + AppViewModelFactory 注册表的 Robolectric 冒烟。
 *
 * 这些用例刻意**不触碰** `jobQueue` / `batchRunner` / `sweepRunning` /
 * `recoveryCoordinator`：单元测试没有初始化 WorkManager，能构造容器并在不初始化
 * 网络/WorkManager 的情况下创建 ViewModel，正好验证「所有依赖懒加载」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppContainerWiringTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun container_exposes_lazy_dependencies_without_initializing_work_manager() {
        val context = context()
        val container = AppContainer(context)
        try {
            // 设置 / Key / 语音资产
            assertNotNull(container.dataStore)
            assertNotNull(container.settingsRepository)
            assertNotNull(container.secureKeyStore)
            assertTrue(
                "音色池至少 %d 类".format(VoicePool.MIN_CATEGORIES),
                container.voicePool.size >= VoicePool.MIN_CATEGORIES,
            )
            assertTrue(container.promptAssets.templates.script.isNotBlank())
            assertNotNull(container.promptRenderer)

            // 仓库（也会懒建数据库）
            assertNotNull(container.projectRepository)
            assertNotNull(container.chapterRepository)
            assertNotNull(container.characterRepository)
            assertNotNull(container.segmentRepository)
            assertNotNull(container.jobRepository)
            assertNotNull(container.tuningRepository)

            // core 服务与用例
            assertNotNull(container.audioStorage)
            assertNotNull(container.exportManager)
            assertNotNull(container.storageMaintenance)
            assertNotNull(container.importNovelUseCase)
            assertNotNull(container.audioPostProcessor)
            assertNotNull(container.chapterStitcher)
            assertNotNull(container.jobWatchdog)

            // 同一个 lazy 只创建一次
            assertSame(container.projectRepository, container.projectRepository)
            assertSame(container.audioPostProcessor, container.audioPostProcessor)

            // 仓库确实指向容器里的同一个数据库（查询走 Room 的 IO 执行器）。
            assertNotNull(
                "项目仓库应能在容器数据库上查询",
                runBlocking { container.projectRepository.listProjects() },
            )

            // 执行图在构造容器时就已装配（只写 lambda，不解析重依赖）
            assertSame(GenerationGraph, container.generationGraph)
            assertNotNull(GenerationGraph.executorFactory)
        } finally {
            // 前面的断言会把数据库懒建出来；显式关闭，避免测试之间留连接。
            container.database.close()
        }
    }

    @Test
    fun data_store_is_a_single_instance_shared_by_every_container() {
        val context = context()
        val first = AppContainer(context)
        val second = AppContainer(context)

        assertSame(first.dataStore, second.dataStore)
        assertSame(first.dataStore, first.dataStore)
    }

    @Test
    fun settings_repository_reads_defaults_through_the_shared_data_store() = runBlocking {
        val container = AppContainer(context())
        val settings = withTimeout(SETTINGS_TIMEOUT_MS) {
            container.settingsRepository.settings.first()
        }
        assertEquals(AppSettings.DEFAULT, settings)
    }

    @Test
    fun api_key_clients_fail_with_clear_errors_when_keys_are_missing() = runBlocking {
        val container = AppContainer(context())
        container.secureKeyStore.clear(SecureKeyStore.LLM_API_KEY)
        container.secureKeyStore.clear(SecureKeyStore.FISH_API_KEY)

        val llmError = try {
            container.llmClient()
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue("缺少 LLM Key 应抛 MissingApiKeyException：$llmError", llmError is MissingApiKeyException)
        assertTrue(llmError!!.message.orEmpty().contains(SecureKeyStore.LLM_API_KEY))

        val fishError = try {
            container.fishTtsClient()
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue("缺少 Fish Key 应抛 MissingApiKeyException：$fishError", fishError is MissingApiKeyException)
        assertTrue(fishError!!.message.orEmpty().contains(SecureKeyStore.FISH_API_KEY))
    }

    @Test
    fun factory_creates_data_store_backed_view_models_without_network_or_work_manager() {
        val container = AppContainer(context())
        val factory = AppViewModelFactory(container)

        // 两个示例 VM 只依赖默认注册表里的 DataStore / KeyStore，不碰 Room/WorkManager。
        assertNotNull(factory.create(SettingsViewModel::class.java))
        assertNotNull(factory.create(OnboardingViewModel::class.java))

        // 运行时追加注册也不需要重依赖。
        assertNotNull(factory.register(UnusedViewModel::class.java) { UnusedViewModel() })
        assertNotNull(factory.create(UnusedViewModel::class.java))
    }

    private class UnusedViewModel : androidx.lifecycle.ViewModel()

    private companion object {
        const val SETTINGS_TIMEOUT_MS = 10_000L
    }
}
