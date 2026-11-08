package com.estimatefaster.audioforge.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * 队列模型 → Media3 `MediaItem` 的转换（P5-08）。
 *
 * 章节位置/片段 index 写进 `MediaMetadata.extras`：服务在锁屏、通知、蓝牙切章后
 * 仍能知道“当前在第几章第几段”，不依赖控制器再传一次状态。
 */
internal object PlaybackMediaItems {

    /** 把一章的可播片段转成 MediaItem 列表；列表顺序即播放顺序。 */
    fun of(chapter: PlaybackQueueChapter, chapterIndex: Int): List<MediaItem> =
        chapter.items.map { item -> of(item, chapterIndex) }

    /** 单条片段转换。 */
    fun of(item: PlaybackQueueItem, chapterIndex: Int): MediaItem {
        val extras = Bundle().apply {
            putInt(PlaybackCommands.EXTRA_CHAPTER_INDEX, chapterIndex)
            putInt(PlaybackCommands.EXTRA_SEGMENT_INDEX, item.segmentIndex)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.subtitle)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(Uri.parse(item.uri))
            .setMediaMetadata(metadata)
            .build()
    }

    /** 从 MediaItem 读章节位置；缺失返回 null。 */
    fun chapterIndexOf(item: MediaItem?): Int? =
        item?.mediaMetadata?.extras?.let { extras ->
            if (extras.containsKey(PlaybackCommands.EXTRA_CHAPTER_INDEX)) {
                extras.getInt(PlaybackCommands.EXTRA_CHAPTER_INDEX, -1).takeIf { it >= 0 }
            } else {
                null
            }
        }

    /** 从 MediaItem 读片段 index；缺失返回 null。 */
    fun segmentIndexOf(item: MediaItem?): Int? =
        item?.mediaMetadata?.extras?.let { extras ->
            if (extras.containsKey(PlaybackCommands.EXTRA_SEGMENT_INDEX)) {
                extras.getInt(PlaybackCommands.EXTRA_SEGMENT_INDEX, -1).takeIf { it >= 0 }
            } else {
                null
            }
        }
}
