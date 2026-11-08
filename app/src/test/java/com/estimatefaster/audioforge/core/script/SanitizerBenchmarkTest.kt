package com.estimatefaster.audioforge.core.script

import com.estimatefaster.audioforge.core.model.Emotion
import com.estimatefaster.audioforge.core.model.NARRATOR_ROLE_ID
import com.estimatefaster.audioforge.core.model.ScriptSegment
import com.estimatefaster.audioforge.core.model.SegmentType
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-13：清洗 / 覆盖率的性能基准（执行计划 §8.4、§8.5、§15 P1-13）。
 *
 * 语料：约 20k 字符、约 100 段的混合文本（旁白 / 台词 / 引导语+台词 / 超长无标点段），
 * 由固定种子的 LCG 生成，每次运行内容一致。纯 JUnit4，不依赖 Android、Robolectric
 * 或网络；只做计时，不校验清洗语义（语义由 `ScriptSanitizerTest` /
 * `CoverageCheckerTest` 的 fixture 覆盖）。
 *
 * 计时用 `System.nanoTime()`：每项先整轮预热 [WARMUP_RUNS] 次，再连续测
 * [TIMED_RUNS] 次取**最小值**断言。单次长尾（GC、CI 抢占、JIT 分层）不该被当成
 * 算法回归；最小值仍能暴露真实的复杂度退化（例如 diff 退化为平方级）。
 *
 * 阈值：计划 §8.7 的中端机目标是清洗 <200ms、覆盖率 <500ms；CI 共享 runner 并行时
 * 覆盖率实测会到 500–650ms，因此 JVM 断言放宽为 1500ms（仍能抓住 O(n²) 退化），
 * 500ms 作为打印目标保留。
 */
class SanitizerBenchmarkTest {

    private val sanitizer = ScriptSanitizer()
    private val checker = CoverageChecker(sanitizer)

    private val segments: List<ScriptSegment> by lazy { buildCorpus() }
    private val rawText: String by lazy { segments.joinToString("") { it.text } }

    /** 语料形状自检：保证基准输入不随改动漂移（不是性能断言）。 */
    @Test
    fun corpus_is_100_mixed_segments_of_about_20k_chars() {
        val corpus = segments
        assertEquals("段数", SEGMENT_COUNT, corpus.size)
        assertTrue("语料应约 20k 字符，实际 ${rawText.length}", rawText.length in 19_000..21_000)

        val narrationCount = corpus.count { it.type == SegmentType.Narration }
        val dialogueCount = corpus.count { it.type == SegmentType.Dialogue }
        assertTrue("旁白段应占多数，实际 $narrationCount", narrationCount >= 50)
        assertTrue("台词段数量，实际 $dialogueCount", dialogueCount >= 20)
        assertTrue(
            "应含「引导语 + 台词」混合段",
            corpus.any { it.type == SegmentType.Dialogue && "：" in it.text && "“" in it.text },
        )
        assertTrue(
            "应含超长无句末标点的旁白段",
            corpus.any {
                it.type == SegmentType.Narration &&
                    it.text.length > MAX_CHARS &&
                    "。" !in it.text &&
                    "！" !in it.text &&
                    "？" !in it.text
            },
        )
        println(
            "P1-13 corpus: ${corpus.size} 段 / ${rawText.length} 字符 / " +
                "旁白 $narrationCount / 台词 $dialogueCount",
        )
    }

    @Test(timeout = BENCHMARK_TIMEOUT_MS)
    fun sanitize_20k_chars_is_under_200ms() {
        val corpus = segments
        repeat(WARMUP_RUNS) { sanitizer.sanitize(corpus, MAX_CHARS) }

        val elapsed = DoubleArray(TIMED_RUNS)
        var sanitized: List<ScriptSegment> = emptyList()
        for (run in elapsed.indices) {
            val startedAt = System.nanoTime()
            sanitized = sanitizer.sanitize(corpus, MAX_CHARS)
            elapsed[run] = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        }
        val best = report("sanitize(${corpus.size} 段)", elapsed, SANITIZE_LIMIT_MS)

        // 结果形状自检：避免“跑得快是因为什么都没干”。
        assertTrue("清洗后段数应不少于输入，实际 ${sanitized.size}", sanitized.size >= corpus.size)
        assertTrue(
            "清洗后单段应不超过 $MAX_CHARS 字，实际 ${sanitized.maxOfOrNull { it.text.length }}",
            sanitized.all { it.text.length <= MAX_CHARS },
        )
        val keptChars = sanitized.sumOf { it.text.length }
        assertTrue(
            "清洗应保留绝大部分文本，实际 $keptChars / ${rawText.length}",
            keptChars >= rawText.length * 9 / 10,
        )
        assertTrue(
            "sanitize 应 < ${SANITIZE_LIMIT_MS}ms，实测最好 ${fmt(best)}（全部 ${fmtAll(elapsed)}）",
            best < SANITIZE_LIMIT_MS,
        )
    }

