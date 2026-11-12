package com.estimatefaster.audioforge.uipreview.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class ChapterStatus { Done, Generating, Pending, Failed }

data class Segment(
    val index: Int,
    val role: String,
    val text: String,
    val emotion: String? = null,
    val hasAudio: Boolean = true,
    val durationSec: Int = 8,
)

data class Chapter(
    val id: Long,
    val number: Int,
    val title: String,
    val status: ChapterStatus,
    val segments: List<Segment> = emptyList(),
    val wordCount: Int = 0,
) {
    val durationSec: Int get() = segments.sumOf { it.durationSec }
    val generatedCount: Int get() = segments.count { it.hasAudio }
}

data class Character(
    val name: String,
    val gender: String,
    val importance: String,
    val voice: String?,
    val locked: Boolean = false,
    val aliases: List<String> = emptyList(),
)

data class Book(
    val id: Long,
    val title: String,
    val author: String,
    val sourceName: String,
    val wordCount: Int,
    val updatedLabel: String,
    val chapters: List<Chapter>,
    val characters: List<Character> = emptyList(),
) {
    val completedChapters: Int get() = chapters.count { it.status == ChapterStatus.Done }
    val progress: Float
        get() = if (chapters.isEmpty()) 0f else completedChapters.toFloat() / chapters.size
    val segmentCount: Int get() = chapters.sumOf { it.segments.size }
    val totalDurationSec: Int get() = chapters.sumOf { it.durationSec }
    val isGenerating: Boolean get() = chapters.any { it.status == ChapterStatus.Generating }
}

data class RunningTask(
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterCount: Int,
    val percent: Int,
    val etaLabel: String,
    val queued: Int,
)

data class StorageSummary(
    val usedLabel: String,
    val audioLabel: String,
    val importLabel: String,
    val exportLabel: String,
    val fileCount: Int,
)

