package com.estimatefaster.audioforge.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider
import com.estimatefaster.audioforge.core.voice.VoicePool
import com.estimatefaster.audioforge.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P2-02：AppContainer 懒加载依赖 + AppViewModelFactory 注册表。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppViewModelFactoryTest {

    @Test
    fun factory_creates_registered_view_model_with_container_dependencies() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(AppDatabase.DB_NAME)
        val container = AppContainer(context)
        try {
            assertTrue(
                "音色池至少 %d 类".format(VoicePool.MIN_CATEGORIES),
                container.voicePool.size >= VoicePool.MIN_CATEGORIES,
            )
            assertTrue(container.promptAssets.templates.script.isNotBlank())
            assertNotNull(container.promptRenderer)

            val factory = AppViewModelFactory(container)
            val viewModel = factory.create(ProjectListViewModel::class.java)
            assertSame(container.projectRepository, viewModel.projectRepository)
            assertTrue(viewModel.projects.first().isEmpty())
        } finally {
            container.database.close()
            context.deleteDatabase(AppDatabase.DB_NAME)
        }
    }

    @Test
    fun factory_rejects_unregistered_view_model() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = AppViewModelFactory(AppContainer(context))

        var thrown: Throwable? = null
        try {
            factory.create(UnregisteredViewModel::class.java)
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown is IllegalArgumentException)
    }

    private class UnregisteredViewModel : ViewModel()
}
