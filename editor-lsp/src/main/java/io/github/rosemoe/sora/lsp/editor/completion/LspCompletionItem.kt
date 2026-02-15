/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2023  Rosemoe
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

package io.github.rosemoe.sora.lsp.editor.completion

import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.SimpleCompletionIconDrawer.draw
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.lsp.editor.LspEventManager
import io.github.rosemoe.sora.lsp.editor.completion.LspCompletionConfig
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.events.document.applyEdits
import io.github.rosemoe.sora.lsp.utils.asLspPosition
import io.github.rosemoe.sora.lsp.utils.createPosition
import io.github.rosemoe.sora.lsp.utils.createRange
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.util.Logger
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.TextEdit


class LspCompletionItem(
    private val completionItem: CompletionItem,
    private val eventManager: LspEventManager,
    prefixLength: Int
) : io.github.rosemoe.sora.lang.completion.CompletionItem(
    completionItem.label,
    completionItem.detail
) {

    private companion object {
        private const val DOLLAR: Char = '$'

        private val TABSTOP_WITH_DEFAULT =
            Regex("\\$\\{(\\d+):([^}]*)\\}")

        /**
         * For variadic functions (e.g. printf(...)), clangd often returns snippet like:
         * `printf(${1:format}, $0)`.
         *
         * Keeping the trailing ", $0" forces users to delete the comma when they
         * don't need extra arguments.
         */
        private val TRAILING_COMMA_FINAL_TABSTOP_BEFORE_RPAREN =
            Regex(",\\s*\\${DOLLAR}0\\)")

        private val TRAILING_COMMA_FINAL_TABSTOP =
            Regex(",\\s*\\${DOLLAR}0\\b")

        private val FINAL_TABSTOP_AFTER_RPAREN =
            Regex("\\)\\s*\\${DOLLAR}0\\b")

        private val VARIADIC_ARG_PLACEHOLDER =
            Regex(",\\s*\\${DOLLAR}\\{\\d+:\\.\\.\\.\\}")

        private val VARIADIC_ARG_LITERAL =
            Regex(",\\s*\\.\\.\\.")
    }

    init {
        this.prefixLength = prefixLength
        kind =
            if (completionItem.kind == null) CompletionItemKind.Text else CompletionItemKind.valueOf(
                completionItem.kind.name
            )
        sortText = completionItem.sortText
        filterText = completionItem.filterText
        val labelDetails = completionItem.labelDetails
        if (labelDetails != null && labelDetails.description?.isNotEmpty() == true) {
            desc = labelDetails.description
        }
        desc = desc?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        icon = draw(kind ?: CompletionItemKind.Text)
    }

    override fun performCompletion(editor: CodeEditor, text: Content, position: CharPosition) {
        var textEdit = TextEdit()

        textEdit.range = createRange(
            createPosition(
                position.line,
                position.column - prefixLength
            ), position.asLspPosition()
        )


        if (completionItem.insertText != null) {
            textEdit.newText = completionItem.insertText
        }

        if (completionItem.textEdit != null && completionItem.textEdit.isLeft) {
            textEdit = completionItem.textEdit.left
        } else if (completionItem.textEdit?.isRight == true) {
            textEdit = TextEdit(completionItem.textEdit.right.insert, completionItem.textEdit.right.newText)
        }

        if (textEdit.newText == null && completionItem.label != null) {
            textEdit.newText = completionItem.label
        }

        run {
            // workaround https://github.com/Microsoft/vscode/issues/17036
            val start = textEdit.range.start
            val end = textEdit.range.end
            if (start.line > end.line || start.line == end.line && start.character > end.character) {
                textEdit.range.end = start
                textEdit.range.start = end
            }
        }

        run {
            // allow completion items to be wrong with a too wide range
            val documentEnd = createPosition(
                text.lineCount - 1,
                text.getColumnCount(0.coerceAtLeast(text.lineCount - 1))
            )

            val textEditEnd = textEdit.range.end
            if (documentEnd.line < textEditEnd.line || documentEnd.line == textEditEnd.line && documentEnd.character < textEditEnd.character
            ) {
                textEdit.range.end = documentEnd
            }
        }

        if (completionItem.insertTextFormat == InsertTextFormat.Snippet) {
            val snippetText = buildSnippetText(textEdit.newText ?: "")
            val codeSnippet = CodeSnippetParser.parse(snippetText)
            var startIndex = text.getCharIndex(
                textEdit.range.start.line,
                textEdit.range.start.character.coerceAtMost(text.getColumnCount(textEdit.range.start.line))
            )

            var endIndex = text.getCharIndex(
                textEdit.range.end.line,
                textEdit.range.end.character.coerceAtMost(text.getColumnCount(textEdit.range.end.line))
            )

            if (endIndex < startIndex) {
                Logger.instance(this.javaClass.name)
                    .w(
                        "Invalid location information found applying edits from %s to %s",
                        textEdit.range.start,
                        textEdit.range.end
                    )
                val diff = startIndex - endIndex
                endIndex = startIndex
                startIndex = endIndex - diff
            }

            val selectedText = text.subSequence(startIndex, endIndex).toString()

            text.delete(startIndex, endIndex)

            editor.snippetController
                .startSnippet(startIndex, codeSnippet, selectedText)
        } else {
            eventManager.emit(EventType.applyEdits) {
                put("edits", listOf(textEdit))
                put(text)
            }
        }

        if (completionItem.additionalTextEdits != null) {
            eventManager.emit(EventType.applyEdits) {
                put("edits", completionItem.additionalTextEdits)
                put(text)
            }
        }
    }

    override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
        // do nothing
    }

    private fun buildSnippetText(raw: String): String {
        if (!isFunctionLikeCompletion(raw)) {
            return sanitizeVariadicSnippetText(raw)
        }

        val mode = LspCompletionConfig.functionArgPlaceholderMode
            .coerceIn(
                LspCompletionConfig.FUNCTION_ARG_PLACEHOLDER_MODE_OFF,
                LspCompletionConfig.FUNCTION_ARG_PLACEHOLDER_MODE_ALWAYS
            )

        val transformed = when (mode) {
            LspCompletionConfig.FUNCTION_ARG_PLACEHOLDER_MODE_OFF -> {
                toCallSnippetWithoutArgs(raw)
            }

            LspCompletionConfig.FUNCTION_ARG_PLACEHOLDER_MODE_SMART -> {
                stripAllArgDefaults(raw)
            }

            else -> {
                raw
            }
        }

        return sanitizeVariadicSnippetText(transformed)
    }

    private fun isFunctionLikeCompletion(rawSnippet: String): Boolean {
        val kind = completionItem.kind
        val isFunctionKind = kind == org.eclipse.lsp4j.CompletionItemKind.Function ||
            kind == org.eclipse.lsp4j.CompletionItemKind.Method ||
            kind == org.eclipse.lsp4j.CompletionItemKind.Constructor

        if (!isFunctionKind) return false

        val open = rawSnippet.indexOf('(')
        val close = rawSnippet.lastIndexOf(')')
        return open >= 0 && close > open
    }

    private fun toCallSnippetWithoutArgs(rawSnippet: String): String {
        val open = rawSnippet.indexOf('(')
        val close = rawSnippet.lastIndexOf(')')
        if (open < 0 || close <= open) return rawSnippet

        // 只保留 “函数名 + ()”，并把 $0 放进括号内
        val prefix = rawSnippet.substring(0, open)
        return "$prefix(${DOLLAR}0)"
    }

    private fun stripAllArgDefaults(rawSnippet: String): String {
        // CLion 风格：不插入任何参数名，只保留 Tab 跳转
        return TABSTOP_WITH_DEFAULT.replace(rawSnippet) { match ->
            val index = match.groupValues[1]
            "${DOLLAR}${index}"
        }
    }

    private fun sanitizeVariadicSnippetText(raw: String): String {
        // Only trim for variadic functions (e.g. printf(...))
        val isVariadic =
            completionItem.detail?.contains("...") == true ||
                completionItem.label?.contains("...") == true ||
                completionItem.labelDetails?.description?.contains("...") == true

        if (!isVariadic) return raw

        var out = raw

        // Remove explicit variadic placeholder if server provided it
        out = VARIADIC_ARG_PLACEHOLDER.replace(out, "")
        out = VARIADIC_ARG_LITERAL.replace(out, "")

        // `printf(${1:format}, $0)` -> `printf(${1:format}$0)`
        out = TRAILING_COMMA_FINAL_TABSTOP_BEFORE_RPAREN.replace(out) { "${DOLLAR}0)" }
        out = TRAILING_COMMA_FINAL_TABSTOP.replace(out) { "${DOLLAR}0" }

        // `printf(${1:format})$0` -> `printf(${1:format}$0)`
        out = FINAL_TABSTOP_AFTER_RPAREN.replace(out) { "${DOLLAR}0)" }

        return out
    }
}


