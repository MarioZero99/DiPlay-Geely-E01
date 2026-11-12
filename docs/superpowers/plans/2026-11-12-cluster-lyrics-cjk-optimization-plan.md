# 吉利星瑞仪表盘歌词与媒体元数据日韩双语显示优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 彻底解决日文和韩文歌曲在吉利星瑞（E01）液晶仪表盘多媒体卡片上出现的“字符空白”、“间距过宽（空的很开）”、“双语超长截断（显示不全）”以及“歌词特征识别漏判”问题。

**Architecture:** 
1. 在 `XingruiClusterMediaFormatter` 中引入字符流规范化清洗器（折叠换行、替换全角空格及易缺失日韩标点符号）。
2. 在 `XingruiClusterMediaFormatter` 中增加 CJK 智能翻译提取与冗余注音剥离器（通过字符集排除法识别中文翻译，剥离纯假名/罗马音注音，支持括号与斜杠 `/` 双语提取，保护 `(Live)` 等版本信息）。
3. 优化 `CarPlayMediaKeys` 的歌词匹配规则（支持去除翻译后缀的模糊歌词匹配，扩充日、韩、英文制作前缀），并自适应装配 Line 1 和 Line 2 文本。

**Tech Stack:** Kotlin, JUnit 4, Regex, Unicode Blocks, Android Lollipop (API 22) E01 Automotive Cluster IPC.

## Global Constraints

- **最低兼容平台**：Android 5.1 (Lollipop MR1, API 22) 吉利 E01 / MT6735 芯片车机。
- **协议单字段上限**：单行文本绝对严禁超过 `MAX_CAN_TEXT_LENGTH = 31` 字符（东软 `CAN_SetInfo` 硬限制）。
- **仪表字库事实**：仪表盘硬件字库仅支持 ASCII + GBK 汉字，完全无韩文字库，日文假名不全；有中文翻译时优先保中文展示。
- **误伤防护原则**：严禁误删 `(Live)`、`(feat. xxx)`、`(伴奏)` 等中文歌曲的版本和副标题信息。
- **TDD 流程要求**：先写单测红灯，再写实现绿灯，确保所有现有单测与新边界测试全部 PASS。

---

### Task 1: 在 `XingruiClusterMediaFormatter` 中实现 CJK 文本清洗与字符规范化

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/XingruiClusterMediaFormatter.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/XingruiClusterMediaFormatterTest.kt`

**Interfaces:**
- Consumes: `raw: String?`
- Produces: `cleanClusterText(raw: String?): String`

- [ ] **Step 1: 编写字符清洗的失败单测**

在 `XingruiClusterMediaFormatterTest.kt` 中增加针对全角空格、换行符、日文长音/波浪号/中点等字符清洗的测试用例：
```kotlin
    @Test
    fun cleanClusterText_normalizesWhitespaceAndCjkPunctuation() {
        // 全角空格折叠与转半角
        assertEquals("A B", XingruiClusterMediaFormatter.cleanClusterText("A\u3000B"))
        // 换行符替换为空格
        assertEquals("Line1 Line2", XingruiClusterMediaFormatter.cleanClusterText("Line1\nLine2"))
        assertEquals("Line1 Line2", XingruiClusterMediaFormatter.cleanClusterText("Line1\r\nLine2"))
        // 日文波浪号与全角符号规整化
        assertEquals("夏~コクリコ坂から", XingruiClusterMediaFormatter.cleanClusterText("夏～コクリコ坂から"))
        assertEquals("A · B", XingruiClusterMediaFormatter.cleanClusterText("A・B"))
        // 多余空白折叠与首尾 trim
        assertEquals("Hello World", XingruiClusterMediaFormatter.cleanClusterText("  Hello   World  "))
    }
```

- [ ] **Step 2: 运行测试验证失败**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.instrument.XingruiClusterMediaFormatterTest.cleanClusterText_normalizesWhitespaceAndCjkPunctuation"`
预期：编译失败（`cleanClusterText` 未定义）。

- [ ] **Step 3: 实现 `cleanClusterText`**

