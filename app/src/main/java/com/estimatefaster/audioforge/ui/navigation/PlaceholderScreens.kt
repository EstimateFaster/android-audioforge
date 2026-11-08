package com.estimatefaster.audioforge.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.estimatefaster.audioforge.R

/** P4-01 占位页：后续 P4-03/P4-08/P4-09 替换为真实页面。 */

@Composable
internal fun ProjectsPlaceholderScreen(
    onOpenProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_projects),
        body = stringResource(R.string.nav_placeholder_body),
        modifier = modifier,
    ) {
        FilledTonalButton(onClick = onOpenProject) {
            Text(text = stringResource(R.string.nav_open_sample_project))
        }
    }
}

@Composable
internal fun TasksPlaceholderScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_tasks),
        body = stringResource(R.string.nav_placeholder_body),
        modifier = modifier,
    )
}

@Composable
internal fun SettingsPlaceholderScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_settings),
        body = stringResource(R.string.nav_placeholder_body),
        modifier = modifier,
    )
}

@Composable
internal fun ProjectDetailPlaceholderScreen(
    novelId: Long,
    onOpenChapters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_project_detail_title),
        body = stringResource(R.string.nav_project_detail_body, novelId),
        modifier = modifier,
    ) {
        FilledTonalButton(onClick = onOpenChapters) {
            Text(text = stringResource(R.string.nav_project_detail_chapters))
        }
    }
}

@Composable
internal fun ChapterListPlaceholderScreen(
    novelId: Long,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_chapter_list_title),
        body = stringResource(R.string.nav_chapter_list_body, novelId),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScreen(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(text = title) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
            )
            extra()
        }
    }
}
