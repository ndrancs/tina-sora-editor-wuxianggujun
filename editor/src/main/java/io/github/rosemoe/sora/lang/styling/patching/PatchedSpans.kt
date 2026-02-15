/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2025  Rosemoe
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

package io.github.rosemoe.sora.lang.styling.patching

import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.Spans
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.lang.styling.ViewportAwareSpans
import io.github.rosemoe.sora.lang.styling.span.SpanExtAttrs
import io.github.rosemoe.sora.lang.styling.span.internal.SpanImpl
import io.github.rosemoe.sora.text.CharPosition
import java.util.Collections
import kotlin.math.max

/**
 * A [Spans] wrapper that applies [SparseStylePatches] on top of the base spans.
 *
 * The patch list is expected to be sparse and (currently) single-line.
 */
class PatchedSpans(
    private val baseSpans: Spans,
    private val stylePatches: SparseStylePatches
) : ViewportAwareSpans {

    override fun adjustOnInsert(start: CharPosition, end: CharPosition) {
        baseSpans.adjustOnInsert(start, end)
    }

    override fun adjustOnDelete(start: CharPosition, end: CharPosition) {
        baseSpans.adjustOnDelete(start, end)
    }

    override fun read(): Spans.Reader {
        return ReaderImpl(baseSpans.read(), stylePatches)
    }

    override fun supportsModify(): Boolean = baseSpans.supportsModify()

    override fun modify(): Spans.Modifier = baseSpans.modify()

    override fun getLineCount(): Int = baseSpans.lineCount

    override fun onViewportChanged(firstVisibleLine: Int, lastVisibleLine: Int, scrollDeltaY: Int) {
        if (baseSpans is ViewportAwareSpans) {
            baseSpans.onViewportChanged(firstVisibleLine, lastVisibleLine, scrollDeltaY)
        }
    }

    private class ReaderImpl(
        private val baseReader: Spans.Reader,
        private val patches: SparseStylePatches
    ) : Spans.Reader {

        private var currentLine: Int = -1
        private var currentLineSpans: List<Span>? = null
        private val patchBuffer = ArrayList<StylePatch>(16)

        override fun moveToLine(line: Int) {
            currentLine = line
            baseReader.moveToLine(line)
            if (line < 0) {
                currentLineSpans = null
                return
            }
            currentLineSpans = buildPatchedSpans(line, baseReader.getSpansOnLine(line))
        }

        override fun getSpanCount(): Int {
            val spans = currentLineSpans
            return spans?.size ?: baseReader.spanCount
        }

        override fun getSpanAt(index: Int): Span {
            val spans = currentLineSpans
            return spans?.get(index) ?: baseReader.getSpanAt(index)
        }

        override fun getSpansOnLine(line: Int): List<Span> {
            if (line == currentLine) {
                val spans = currentLineSpans
                if (spans != null) {
                    return spans
                }
            }
            return buildPatchedSpans(line, baseReader.getSpansOnLine(line))
        }

        private fun buildPatchedSpans(line: Int, baseSpans: List<Span>): List<Span> {
            if (baseSpans.isEmpty()) return baseSpans

            patches.getPatchesOnLine(line, patchBuffer)
            if (patchBuffer.isEmpty()) return baseSpans

            val result = ArrayList<Span>(baseSpans.size + patchBuffer.size * 4)

            var baseIndex = 0
            var nextBaseIndex = 1
            var currentColumn = 0

            fun advanceBaseIndexTo(column: Int) {
                while (nextBaseIndex < baseSpans.size && baseSpans[nextBaseIndex].column <= column) {
                    baseIndex = nextBaseIndex
                    nextBaseIndex++
                }
            }

            fun appendSpanAt(column: Int, patch: StylePatch?) {
                if (column < 0) return
                if (result.isNotEmpty() && result[result.size - 1].column == column) {
                    result.removeAt(result.size - 1)
                }
                advanceBaseIndexTo(column)
                val baseSpan = baseSpans[baseIndex]
                val span = if (patch == null) {
                    if (baseSpan.column == column) {
                        baseSpan
                    } else {
                        baseSpan.copy().also { it.column = column }
                    }
                } else {
                    createPatchedSpan(baseSpan, column, patch)
                }
                result.add(span)
            }

            fun appendInterval(from: Int, to: Int, patch: StylePatch?) {
                if (from >= to) return
                appendSpanAt(from, patch)
                while (nextBaseIndex < baseSpans.size) {
                    val nextCol = baseSpans[nextBaseIndex].column
                    if (nextCol >= to) break
                    appendSpanAt(nextCol, patch)
                }
            }

            for (patch in patchBuffer) {
                val start = max(currentColumn, patch.startColumn)
                val end = patch.endColumn
                if (end <= start) continue

                if (currentColumn < start) {
                    appendInterval(currentColumn, start, null)
                    currentColumn = start
                }
                appendInterval(currentColumn, end, patch)
                currentColumn = end
            }

            appendInterval(currentColumn, Int.MAX_VALUE, null)

            return Collections.unmodifiableList(result)
        }

        private fun createPatchedSpan(baseSpan: Span, column: Int, patch: StylePatch): Span {
            val baseStyle = baseSpan.style
            var style = baseStyle
            patch.overrideBold?.let { enabled ->
                style = if (enabled) style or TextStyle.BOLD_BIT else style and TextStyle.BOLD_BIT.inv()
            }
            patch.overrideItalics?.let { enabled ->
                style = if (enabled) style or TextStyle.ITALICS_BIT else style and TextStyle.ITALICS_BIT.inv()
            }
            patch.overrideStrikeThrough?.let { enabled ->
                style = if (enabled) style or TextStyle.STRIKETHROUGH_BIT else style and TextStyle.STRIKETHROUGH_BIT.inv()
            }

            val hasColorOverride = patch.overrideForeground != null || patch.overrideBackground != null
            val span = when {
                baseSpan is SpanImpl -> baseSpan.copy()
                hasColorOverride -> SpanImpl.obtain(column, style)
                else -> baseSpan.copy()
            }
            span.column = column
            span.style = style

            if (hasColorOverride) {
                span.setSpanExt(SpanExtAttrs.EXT_COLOR_RESOLVER, patch)
            }
            return span
        }
    }
}