在 `XingruiClusterMediaFormatter.kt` 中实现文本规范化清洗：
```kotlin
    fun cleanClusterText(raw: String?): String {
        if (raw.isNullOrBlank()) return " "
        var s = raw
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\u3000', ' ')
            .replace('～', '~')
            .replace('〜', '~')
            .replace('・', '·')
        s = s.replace(Regex("\\s+"), " ").trim()
        return if (s.isEmpty()) " " else s
    }
```

- [ ] **Step 4: 运行测试验证通过**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.instrument.XingruiClusterMediaFormatterTest.cleanClusterText_normalizesWhitespaceAndCjkPunctuation"`
预期：PASS。

- [ ] **Step 5: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/XingruiClusterMediaFormatter.kt common/src/test/java/com/shilapi/xcertplay/instrument/XingruiClusterMediaFormatterTest.kt
git commit -m "feat(cluster): 增加仪表文本空白折叠与日韩标点规范化清洗"
```

---

### Task 2: 在 `XingruiClusterMediaFormatter` 中实现日韩文注音剔除与中文翻译优先提取

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/XingruiClusterMediaFormatter.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/XingruiClusterMediaFormatterTest.kt`

**Interfaces:**
- Consumes: `raw: String?`
- Produces: `optimizeCjkDisplay(raw: String?): String`, `stripTranslationSuffix(raw: String?): String`

- [ ] **Step 1: 编写注音剥离、双语提取与防误伤单测**

在 `XingruiClusterMediaFormatterTest.kt` 中添加单测：
```kotlin
    @Test
    fun optimizeCjkDisplay_stripsFuriganaRomajiAnnotations() {
        // 剥离纯假名/罗马音注音括号，释放仪表字符空间
        assertEquals("仓木麻衣", XingruiClusterMediaFormatter.optimizeCjkDisplay("仓木麻衣 (くらき まい)"))
        assertEquals("手嶌葵", XingruiClusterMediaFormatter.optimizeCjkDisplay("手嶌葵 (てしま あおい)"))
        assertEquals("Buono!", XingruiClusterMediaFormatter.optimizeCjkDisplay("Buono! (ボーノ)"))
    }

    @Test
    fun optimizeCjkDisplay_extractsChineseTranslationWhenCjkPresent() {
        // 带中文翻译的日文歌曲：优先提取中文，确保在31字内且含中文
        val jpLong = "さよならの夏~コクリコ坂から~ (道别之夏 ~虞美人盛开的山坡~)"
        val optJp = XingruiClusterMediaFormatter.optimizeCjkDisplay(jpLong)
        assertTrue(optJp.length <= XingruiClusterMediaFormatter.MAX_CAN_TEXT_LENGTH)
        assertEquals("道别之夏 ~虞美人盛开的山坡~", optJp)

        // 韩文歌曲带中文翻译：直接提取中文翻译避免大片字空白
        val krWithZh = "사랑해요 (我爱你)"
        assertEquals("我爱你", XingruiClusterMediaFormatter.optimizeCjkDisplay(krWithZh))

        // 斜杠分隔双语歌词：提取中文翻译
        val slashBilingual = "さよなら / 再见"
        assertEquals("再见", XingruiClusterMediaFormatter.optimizeCjkDisplay(slashBilingual))
    }

    @Test
    fun optimizeCjkDisplay_preservesStandardVersionsWithoutFalseDrop() {
        // 中文歌曲带 (Live)、(伴奏)、(feat. xxx) 等，不得误删
        assertEquals("七里香 (Live)", XingruiClusterMediaFormatter.optimizeCjkDisplay("七里香 (Live)"))
        assertEquals("稻香 (伴奏)", XingruiClusterMediaFormatter.optimizeCjkDisplay("稻香 (伴奏)"))
        assertEquals("怎么了 (feat. 周兴哲)", XingruiClusterMediaFormatter.optimizeCjkDisplay("怎么了 (feat. 周兴哲)"))
    }

    @Test
    fun stripTranslationSuffix_removesOnlyChineseTranslationBracket() {
        assertEquals("さよならの夏", XingruiClusterMediaFormatter.stripTranslationSuffix("さよならの夏 (道别之夏)"))
        assertEquals("七里香 (Live)", XingruiClusterMediaFormatter.stripTranslationSuffix("七里香 (Live)"))
    }
```