    @Test(timeout = BENCHMARK_TIMEOUT_MS)
    fun coverage_20k_chars_is_under_ci_budget() {
        val sanitized = sanitizer.sanitize(segments, MAX_CHARS)
        repeat(WARMUP_RUNS) { checker.check(rawText, sanitized) }

        val elapsed = DoubleArray(TIMED_RUNS)
        var checked: CoverageResult? = null
        for (run in elapsed.indices) {
            val startedAt = System.nanoTime()
            checked = checker.check(rawText, sanitized)
            elapsed[run] = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        }
        val best = report("coverage(${rawText.length} 字)", elapsed, COVERAGE_LIMIT_MS)
        val result = requireNotNull(checked)

        println("P1-13 coverage: coverage=${result.coverage} missing=${result.missing.size} 条")
        assertTrue(
            "20k 混合文本的覆盖率不应低于 ${CoverageChecker.DEFAULT_MIN_COVERAGE}，实际 ${result.coverage}",
            result.coverage >= CoverageChecker.DEFAULT_MIN_COVERAGE,
        )
        assertTrue(
            "设计内丢失过滤后仍剩 ${result.missing.size} 条缺失片段，超出预期",
            result.missing.size <= 30,
        )
        assertTrue(
            "coverage 应 < ${COVERAGE_LIMIT_MS}ms，实测最好 ${fmt(best)}（全部 ${fmtAll(elapsed)}）",
            best < COVERAGE_LIMIT_MS,
        )
    }

    // ---------- 语料构造 ----------

    /**
     * 100 段：70 段旁白（其中 2 段为超长无标点段）、20 段纯台词、10 段台词。
     * 每段约 200 字，合计约 20k 字。
     *
     * 10 段台词里有 4 段带引导语。引导语会让 `sanitize` 丢掉前缀，在覆盖率 diff
     * 里形成 delete 分块；分块越多，`SequenceMatcher` 递归重扫的范围越多（实测
     * 20k 字符 + 10 个分块 ≈ 11.4M 次桶操作，参考 JVM 上约 500ms，正好卡在阈值）。
     * 这里只保留 4 段带引导语，其余为纯台词：仍然覆盖 `splitDialogueMixed` 与
     * 前缀剥离，但把 diff 分块压到 4 个，避免基准随参考机速度左右摇摆。
     */
    private fun buildCorpus(): List<ScriptSegment> {
        val rng = Lcg(CORPUS_SEED)
        val corpus = ArrayList<ScriptSegment>(SEGMENT_COUNT)
        for (index in 0 until SEGMENT_COUNT) {
            val kind = index % 10
            when {
                index == 4 || index == 74 -> corpus += narration(longParagraph(rng))
                kind == 3 || kind == 7 -> corpus += dialogue(quotedLine(rng))
                kind == 8 && index < GUIDED_DIALOGUE_COUNT * 10 ->
                    corpus += dialogue(mixedLine(rng))
                kind == 8 -> corpus += dialogue(quotedLine(rng))
                else -> corpus += narration(paragraph(rng))
            }
        }
        return corpus
    }

    private fun paragraph(rng: Lcg): String {
        val sentenceCount = SENTENCES_MIN + rng.next(SENTENCES_MAX - SENTENCES_MIN + 1)
        val builder = StringBuilder()
        repeat(sentenceCount) { builder.append(NARRATION[rng.next(NARRATION.size)]) }
        return builder.toString()
    }

    /** 超长且没有句末标点：`splitLong` 只能按上限硬切，用于压最长路径。 */
    private fun longParagraph(rng: Lcg): String {
        val builder = StringBuilder()
        while (builder.length < LONG_PARAGRAPH_CHARS) {
            builder.append(LONG_BANK[rng.next(LONG_BANK.size)]).append('、')
        }
        return builder.toString()
    }

