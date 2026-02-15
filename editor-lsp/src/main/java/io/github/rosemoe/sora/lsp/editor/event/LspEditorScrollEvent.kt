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

package io.github.rosemoe.sora.lsp.editor.event

import io.github.rosemoe.sora.event.EventReceiver
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.Unsubscribe
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.requestInlayHint
import io.github.rosemoe.sora.lsp.editor.requestSemanticTokens
import io.github.rosemoe.sora.text.CharPosition
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

@OptIn(FlowPreview::class)
class LspEditorScrollEvent(private val editor: LspEditor) :
    EventReceiver<ScrollEvent> {

    private var lastRangeStartLine: Int = -1
    private var lastRangeEndLine: Int = -1

    override fun onReceive(event: ScrollEvent, unsubscribe: Unsubscribe) {
        if (!editor.isConnected) {
            return
        }

        val codeEditor = event.editor
        val firstVisibleLine = codeEditor.firstVisibleLine

        if (editor.isEnableInlayHint) {
            editor.coroutineScope.launch {
                editor.requestInlayHint(
                    CharPosition(firstVisibleLine, 0)
                )
            }
        }

        if (editor.isEnableSemanticTokens) {
            val provider = editor.requestManager.capabilities?.semanticTokensProvider ?: return
            val rangeSupport = provider.range ?: return
            val rangeSupported = (rangeSupport.isLeft && rangeSupport.left == true) || rangeSupport.isRight
            if (!rangeSupported) {
                return
            }

            val lineCount = codeEditor.lineCount
            if (lineCount <= 0) {
                return
            }
            val lastVisibleLine = codeEditor.lastVisibleLine

            // Sliding window: only request when the viewport is close to the current window edge.
            // This reduces request spam while keeping semantic highlighting responsive on scroll.
            val paddingLines = 60
            val edgeThresholdLines = 20
            val shouldRequest = lastRangeStartLine < 0 ||
                lastRangeEndLine < 0 ||
                firstVisibleLine < lastRangeStartLine + edgeThresholdLines ||
                lastVisibleLine > lastRangeEndLine - edgeThresholdLines
            if (!shouldRequest) {
                return
            }

            val startLine = (firstVisibleLine - paddingLines).coerceAtLeast(0)
            val endLine = (lastVisibleLine + paddingLines).coerceAtMost(lineCount - 1)
            if (endLine < startLine) return
            if (startLine == lastRangeStartLine && endLine == lastRangeEndLine) return
            lastRangeStartLine = startLine
            lastRangeEndLine = endLine
            val endColumn = codeEditor.text.getColumnCount(endLine)
            val range = Range(
                Position(startLine, 0),
                Position(endLine, endColumn)
            )

            editor.coroutineScope.launch {
                editor.requestSemanticTokens(range)
            }
        }

    }
}