- [ ] **Step 2: 运行测试验证失败**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.instrument.XingruiClusterMediaFormatterTest.optimizeCjkDisplay_stripsFuriganaRomajiAnnotations"`
预期：编译失败或断言失败。

- [ ] **Step 3: 实现精确中文识别与 `optimizeCjkDisplay`**

在 `XingruiClusterMediaFormatter.kt` 中实现：
```kotlin
    private val RE_HANGUL = Regex("[\\uac00-\\ud7af]")
    private val RE_KANA = Regex("[\\u3040-\\u30ff]")
    private val RE_HANZI = Regex("[\\u4e00-\\u9fff]")
    private val RE_BRACKETS = Regex("[\\(\\[（【](.*?)[\\)\\]）】]")

    /**
     * 判断文本是否是中文翻译：包含汉字，且绝不包含日文假名与韩文。
     */
    fun isChineseTranslation(text: String): Boolean {
        val hasHanzi = RE_HANZI.containsMatchIn(text)
        val hasKana = RE_KANA.containsMatchIn(text)
        val hasHangul = RE_HANGUL.containsMatchIn(text)
        return hasHanzi && !hasKana && !hasHangul
    }

    fun stripTranslationSuffix(raw: String?): String {
        val cleaned = cleanClusterText(raw)
        if (cleaned.isBlank() || cleaned == " ") return " "
        val hasForeign = RE_KANA.containsMatchIn(cleaned) || RE_HANGUL.containsMatchIn(cleaned)
        if (!hasForeign) return cleaned

        val match = RE_BRACKETS.findAll(cleaned).lastOrNull()
        if (match != null && isChineseTranslation(match.groupValues[1])) {
            val stripped = cleaned.removeRange(match.range).trim()
            if (stripped.isNotEmpty()) return stripped
        }
        return cleaned
    }

    fun optimizeCjkDisplay(raw: String?): String {
        val cleaned = cleanClusterText(raw)
        if (cleaned.isBlank() || cleaned == " ") return " "

        val hasHangul = RE_HANGUL.containsMatchIn(cleaned)
        val hasKana = RE_KANA.containsMatchIn(cleaned)
        val isForeignTrack = hasHangul || hasKana

        // 1. 如果包含斜杠双语分隔（例如 "日文 / 中文"），若前半截含日韩、后半截为中文，直接取中文
        if (isForeignTrack && cleaned.contains("/")) {
            val parts = cleaned.split("/").map { it.trim() }
            if (parts.size == 2 && isChineseTranslation(parts[1])) {
                return formatText(parts[1])
            }
        }

        var result = cleaned
        val matches = RE_BRACKETS.findAll(cleaned).toList()

        // 2. 只有在确定是日韩歌曲时，才清理注音括号（纯假名或纯字母注音）
        if (isForeignTrack) {
            for (m in matches) {
                val content = m.groupValues[1].trim()
                val isKanaOnly = RE_KANA.containsMatchIn(content) && !RE_HANZI.containsMatchIn(content)
                val isRomajiOnly = content.all { it.isLetter() || it.isWhitespace() } &&
                    !content.equals("Live", ignoreCase = true) &&
                    !content.startsWith("feat", ignoreCase = true)
                if (isKanaOnly || isRomajiOnly) {
                    result = result.replace(m.value, "").trim()
                }
            }
            result = cleanClusterText(result)
        }

        // 3. 韩文歌曲（仪表盘完全无字库会全变空白）：优先取中文翻译
        if (hasHangul) {
            val zhMatch = matches.firstOrNull { isChineseTranslation(it.groupValues[1]) }
            if (zhMatch != null) {
                return formatText(zhMatch.groupValues[1])
            }
        }

        // 4. 日文歌曲超长截断保护：如果总长超 31 字符，且括号内是完整中文翻译，优先显示中文翻译
        if (hasKana && (result.length > MAX_CAN_TEXT_LENGTH || matches.isNotEmpty())) {
            val zhMatch = matches.firstOrNull { isChineseTranslation(it.groupValues[1]) }
            if (zhMatch != null && result.length > MAX_CAN_TEXT_LENGTH) {
                return formatText(zhMatch.groupValues[1])
            }
        }

        return formatText(result)
    }
```

