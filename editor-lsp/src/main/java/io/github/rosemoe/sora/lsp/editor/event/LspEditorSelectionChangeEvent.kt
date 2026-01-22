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
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.Unsubscribe
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.events.highlight.DocumentHighlightEvent
import io.github.rosemoe.sora.lsp.events.highlight.documentHighlight
import io.github.rosemoe.sora.lsp.events.hover.hover
import io.github.rosemoe.sora.lsp.events.signature.signatureHelp
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.getComponent
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LspEditorSelectionChangeEvent(private val editor: LspEditor) :
    EventReceiver<SelectionChangeEvent> {
    override fun onReceive(event: SelectionChangeEvent, unsubscribe: Unsubscribe) {
        if (!editor.isConnected) {
            return
        }

        val originEditor = editor.editor ?: return
        val isInCompletion = originEditor.getComponent<EditorAutoCompletion>().isShowing
        val shouldQueryAtCursor = !event.isSelected && hasIdentifierNearCursor(originEditor, event.left)

        if (!editor.isEnableSignatureHelp) {
            editor.showSignatureHelp(null)
        } else if (!isInCompletion) {
            // 更接近 CLion：光标移动时更新签名帮助（有选择文本时隐藏）
            if (event.isSelected) {
                editor.showSignatureHelp(null)
            } else if (editor.isShowSignatureHelp) {
                editor.coroutineScope.launch(Dispatchers.IO) {
                    editor.eventManager.emitAsync(EventType.signatureHelp, event.left)
                }
            }
        }

        if (editor.isEnableHover) {
            editor.showHover(null)
        }

        if (shouldQueryAtCursor) {
            editor.coroutineScope.launch(Dispatchers.IO) {
                editor.eventManager.emitAsync(EventType.documentHighlight) {
                    put(
                        DocumentHighlightEvent.DocumentHighlightRequest(
                            event.left.fromThis()
                        )
                    )
                }
            }
        } else {
            editor.showDocumentHighlight(null)
        }

        val hoverWindow = editor.hoverWindow ?: return

        if (!editor.isEnableHover) {
            return
        }

        if ((!originEditor.hasMouseHovering() && (!hoverWindow.alwaysShowOnTouchHover || event.isSelected)) || isInCompletion || !shouldQueryAtCursor) {
            return
        }

        editor.coroutineScope.launch(Dispatchers.IO) {
            editor.eventManager.emitAsync(EventType.hover, event.left)
        }
    }
}

private fun hasIdentifierNearCursor(editor: CodeEditor, position: CharPosition): Boolean {
    val safeLine = position.line.coerceIn(0, (editor.lineCount - 1).coerceAtLeast(0))
    val line = editor.text.getLine(safeLine)
    if (line.isEmpty()) return false

    // 空白行直接短路（避免滚动时在空行频繁触发 hover/highlight/signatureHelp）
    var hasNonWhitespace = false
    for (i in 0..line.lastIndex) {
        if (!line[i].isWhitespace()) {
            hasNonWhitespace = true
            break
        }
    }
    if (!hasNonWhitespace) return false

    val column = position.column.coerceIn(0, line.length)
    val start = (column - 8).coerceAtLeast(0)
    val endExclusive = (column + 8).coerceAtMost(line.length)

    for (i in start until endExclusive) {
        val c = line[i]
        if (c.isLetterOrDigit() || c == '_' || c == '$') {
            return true
        }
    }
    return false
}