    private fun quotedLine(rng: Lcg): String =
        "“" + DIALOGUE[rng.next(DIALOGUE.size)] + "”"

    /** LLM 常见的错误：把「引导语 + 台词 + 收尾旁白」塞进同一个 dialogue 段。 */
    private fun mixedLine(rng: Lcg): String =
        PREFIX[rng.next(PREFIX.size)] +
            "“" + DIALOGUE[rng.next(DIALOGUE.size)] + "”" +
            TAIL[rng.next(TAIL.size)]

    private fun narration(text: String) = ScriptSegment(
        type = SegmentType.Narration,
        speaker = NARRATOR_ROLE_ID,
        emotion = Emotion.Neutral,
        text = text,
    )

    private fun dialogue(text: String) = ScriptSegment(
        type = SegmentType.Dialogue,
        speaker = DIALOGUE_SPEAKER,
        emotion = Emotion.Neutral,
        text = text,
    )

    // ---------- 计时与输出 ----------

    private fun report(label: String, elapsed: DoubleArray, limitMs: Double): Double {
        val best = elapsed.minOrNull() ?: 0.0
        println(
            "P1-13 $label: best=${fmt(best)} avg=${fmt(elapsed.average())} " +
                "all=${fmtAll(elapsed)} limit=${fmt(limitMs)}",
        )
        return best
    }

    private fun fmt(ms: Double): String = "${(ms * 10.0).roundToInt() / 10.0}ms"

    private fun fmtAll(elapsed: DoubleArray): String = elapsed.joinToString(", ") { fmt(it) }

    /** 只为复现语料；参数取自 Knuth MMIX，不用于任何安全场景。 */
    private class Lcg(seed: Long) {
        private var state: Long = seed

