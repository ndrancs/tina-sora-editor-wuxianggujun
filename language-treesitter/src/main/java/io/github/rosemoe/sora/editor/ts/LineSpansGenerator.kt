/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2024  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/

package io.github.rosemoe.sora.editor.ts

import com.itsaky.androidide.treesitter.TSQueryCapture
import com.itsaky.androidide.treesitter.TSQueryCursor
import io.github.rosemoe.sora.editor.ts.spans.TsSpanFactory
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Spans
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.lang.styling.ViewportAwareSpans
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlin.math.max

/**
 * Spans generator for tree-sitter. Results are cached.
 *
 * Note that this implementation does not support external modifications.
 *
 * @author Rosemoe
 */
class LineSpansGenerator(
    internal var safeTree: SafeTsTree, internal var lineCount: Int,
    private val content: Content, internal var theme: TsTheme,
    private val languageSpec: TsLanguageSpec, var scopedVariables: TsScopedVariables,
    private val spanFactory: TsSpanFactory
) : ViewportAwareSpans {

    companion object {
        /**
         * Default visible lines per screen (estimated).
         * This is used as a baseline for prefetch calculations.
         */
        const val DEFAULT_VISIBLE_LINES = 40

        /**
         * Prefetch multiplier: how many screens worth of lines to prefetch
         * in the scroll direction. 1.5 means prefetch 1.5 screens ahead.
         */
        const val PREFETCH_MULTIPLIER = 1.5f

        /**
         * Minimum cache size to maintain (in lines).
         * This ensures we always have some buffer even when not scrolling.
         */
        const val MIN_CACHE_SIZE = 60
    }

    private val caches = mutableListOf<SpanCache>()

    // Dynamic cache management based on visible area
    private var estimatedVisibleLines = DEFAULT_VISIBLE_LINES
    private var cacheRangeStart = 0
    private var cacheRangeEnd = lineCount - 1

    private var scrollDirection = 0 // -1 = up, 0 = unknown, 1 = down

    private var rainbowDepthCacheVersion: Long = -1L
    private var rainbowDepthCacheLineCount: Int = -1
    private var rainbowDepthAtLineStart: IntArray = IntArray(0)

    /**
     * Calculate the optimal cache range based on visible line window and scroll direction.
     * Returns Pair(startLine, endLine) for the cache range.
     */
    private fun calculateCacheRange(firstVisibleLine: Int, lastVisibleLine: Int): Pair<Int, Int> {
        val prefetchLines = (estimatedVisibleLines * PREFETCH_MULTIPLIER).toInt()
        val bufferLines = estimatedVisibleLines / 2

        val start: Int
        val end: Int

        when (scrollDirection) {
            1 -> {
                // Scrolling down: prefetch more lines below the viewport
                start = (firstVisibleLine - bufferLines).coerceAtLeast(0)
                end = (lastVisibleLine + prefetchLines).coerceAtMost(lineCount - 1)
            }
            -1 -> {
                // Scrolling up: prefetch more lines above the viewport
                start = (firstVisibleLine - prefetchLines).coerceAtLeast(0)
                end = (lastVisibleLine + bufferLines).coerceAtMost(lineCount - 1)
            }
            else -> {
                // Unknown direction: balanced buffer around viewport
                start = (firstVisibleLine - bufferLines).coerceAtLeast(0)
                end = (lastVisibleLine + bufferLines).coerceAtMost(lineCount - 1)
            }
        }

        return Pair(start, end)
    }

    /**
     * Check if a line is within the current cache range.
     */
    private fun isInCacheRange(line: Int): Boolean {
        return line in cacheRangeStart..cacheRangeEnd
    }

    /**
     * Evict cache entries that are outside the optimal range.
     * Prioritizes evicting lines in the opposite direction of scroll.
     */
    private fun evictOutOfRangeCache() {
        if (caches.isEmpty()) return

        // Calculate max cache size based on visible lines
        val maxCacheSize = kotlin.math.max(
            MIN_CACHE_SIZE,
            (estimatedVisibleLines * (1 + PREFETCH_MULTIPLIER * 2)).toInt()
        )

        if (caches.size <= maxCacheSize) return

        // Remove out-of-range entries from the oldest end first.
        var idx = caches.lastIndex
        while (caches.size > maxCacheSize && idx >= 0) {
            val cache = caches[idx]
            if (!isInCacheRange(cache.line)) {
                caches.removeAt(idx)
            }
            idx--
        }

        // If still over limit, remove oldest entries
        while (caches.size > maxCacheSize) {
            caches.removeAt(caches.size - 1)
        }
    }

    override fun onViewportChanged(firstVisibleLine: Int, lastVisibleLine: Int, scrollDeltaY: Int) {
        if (lineCount <= 0) {
            cacheRangeStart = 0
            cacheRangeEnd = -1
            scrollDirection = 0
            return
        }

        estimatedVisibleLines = (lastVisibleLine - firstVisibleLine + 1)
            .coerceAtLeast(1)
            .coerceAtMost(lineCount)

        scrollDirection = when {
            scrollDeltaY > 0 -> 1
            scrollDeltaY < 0 -> -1
            else -> 0
        }

        val (rangeStart, rangeEnd) = calculateCacheRange(firstVisibleLine, lastVisibleLine)
        cacheRangeStart = rangeStart
        cacheRangeEnd = rangeEnd

        evictOutOfRangeCache()
    }

    fun queryCache(line: Int): MutableList<Span>? {
        for (i in 0 until caches.size) {
            val cache = caches[i]
            if (cache.line == line) {
                caches.removeAt(i)
                caches.add(0, cache)
                return cache.spans
            }
        }
        return null
    }

    fun pushCache(line: Int, spans: MutableList<Span>) {
        // Evict out-of-range entries first
        evictOutOfRangeCache()
        caches.add(0, SpanCache(spans, line))
    }

    /**
     * Capture spans for the given region.
     *
     * @return the list of spans, or null if the tree lock is not available (to avoid blocking the rendering thread)
     */
    fun captureRegion(startIndex: Int, endIndex: Int): MutableList<Span>? {
        val list = mutableListOf<Span>()
        val captures = mutableListOf<TSQueryCapture>()

        TSQueryCursor.create().use { cursor ->
            cursor.isAllowChangedNodes = true
            cursor.setByteRange(startIndex * 2, endIndex * 2)

            // Use tryAccessTree to avoid blocking the rendering thread
            // If the lock is not available (background thread is parsing), return null
            val accessed = safeTree.tryAccessTree { tree ->
                if (languageSpec.closed || tree.closed) {
                    return@tryAccessTree
                }

                cursor.exec(languageSpec.tsQuery, tree.rootNode)
                var match = cursor.nextMatch()
                while (match != null) {
                    if (languageSpec.queryPredicator.doPredicate(
                            languageSpec.predicates,
                            content,
                            match
                        )
                    ) {
                        captures.addAll(match.captures)
                    }
                    match = cursor.nextMatch()
                }
                captures.sortBy { it.node.startByte }
                var lastIndex = 0
                captures.forEach { capture ->
                    val startByte = capture.node.startByte
                    val endByte = capture.node.endByte
                    val start = (startByte / 2 - startIndex).coerceAtLeast(0)
                    val pattern = capture.index
                    // Do not add span for overlapping regions and out-of-bounds regions
                    if (start >= lastIndex && endByte / 2 >= startIndex && startByte / 2 < endIndex
                        && (pattern !in languageSpec.localsScopeIndices && pattern !in languageSpec.localsDefinitionIndices
                                && pattern !in languageSpec.localsDefinitionValueIndices && pattern !in languageSpec.localsMembersScopeIndices)
                    ) {
                        if (start != lastIndex) {
                            list.addAll(
                                createSpans(
                                    capture,
                                    lastIndex,
                                    start - 1,
                                    theme.normalTextStyle
                                )
                            )
                        }
                        var style = 0L
                        if (capture.index in languageSpec.localsReferenceIndices) {
                            val def = scopedVariables.findDefinition(
                                startByte / 2,
                                endByte / 2,
                                content.substring(startByte / 2, endByte / 2)
                            )
                            if (def != null && def.matchedHighlightPattern != -1) {
                                style = theme.resolveStyleForPattern(def.matchedHighlightPattern)
                            }
                            // This reference can not be resolved to its definition
                            // but it can have its own fallback color by other captures
                            // so continue to next capture
                            if (style == 0L) {
                                return@forEach
                            }
                        }
                        if (style == 0L) {
                            style = theme.resolveStyleForPattern(capture.index)
                        }
                        if (style == 0L) {
                            style = theme.normalTextStyle
                        }
                        val end = (endByte / 2 - startIndex).coerceAtMost(endIndex)
                        list.addAll(createSpans(capture, start, end, style))
                        lastIndex = end
                    }
                }
                if (lastIndex != endIndex) {
                    list.add(emptySpan(lastIndex))
                }
            }

            // If lock was not available, return null to indicate the caller should not cache
            if (accessed == null) {
                return null
            }
        }
        if (list.isEmpty()) {
            list.add(emptySpan(0))
        }
        return list
    }

    private fun createSpans(
        capture: TSQueryCapture,
        startColumn: Int,
        endColumn: Int,
        style: Long
    ): List<Span> {
        val spans = spanFactory.createSpans(capture, startColumn, style)
        if (spans.size > 1) {
            var prevCol = spans[0].column
            if (prevCol > endColumn) {
                throw IndexOutOfBoundsException("Span's column is out of bounds! column=$prevCol, endColumn=$endColumn")
            }
            for (i in 1..spans.lastIndex) {
                val col = spans[i].column
                if (col <= prevCol) {
                    throw IllegalStateException("Spans must not overlap! prevCol=$prevCol, col=$col")
                }
                if (col > endColumn) {
                    throw IndexOutOfBoundsException("Span's column is out of bounds! column=$col, endColumn=$endColumn")
                }
                prevCol = col
            }
        }
        return spans
    }

    private fun emptySpan(column: Int): Span {
        return SpanFactory.obtain(
            column,
            TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
        )
    }

    override fun adjustOnInsert(start: CharPosition, end: CharPosition) {

    }

    override fun adjustOnDelete(start: CharPosition, end: CharPosition) {

    }

    override fun read() = object : Spans.Reader {

        private var spans = mutableListOf<Span>()

        override fun moveToLine(line: Int) {
            if (line < 0) {
                spans = mutableListOf()
                return
            }

            if (line >= lineCount) {
                spans = mutableListOf()
                return
            }

            val cached = queryCache(line)
            if (cached != null) {
                spans = cached
                return
            }
            val start = content.indexer.getCharPosition(line, 0).index
            val end = start + content.getColumnCount(line)
            val captured = captureRegion(start, end)
            if (captured != null) {
                // Only cache and apply rainbow brackets if we successfully captured
                applyRainbowBracketsIfEnabled(line, captured)
                pushCache(line, captured)
                spans = captured
            } else {
                // Lock was not available, use empty span (don't cache)
                spans = mutableListOf(emptySpan(0))
            }
        }

        override fun getSpanCount() = spans.size

        override fun getSpanAt(index: Int) = spans[index]

        override fun getSpansOnLine(line: Int): MutableList<Span> {
            val cached = queryCache(line)
            if (cached != null) {
                return cached.toMutableList()
            }
            val start = content.indexer.getCharPosition(line, 0).index
            val end = start + content.getColumnCount(line)
            val captured = captureRegion(start, end)
            if (captured != null) {
                // Only cache and apply rainbow brackets if we successfully captured
                applyRainbowBracketsIfEnabled(line, captured)
                pushCache(line, captured)
                return captured.toMutableList()
            } else {
                // Lock was not available, return empty span (don't cache)
                return mutableListOf(emptySpan(0))
            }
        }

    }

    override fun supportsModify() = false

    override fun modify(): Spans.Modifier {
        throw UnsupportedOperationException()
    }

    override fun getLineCount() = lineCount

    private fun applyRainbowBracketsIfEnabled(line: Int, spans: MutableList<Span>) {
        if (!languageSpec.rainbowBracketsEnabled) return
        val colorCount = languageSpec.rainbowBracketsColorCount
        if (colorCount <= 0) return
        val maxLines = languageSpec.rainbowBracketsMaxLines
        if (maxLines > 0 && lineCount > maxLines) return
        if (line !in 0..<lineCount) return

        val textLine = content.getLineString(line)
        if (textLine.isEmpty()) return
        if (spans.isEmpty()) return

        ensureRainbowDepthCache()
        val startDepth = rainbowDepthAtLineStart.getOrElse(line) { 0 }

        // In some edge cases spans might not start at column 0
        if (spans[0].column != 0) {
            spans.add(0, emptySpan(0))
        }

        val bracketColumns = IntArray(textLine.length)
        val bracketColorIds = IntArray(textLine.length)
        var bracketCount = 0

        var depth = startDepth
        for (col in textLine.indices) {
            when (textLine[col]) {
                '(', '[', '{' -> {
                    bracketColumns[bracketCount] = col
                    bracketColorIds[bracketCount] = rainbowColorId(depth)
                    bracketCount++
                    depth++
                }

                ')', ']', '}' -> {
                    depth = max(0, depth - 1)
                    bracketColumns[bracketCount] = col
                    bracketColorIds[bracketCount] = rainbowColorId(depth)
                    bracketCount++
                }
            }
        }
        if (bracketCount == 0) return

        // Apply by inserting span switches at bracket positions. Process left-to-right.
        var spanIndex = 0
        for (i in 0 until bracketCount) {
            val col = bracketColumns[i]
            if (col !in 0 until textLine.length) continue

            while (spanIndex + 1 < spans.size && spans[spanIndex + 1].column <= col) {
                spanIndex++
            }

            val baseStyle = spans[spanIndex].style
            val fg = TextStyle.getForegroundColorId(baseStyle)
            if (fg == EditorColorScheme.COMMENT || fg == EditorColorScheme.LITERAL) {
                continue
            }

            // Ensure there's a span starting exactly at this column so that changing style only affects this char.
            if (spans[spanIndex].column != col) {
                spans.add(spanIndex + 1, SpanFactory.obtain(col, baseStyle))
                spanIndex++
            }

            spans[spanIndex].style = withForeground(baseStyle, bracketColorIds[i])

            val revertCol = col + 1
            if (revertCol < textLine.length) {
                val nextIdx = spanIndex + 1
                if (nextIdx >= spans.size || spans[nextIdx].column > revertCol) {
                    spans.add(nextIdx, SpanFactory.obtain(revertCol, baseStyle))
                }
            }
        }
    }

    private fun ensureRainbowDepthCache() {
        val version = content.documentVersion
        if (version == rainbowDepthCacheVersion && rainbowDepthCacheLineCount == lineCount) return

        val depths = IntArray(lineCount)
        var depth = 0
        for (line in 0 until lineCount) {
            depths[line] = depth
            val s = content.getLineString(line)
            for (ch in s) {
                when (ch) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth = max(0, depth - 1)
                }
            }
        }

        rainbowDepthAtLineStart = depths
        rainbowDepthCacheVersion = version
        rainbowDepthCacheLineCount = lineCount
    }

    private fun rainbowColorId(depth: Int): Int {
        val count = languageSpec.rainbowBracketsColorCount
        val base = languageSpec.rainbowBracketsBaseColorId
        if (count <= 0) return base
        return base + (depth % count)
    }

    private fun withForeground(style: Long, foregroundColorId: Int): Long {
        TextStyle.checkColorId(foregroundColorId)
        return (style and TextStyle.FOREGROUND_BITS.inv()) or foregroundColorId.toLong()
    }
}

data class SpanCache(val spans: MutableList<Span>, val line: Int)
