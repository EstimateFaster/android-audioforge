package com.estimatefaster.audioforge.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.feature.project.ChapterListViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 接线 1/2：AppViewModelFactory 的注册表与 CreationExtras 契约。
 *
 * 容器装配本身的冒烟在 [AppContainerWiringTest]；这里只验证「注册 / 覆盖 / 缺参报错」，
 * 不重复创建 Room 数据库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppViewModelFactoryTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun unregistered_view_model_is_rejected_with_clear_error() {
        val factory = AppViewModelFactory(AppContainer(context()))

        val error = runCatching { factory.create(UnregisteredViewModel::class.java) }
            .exceptionOrNull()

        assertTrue("未注册的类应抛 IllegalArgumentException：$error", error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("未注册的 ViewModel"))
    }

    @Test
    fun registered_view_model_needing_parameters_reports_the_missing_extra() {
        val factory = AppViewModelFactory(AppContainer(context()))

        val error = runCatching { factory.create(ChapterListViewModel::class.java) }
            .exceptionOrNull()

        assertTrue("缺 novelId 应抛 IllegalArgumentException：$error", error is IllegalArgumentException)
        val message = error?.message.orEmpty()
        assertTrue(message.contains("novelId"))
        assertTrue(message.contains("CreationExtras"))
    }

    @Test
    fun register_passes_creation_extras_to_the_creator() {
        val factory = AppViewModelFactory(AppContainer(context()))
        factory.register(ExtrasViewModel::class.java) { extras ->
            ExtrasViewModel(extras[AppViewModelKeys.NOVEL_ID] ?: -1L)
        }

        val extras = MutableCreationExtras().apply { this[AppViewModelKeys.NOVEL_ID] = 42L }

        assertEquals(42L, factory.create(ExtrasViewModel::class.java, extras).novelId)
        // 无 extras 的默认入口等价于 CreationExtras.Empty，交给 creator 自己的缺省。
        assertEquals(-1L, factory.create(ExtrasViewModel::class.java).novelId)
    }

    @Test
    fun register_returns_this_for_chaining_and_replaces_existing_entry() {
        val factory = AppViewModelFactory(AppContainer(context()))

        val chained = factory
            .register(ExtrasViewModel::class.java) { ExtrasViewModel(1L) }
            .register(ExtrasViewModel::class.java) { ExtrasViewModel(2L) }

        assertEquals(2L, chained.create(ExtrasViewModel::class.java).novelId)
    }

    private class UnregisteredViewModel : ViewModel()

    private class ExtrasViewModel(val novelId: Long) : ViewModel()
}