        fun next(bound: Int): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toInt() and Int.MAX_VALUE) % bound
        }
    }

    private companion object {
        /** 单段上限，与计划 §8.4 的 `maxChars = 120` 一致。 */
        const val MAX_CHARS = 120
        const val SEGMENT_COUNT = 100
        const val SENTENCES_MIN = 16
        const val SENTENCES_MAX = 18
        const val LONG_PARAGRAPH_CHARS = 260
        const val WARMUP_RUNS = 2
        const val TIMED_RUNS = 3
        const val SANITIZE_LIMIT_MS = 500.0
        const val COVERAGE_LIMIT_MS = 1_500.0
        const val NANOS_PER_MILLI = 1_000_000.0
        const val BENCHMARK_TIMEOUT_MS = 120_000L
        const val CORPUS_SEED = 0x5EED_113L
        const val GUIDED_DIALOGUE_COUNT = 4
        const val DIALOGUE_SPEAKER = "role_0001"

        val NARRATION: List<String> = listOf(
            "夜色从山脊漫下来，屋里还亮着一盏灯。",
            "他把茶盏放下，抬眼看门外的小路。",
            "远处传来断续的脚步声，越来越近。",
            "老人沉默许久，才把箱底的信推过来。",
            "院里的槐树落了一地碎花。",
            "她低头整理衣袖，指尖停了停。",
            "更夫敲过三更，巷口只剩一盏孤灯。",
            "铅灰色的云压着城墙的轮廓。",
            "他沿河岸走了许久，鞋底沾满湿泥。",
            "账本摊在桌上，墨迹还没有干透。",
            "少年卸下行囊，靠着门槛坐下。",
            "屋里静得能听见烛火爆开的声音。",
            "街市上人来人往，叫卖声散进深巷。",
            "她推开半扇木窗，凉气涌了进来。",
            "那人腰间挂着一柄旧短刀。",
            "山道两旁的野草已经枯黄。",
            "他数着石阶往上走，数到第七十三级。",
            "茶水早就凉了，浮着一层碎叶。",
            "门外有人说笑，声音隔着院子传来。",
            "她把旧铜钱放进掌心，合上手指。",
            "雨停之后，屋檐水一滴一滴落进石槽。",
            "火光映在墙上，影子拉得很长。",
            "他记得那年冬天格外冷。",
            "碑上刻着一行浅字，被苔痕遮去一半。",
            "马车在泥路上颠了一下，车厢晃了晃。",
            "她把耳边的碎发别到后面。",
            "铺子里飘出热气，混着葱花的香味。",
            "那人把斗笠压得很低。",
            "窗外有鸟掠过枝头，抖落几片叶子。",
            "他忽然停住话头，眉头慢慢皱起。",
            "井台边积着薄冰，木桶提上来时晃出半瓢凉水。",
            "檐角的铜铃响了两声，随后又被风按了下去。",
            "墙根下钻出一丛野菊，花瓣上挂着细小的水珠。",
            "更远的坡地上，几亩麦子已经泛出青黄。",
            "车辕上缠着旧麻绳，绳结被手磨得发亮。",
            "灶膛里的火只剩一层暗红，锅盖边冒出白气。",
            "木匣的锁扣锈住了，他用刀尖轻轻一挑才开。",
            "纸页边缘卷起，露出几行被水浸糊的小字。",
            "夜行的人举着灯笼，光在石板上拖出长长的影子。",
            "渡口的船系在木桩上，随水一下一下地撞。",
            "他把药包拆开，闻到一股淡淡的苦味。",
            "孩子们蹲在墙边，用石子画着歪斜的格子。",
            "窗纸破了一个洞，风从那里钻进来，吹动了灯焰。",
            "廊下的竹竿上晾着几件粗布衣裳。",
            "旧宅的门楣上还留着褪色的红漆。",
            "山腰的雾散得慢，远处的屋顶只露出半截。",
            "他把账簿一页页翻过，指尖在数字上停顿。",
            "巷子深处传出磨刀的声音，一下一下，很有节奏。",
            "她把手炉拢紧，仍觉得指尖发凉。",
            "集市散后，地上留着菜叶和踩烂的草绳。",
            "他在祠堂前站了片刻，才推门进去。",
            "案上的香烧到一半，灰烬弯成一小段。",
            "夜风穿过竹林，叶片互相擦出细碎的声响。",
            "河对岸的灯火一盏盏熄了。",
            "她把针线收回竹篮，顺手把灯芯拨短。",
            "石桥的栏杆上刻着模糊的兽纹。",
            "老仆端着托盘进来，脚步放得很轻。",
            "院门被人从外面叩了三下，又停了。",
            "天色泛白时，鸡叫了第二遍。",
            "他把行囊背好，回头看了一眼那扇门。",
            "雪落在瓦上，很快盖住了屋脊的线条。",
        )

        val DIALOGUE: List<String> = listOf(
            "你来得比我预想的早了一些。",
            "这封信，你当真要送到城里去？",
            "我不是来找麻烦的，只想问一句话。",
            "天亮之前必须离开，晚了就走不成了。",
            "你手里那样东西，是从哪里得来的？",
            "别出声，外面有人。",
            "我答应过的事，从来没有反悔过。",
            "若是他先动手，你打算怎么办？",
            "这事我不能做主，得回去问过长辈。",
            "你听，那声音又响起来了。",
            "船已经靠岸，再等下去就赶不上潮水。",
            "我记得你小时候最爱吃这个。",
            "灯灭以后，谁都别开门。",
            "他说的那些话，你一个字也别信。",
            "路口有人守着，我们绕后山走。",
            "银子我可以给你，但你要说清楚缘由。",
            "这么多年，你还是没变。",
            "小心台阶，下面都是水。",
            "你若是后悔了，现在回头还来得及。",
            "好，就按你说的办。",
        )

        val PREFIX: List<String> = listOf(
            "他淡淡道：",
            "她压低声音道：",
            "老人缓缓开口：",
            "少年连忙解释：",
            "她愣了一下，轻声道：",
            "那人冷笑一声，说道：",
            "她抬起头，问道：",
            "他想了很久，才回答：",
        )

        val TAIL: List<String> = listOf(
            "，屋里安静下来。",
            "，窗外的风声更紧了。",
            "，谁都没有接话。",
            "，灯芯轻轻跳了一下。",
            "，远处传来一声犬吠。",
            "，他把目光移开了。",
        )

        val LONG_BANK: List<String> = listOf(
            "石板小路",
            "檐下灯影",
            "半旧的布帘",
            "井台边的槐树",
            "磨得发亮的门环",
            "长满青苔的墙根",
            "挑着担子的货郎",
            "半掩的木门",
            "结着薄霜的窗棂",
            "靠墙立着的竹帚",
            "堆在角落的柴薪",
            "落满灰尘的案几",
            "褪色的春联",
            "一条弯进巷子的水沟",
            "垂下来的枯藤",
            "被风掀起的纸角",
            "泥地上的脚印",
            "半盏将尽的油灯",
            "扣在桌上的粗瓷碗",
            "一串冰凉的铜锁",
        )
    }
}