fun formatTime(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun formatDuration(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    return when {
        hours > 0 -> "${hours} 小时 ${minutes} 分"
        else -> "${minutes} 分钟"
    }
}

fun formatWordCount(count: Int): String = String.format(Locale.US, "%,d", count)

/** 演示数据：全部为原创占位内容，不代表真实作品。 */
object FakeData {

    private fun seg(i: Int, role: String, text: String, emotion: String?, hasAudio: Boolean = true, dur: Int = 8) =
        Segment(index = i, role = role, text = text, emotion = emotion, hasAudio = hasAudio, durationSec = dur)

    private val chapterOneSegments = listOf(
        seg(0, "旁白", "那一年，凤阳的天像是被谁按住了，一连四个月没有落下一滴雨。", "平静", dur = 9),
        seg(1, "朱五四", "重八，把西坡那两亩地也翻了吧。", "疲惫", dur = 6),
        seg(2, "朱元璋", "爹，地都裂了，翻出来也是土。", "倔强", dur = 7),
        seg(3, "旁白", "朱五四没有回头。他蹲在田埂上，用粗糙的手指捻着一把干土，土从指缝里漏下去，像漏掉的日子。", "平静", dur = 12),
        seg(4, "朱五四", "土是干的，人也得活着。翻了，好歹还有个念想。", "低沉", dur = 8),
        seg(5, "旁白", "少年没有再说话。他扛起锄头往西坡走，日头把他的影子压得很短，短到几乎贴着地面。", "平静", dur = 11),
        seg(6, "刘德", "朱家的小子，站住。你爹欠我的三斗米，什么时候还？", "严厉", dur = 9),
        seg(7, "朱元璋", "刘老爷，今年颗粒无收，等来年——", "克制", dur = 7),
        seg(8, "刘德", "来年？我只认得今年的账。", "讥讽", dur = 6),
        seg(9, "旁白", "那天傍晚，朱元璋第一次记住了“账”这个字。它比饥饿更冷，也比饥饿更长。", "平静", dur = 10),
        seg(10, "旁白", "许多年后，他坐在应天的宫殿里，仍然会梦见西坡上那块裂开的土地。", "追忆", dur = 9),
        seg(11, "朱元璋", "都记着。一笔一笔，都记着。", "低沉", dur = 6),
    )

    private val fillerLines = listOf(
        "风从北边来，带着沙，也带着远处军营的号角声。",
        "他把最后一口干粮掰成两半，一半留给母亲，一半揣回怀里。",
        "寺里的钟声每天敲三次，敲到第三次，天就该黑了。",
        "有人在城门口贴了榜文，识字的人围了一圈，不识字的人围了两圈。",
        "那一夜他没有睡，听着屋顶的雨声，把明天的路在心里走了一遍。",
        "她只说了一句：活着回来。剩下的都咽回了肚子里。",
        "刀出鞘的声音很轻，轻得像一声叹息。",
        "江面上的雾还没散，船就已经离了岸。",
    )

    private fun fillerChapter(id: Long, number: Int, title: String, status: ChapterStatus): Chapter {
        val count = if (status == ChapterStatus.Pending) 0 else 10 + (number % 4)
        val segments = (0 until count).map { i ->
            seg(
                i = i,
                role = if (i % 3 == 0) "旁白" else if (i % 3 == 1) "朱元璋" else "朱五四",
                text = fillerLines[(number + i) % fillerLines.size],
                emotion = if (i % 2 == 0) "平静" else "低沉",
                hasAudio = status == ChapterStatus.Done || (status == ChapterStatus.Generating && i < count / 2),
                dur = 7 + (i % 4) * 2,
            )
        }
        return Chapter(
            id = id,
            number = number,
            title = title,
            status = status,
            segments = segments,
            wordCount = 5200 + number * 137,
        )
    }

    private val chapterTitles = listOf(
        "童年", "旱年", "皇觉寺", "行脚僧", "红巾", "投军",
        "新婚", "滁州", "渡江", "集庆", "兄弟", "北伐",
    )

    val characters = listOf(
        Character("朱元璋", "男", "主角", "沉稳男声 · 中文", locked = true, aliases = listOf("重八", "朱重八")),
        Character("朱五四", "男", "配角", "沧桑男声 · 中文"),
        Character("刘德", "男", "配角", "尖细男声 · 中文"),
        Character("郭子兴", "男", "配角", null),
        Character("马氏", "女", "配角", "温柔女声 · 中文"),
        Character("旁白", "未知", "旁白", "叙述男声 · 中文", locked = true),
        Character("徐达", "男", "龙套", null),
    )

    private val mingChapters: List<Chapter> = buildList {
        add(
            Chapter(
                id = 101,
                number = 1,
                title = "童年",
                status = ChapterStatus.Done,
                segments = chapterOneSegments,
                wordCount = 6120,
            ),
        )
        for (n in 2..12) {
            val status = when {
                n <= 5 -> ChapterStatus.Done
                n == 6 -> ChapterStatus.Generating
                n == 9 -> ChapterStatus.Failed
                else -> ChapterStatus.Pending
            }
            add(fillerChapter(100L + n, n, chapterTitles[n - 1], status))
        }
    }

    val books: List<Book> = listOf(
        Book(
            id = 1,
            title = "明朝那些事儿",
            author = "当年明月",
            sourceName = "明朝那些事儿.txt",
            wordCount = 96400,
            updatedLabel = "2 小时前",
            chapters = mingChapters,
            characters = characters,
        ),
        Book(
            id = 2,
            title = "山海食话",
            author = "本地草稿",
            sourceName = "山海食话.txt",
            wordCount = 54200,
            updatedLabel = "昨天",
            chapters = (1..8).map { n ->
                fillerChapter(200L + n, n, "第 ${n} 味", ChapterStatus.Done)
            },
            characters = characters.take(4),
        ),
        Book(
            id = 3,
            title = "长夜将明",
            author = "本地草稿",
            sourceName = "长夜将明.txt",
            wordCount = 188000,
            updatedLabel = "3 天前",
            chapters = (1..24).map { n ->
                Chapter(id = 300L + n, number = n, title = "第 $n 章", status = ChapterStatus.Pending, wordCount = 7800)
            },
        ),
        Book(
            id = 4,
            title = "机械之心",
            author = "本地草稿",
            sourceName = "机械之心.txt",
            wordCount = 33900,
            updatedLabel = "刚刚",
            chapters = (1..6).map { n ->
                when (n) {
                    1, 2 -> fillerChapter(400L + n, n, "第 $n 章", ChapterStatus.Done)
                    3 -> fillerChapter(400L + n, n, "第 $n 章", ChapterStatus.Generating)
                    else -> Chapter(id = 400L + n, number = n, title = "第 $n 章", status = ChapterStatus.Pending)
                }
            },
            characters = characters.take(3),
        ),
    )

    val runningTask = RunningTask(
        bookTitle = "明朝那些事儿",
        chapterIndex = 6,
        chapterCount = 12,
        percent = 62,
        etaLabel = "约 4 分钟",
        queued = 2,
    )

    val storage = StorageSummary(
        usedLabel = "1.8 GB",
        audioLabel = "1.4 GB",
        importLabel = "260 MB",
        exportLabel = "140 MB",
        fileCount = 1284,
    )

    fun book(id: Long): Book = books.firstOrNull { it.id == id } ?: books.first()

    fun chapter(bookId: Long, chapterId: Long): Chapter? =
        book(bookId).chapters.firstOrNull { it.id == chapterId }

    val voices = listOf(
        "沉稳男声" to "中文 · 男声 · 适合旁白与中年角色",
        "叙述男声" to "中文 · 男声 · 语速偏慢",
        "沧桑男声" to "中文 · 男声 · 沙哑质感",
        "清亮青年" to "中文 · 男声 · 年轻角色",
        "尖细男声" to "中文 · 男声 · 反派",
        "温柔女声" to "中文 · 女声 · 叙述",
        "清冷女声" to "中文 · 女声 · 少女",
        "童声" to "中文 · 中性 · 少年",
        "英文旁白" to "英文 · 男声 · 叙述",
        "日文少女" to "日文 · 女声 · 少女",
    )
}

/** 播放器预览状态机：句级时间轴、倍速、睡眠定时、连播。 */
@Stable
class PlayerState(initialBook: Book = FakeData.books.first()) {
    var book by mutableStateOf(initialBook)
        private set

    var chapterIndex by mutableIntStateOf(4)
        private set

    var segmentIndex by mutableIntStateOf(3)
        private set

    var elapsedSec by mutableFloatStateOf(26f)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var speed by mutableFloatStateOf(1.0f)
        private set

    var sleepMinutes by mutableIntStateOf(0)
        private set

    var continuous by mutableStateOf(true)
        private set

    var sleepRemainingSec by mutableIntStateOf(0)
        private set

    val chapters get() = book.chapters
    val chapter get() = chapters[chapterIndex.coerceIn(0, chapters.lastIndex)]
    val segments get() = chapter.segments

    val currentSegment get() = segments.getOrNull(segmentIndex)
    val totalSec get() = chapter.durationSec.coerceAtLeast(1)
    val progress get() = (elapsedSec / totalSec).coerceIn(0f, 1f)
    val hasAudio get() = currentSegment?.hasAudio == true

    fun toggle() {
        if (!hasAudio) return
        isPlaying = !isPlaying
    }

    fun playSegment(index: Int) {
        val target = segments.getOrNull(index) ?: return
        segmentIndex = index
        elapsedSec = offsetOf(index).toFloat()
        isPlaying = target.hasAudio
    }

    fun seekToFraction(fraction: Float) {
        elapsedSec = (fraction.coerceIn(0f, 1f) * totalSec)
        segmentIndex = segmentAt(elapsedSec.toInt())
    }

    fun nextSegment() {
        if (segmentIndex < segments.lastIndex) {
            segmentIndex += 1
            elapsedSec = offsetOf(segmentIndex).toFloat()
        }
    }

    fun previousSegment() {
        if (elapsedSec - offsetOf(segmentIndex) > 3f) {
            elapsedSec = offsetOf(segmentIndex).toFloat()
        } else if (segmentIndex > 0) {
            segmentIndex -= 1
            elapsedSec = offsetOf(segmentIndex).toFloat()
        }
    }

    fun nextChapter() {
        if (chapterIndex < chapters.lastIndex) {
            chapterIndex += 1
            segmentIndex = 0
            elapsedSec = 0f
        }
    }

    fun previousChapter() {
        if (chapterIndex > 0) {
            chapterIndex -= 1
            segmentIndex = 0
            elapsedSec = 0f
        }
    }

    fun selectChapter(id: Long) {
        val index = chapters.indexOfFirst { it.id == id }
        if (index >= 0) {
            chapterIndex = index
            segmentIndex = 0
            elapsedSec = 0f
        }
    }

    fun applySpeed(value: Float) {
        speed = value
    }

    fun applySleepMinutes(value: Int) {
        sleepMinutes = value
        sleepRemainingSec = value * 60
    }

    fun applyContinuous(value: Boolean) {
        continuous = value
    }

    /** 由 UI 的 ticker 调用。 */
    fun tick(deltaSec: Float) {
        if (!isPlaying) return
        if (sleepMinutes > 0) {
            sleepRemainingSec = (sleepRemainingSec - deltaSec).toInt()
            if (sleepRemainingSec <= 0) {
                sleepMinutes = 0
                sleepRemainingSec = 0
                isPlaying = false
                return
            }
        }
        elapsedSec += deltaSec * speed
        if (elapsedSec >= offsetOf(segmentIndex) + (currentSegment?.durationSec ?: 1)) {
            if (segmentIndex < segments.lastIndex) {
                segmentIndex += 1
            } else if (continuous && chapterIndex < chapters.lastIndex) {
                chapterIndex += 1
                segmentIndex = 0
                elapsedSec = 0f
            } else {
                isPlaying = false
                elapsedSec = totalSec.toFloat()
            }
        }
    }

    private fun offsetOf(index: Int): Int =
        segments.take(index).sumOf { it.durationSec }

    private fun segmentAt(sec: Int): Int {
        var acc = 0
        segments.forEachIndexed { i, s ->
            acc += s.durationSec
            if (sec < acc) return i
        }
        return segments.lastIndex.coerceAtLeast(0)
    }
}