- [ ] **Step 4: 运行测试验证通过**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.instrument.XingruiClusterMediaFormatterTest"`
预期：PASS。

- [ ] **Step 5: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/XingruiClusterMediaFormatter.kt common/src/test/java/com/shilapi/xcertplay/instrument/XingruiClusterMediaFormatterTest.kt
git commit -m "feat(cluster): 优化 CJK 媒体文本，剥离假名注音并优先提取中文翻译"
```

---

### Task 3: 优化 `CarPlayMediaKeys` 中的日韩制作前缀与双行智能排版

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/CarPlayMediaKeysBroadcastTest.kt`

**Interfaces:**
- Consumes: `rawTitle: String?`, `rawArtist: String?`
- Produces: `filterLyricMetadata(...)` 中的 `clusterLine1`, `clusterLine2`

- [ ] **Step 1: 编写制作前缀与双行排版单测**

在 `CarPlayMediaKeysBroadcastTest.kt` 中添加：
```kotlin
    @Test
    fun filterLyricMetadata_recognizesJapaneseKoreanEnglishCredits() {
        val base = CarPlayNowPlaying(
            title = "どうして", artist = "高瀬統也", album = "Album",
            durationMillis = 200_000L, elapsedMillis = 0L, playing = true,
        )
        val baseFilter = CarPlayMediaKeys.filterLyricMetadata(base, null, null, null, null)

        // 日文制作信息前缀
        val jpLyric = CarPlayNowPlaying(
            title = "作詞：野田愛実", artist = "高瀬統也", album = "Album",
            durationMillis = 200_000L, elapsedMillis = 10_000L, playing = true,
        )
        val filteredJp = CarPlayMediaKeys.filterLyricMetadata(
            jpLyric, baseFilter.genuineTitle, baseFilter.genuineArtist, baseFilter.genuineAlbum, baseFilter.durationMillis,
        )
        assertTrue("日文作词应识别为歌词行", filteredJp.isLyric)
        assertEquals("作詞：野田愛実", filteredJp.clusterLine1)

        // 英文制作信息前缀
        val enLyric = CarPlayNowPlaying(
            title = "Lyrics: Singer", artist = "高瀬統也", album = "Album",
            durationMillis = 200_000L, elapsedMillis = 10_000L, playing = true,
        )
        val filteredEn = CarPlayMediaKeys.filterLyricMetadata(
            enLyric, baseFilter.genuineTitle, baseFilter.genuineArtist, baseFilter.genuineAlbum, baseFilter.durationMillis,
        )
        assertTrue("英文 Lyrics 应识别为歌词行", filteredEn.isLyric)
    }

    @Test
    fun filterLyricMetadata_matchesLyricsWhenSuffixOmitsTranslation() {
        // 主歌名带有中文翻译括号
        val base = CarPlayNowPlaying(
            title = "こころのたまご (心灵之蛋)", artist = "Buono!", album = "Album",
            durationMillis = 200_000L, elapsedMillis = 0L, playing = true,
        )
        val baseFilter = CarPlayMediaKeys.filterLyricMetadata(base, null, null, null, null)

        // 歌词包的后缀只带有原歌名 " - こころのたまご"，没有带括号翻译
        val lyricNoZh = CarPlayNowPlaying(
            title = "Hop Step Jump - こころのたまご", artist = "Buono!", album = "Album",
            durationMillis = 200_000L, elapsedMillis = 15_000L, playing = true,
        )
        val filtered = CarPlayMediaKeys.filterLyricMetadata(
            lyricNoZh, baseFilter.genuineTitle, baseFilter.genuineArtist, baseFilter.genuineAlbum, baseFilter.durationMillis,
        )
        assertTrue("歌词后缀即使省略翻译也应精准匹配", filtered.isLyric)
        assertEquals("Hop Step Jump", filtered.clusterLine1)
    }

    @Test
    fun filterLyricMetadata_formatsClusterLinesWithCjkOptimization() {
        // 歌名带中文翻译与注音歌手
        val raw = CarPlayNowPlaying(
            title = "さよならの夏～コクリコ坂から～ (道别之夏 ~虞美人盛开的山坡~)",
            artist = "手嶌葵 (てしま あおい)",
            album = "Album",
            durationMillis = 300_000L,
            elapsedMillis = 0L,
            playing = true,
        )
        val filtered = CarPlayMediaKeys.filterLyricMetadata(raw, null, null, null, null)
        assertTrue("Line1 长度不超过31", filtered.clusterLine1!!.length <= 31)
        assertTrue("Line2 长度不超过31", filtered.clusterLine2!!.length <= 31)
        assertTrue("注音括号被清理", !filtered.clusterLine2!!.contains("てしま あおい"))
    }
```

- [ ] **Step 2: 运行测试验证失败**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.CarPlayMediaKeysBroadcastTest.filterLyricMetadata_recognizesJapaneseKoreanEnglishCredits"`
预期：断言失败。

- [ ] **Step 3: 扩充前缀并接入 CJK 格式化与模糊后缀匹配**

1. 修改 `CarPlayMediaKeys.kt` 中的 `LYRIC_CREDIT_PREFIXES`：
```kotlin
    private val LYRIC_CREDIT_PREFIXES = arrayOf(
        "词：", "词:", "作词：", "作词:",
        "曲：", "曲:", "作曲：", "作曲:",
        "编曲：", "编曲:",
        "制作：", "制作:", "制作人：", "制作人:",
        "监制：", "监制:", "混音：", "混音:", "出品：", "出品:",
        // 日文前缀
        "作詞：", "作詞:", "作曲：", "作曲:", "編曲：", "編曲:", "唄：", "唄:",
        // 英文前缀
        "Lyrics:", "Lyricist:", "Composer:", "Arranger:", "Producer:",
        // 韩文前缀
        "작사:", "작곡:", "편곡:",
    )
```
2. 在 `filterLyricMetadata` 中增强歌词后缀与置换识别：
```kotlin
        val baseTitle = lastGenuineTitle?.let { XingruiClusterMediaFormatter.stripTranslationSuffix(it) }
        val isLyricSwap = rawArtist == lastGenuineTitle || (!baseTitle.isNullOrBlank() && rawArtist == baseTitle)
        val isLyricSuffix = !rawTitle.isNullOrBlank() && (
            rawTitle.endsWith(" - $lastGenuineTitle") || (!baseTitle.isNullOrBlank() && rawTitle.endsWith(" - $baseTitle"))
        )
```
3. 在 `filterLyricMetadata` 中组装 `clusterLine1` 与 `clusterLine2`：
```kotlin
        val rawLine1 = when {
            isLyricSuffix -> {
                val suffix = if (rawTitle!!.endsWith(" - $lastGenuineTitle")) " - $lastGenuineTitle" else " - $baseTitle"
                rawTitle.removeSuffix(suffix).ifBlank { lastGenuineTitle }
            }
            else -> rawTitle?.takeIf { it.isNotBlank() } ?: lastGenuineTitle
        }
        val line1 = XingruiClusterMediaFormatter.optimizeCjkDisplay(rawLine1)
        val cleanArtist = XingruiClusterMediaFormatter.optimizeCjkDisplay(lastGenuineArtist)
        val cleanTitle = XingruiClusterMediaFormatter.optimizeCjkDisplay(lastGenuineTitle)
        val combinedLine2 = joinNonBlank(cleanArtist, cleanTitle, " - ")
        val line2 = if (combinedLine2 != null && combinedLine2.length > 31) {
            cleanArtist.takeIf { it.isNotBlank() } ?: cleanTitle
        } else {
            combinedLine2 ?: cleanTitle
        }
```

- [ ] **Step 4: 运行全部测试验证通过**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest`
预期：所有单测全部 PASS。

- [ ] **Step 5: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt common/src/test/java/com/shilapi/xcertplay/CarPlayMediaKeysBroadcastTest.kt
git commit -m "feat(cluster): 增强日韩英制作前缀识别并优化仪表第二行自适应排版"
```

---

### Task 4: 回归验证与整体构建打包校验

**Files:**
- Test: All unit tests in `:common` and `:automotive`

- [ ] **Step 1: 运行全模块测试**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew test`
预期：BUILD SUCCESSFUL。

- [ ] **Step 2: 验证编译与打包**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew assembleDebug`
预期：BUILD SUCCESSFUL。
