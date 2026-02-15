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

package io.github.rosemoe.sora.lsp.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException
import io.github.rosemoe.sora.lang.completion.CompletionHelper
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.createCompletionItemComparator
import io.github.rosemoe.sora.lang.completion.filterCompletionItems
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lsp.editor.completion.CompletionItemProvider
import io.github.rosemoe.sora.lsp.editor.completion.CollectingCompletionPublisher
import io.github.rosemoe.sora.lsp.editor.completion.LspCompletionItem
import io.github.rosemoe.sora.lsp.editor.format.LspFormatter
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.events.completion.completion
import io.github.rosemoe.sora.lsp.events.document.DocumentChangeEvent
import io.github.rosemoe.sora.lsp.requests.Timeout
import io.github.rosemoe.sora.lsp.requests.Timeouts
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.util.MyCharacter
import io.github.rosemoe.sora.widget.SymbolPairMatch
import kotlinx.coroutines.future.future
import java.util.concurrent.TimeUnit
import kotlin.math.min


class LspLanguage(var editor: LspEditor) : Language {

    private var _formatter: Formatter? = null

    var wrapperLanguage: Language? = null
    var completionItemProvider: CompletionItemProvider<*>

    init {
        _formatter = LspFormatter(this)
        completionItemProvider =
            CompletionItemProvider { completionItem, eventManager, prefixLength ->
                LspCompletionItem(
                    completionItem,
                    eventManager,
                    prefixLength
                )
            }
    }

    override fun getAnalyzeManager(): AnalyzeManager {
        return wrapperLanguage?.analyzeManager ?: EmptyLanguage.EmptyAnalyzeManager.INSTANCE
    }

    override fun getInterruptionLevel(): Int {
        return wrapperLanguage?.interruptionLevel ?: 0
    }

    @Throws(CompletionCancelledException::class)
    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {

        /* if (getEditor().hitTrigger(line)) {
            publisher.cancel();
            return;
        }*/

        val wrapper = wrapperLanguage
        val fallbackItems = if (wrapper != null) {
            runCatching {
                val collector = CollectingCompletionPublisher(wrapper.interruptionLevel)
                wrapper.requireAutoComplete(content, position, collector, extraArguments)
                collector.snapshot()
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        // 1) 未连接：仅使用 wrapperLanguage 的本地候选（如果有）
        if (!editor.isConnected) {
            if (fallbackItems.isEmpty()) return
            val filteredFallback = filterCompletionItems(content, position, fallbackItems)
            publisher.setComparator(createCompletionItemComparator(filteredFallback))
            publisher.addItems(filteredFallback)
            publisher.updateList()
            return
        }

        // 2) 已连接：先快速展示本地候选，再异步追加 LSP completion（避免阻塞输入体验）
        val fallbackDeduped = LinkedHashMap<String, CompletionItem>(fallbackItems.size)
        for (item in fallbackItems) {
            fallbackDeduped.putIfAbsent(completionKey(item), item)
        }

        val filteredFallback = if (fallbackDeduped.isEmpty()) {
            emptyList()
        } else {
            filterCompletionItems(content, position, fallbackDeduped.values)
        }

        if (filteredFallback.isNotEmpty()) {
            publisher.setComparator(createCompletionItemComparator(filteredFallback))
            publisher.addItems(filteredFallback)
            publisher.updateList()
        }

        val prefixLength = computePrefix(content, position).length

        val documentChangeEvent = editor.eventManager.getEventListener<DocumentChangeEvent>()
        val documentChangeFuture = documentChangeEvent?.future

        // 如果没有任何 fallback 候选：需要在当前 completion 线程内等待一次 LSP 结果，
        // 否则 EditorAutoCompletion 会因为 publisher.hasData()==false 直接 hide()，导致结果“闪一下就没了”。
        if (filteredFallback.isEmpty()) {
            if (documentChangeFuture != null) {
                runCatching {
                    documentChangeFuture.get(Timeout[Timeouts.WILLSAVE].toLong(), TimeUnit.MILLISECONDS)
                }
            }

            val serverResultCompletionItems = editor.coroutineScope.future {
                editor.eventManager.emitAsync(EventType.completion, position)
                    .getOrNull<List<org.eclipse.lsp4j.CompletionItem>>("completion-items")
                    ?: emptyList()
            }

            val rawLspItems = ArrayList<CompletionItem>(32)
            try {
                serverResultCompletionItems
                    .get(Timeout[Timeouts.COMPLETION].toLong(), TimeUnit.MILLISECONDS)
                    .forEach { completionItem ->
                        val itemPrefixLength =
                            lspPrefixLengthForCompletionItem(completionItem, position, prefixLength)
                        rawLspItems.add(
                            completionItemProvider.createCompletionItem(
                                completionItem,
                                editor.eventManager,
                                itemPrefixLength
                            )
                        )
                    }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                // Ignore LSP completion failures
            }

            val filteredLspItems = filterCompletionItems(content, position, rawLspItems)
            if (filteredLspItems.isEmpty()) return

            publisher.setComparator(createCompletionItemComparator(filteredLspItems))
            publisher.addItems(filteredLspItems)
            publisher.updateList()
            return
        }

        val existingKeys = HashSet<String>(filteredFallback.size + 32).apply {
            for (item in filteredFallback) {
                add(completionKey(item))
            }
        }

        editor.coroutineScope.future {
            // 尽量确保 didChange 已发送，再请求 completion（但不阻塞当前 completion 线程）
            if (documentChangeFuture != null) {
                runCatching {
                    documentChangeFuture.get(Timeout[Timeouts.WILLSAVE].toLong(), TimeUnit.MILLISECONDS)
                }
            }

            editor.eventManager.emitAsync(EventType.completion, position)
                .getOrNull<List<org.eclipse.lsp4j.CompletionItem>>("completion-items")
                ?: emptyList()
        }.thenAccept { completions ->
            if (completions.isEmpty()) return@thenAccept

            val rawLspItems = buildList(completions.size) {
                for (completionItem in completions) {
                    val itemPrefixLength =
                        lspPrefixLengthForCompletionItem(completionItem, position, prefixLength)
                    val item = completionItemProvider.createCompletionItem(
                        completionItem,
                        editor.eventManager,
                        itemPrefixLength
                    )
                    val key = completionKey(item)
                    if (existingKeys.add(key)) {
                        add(item)
                    }
                }
            }

            if (rawLspItems.isEmpty()) return@thenAccept

            runCatching {
                val filteredLspItems = filterCompletionItems(content, position, rawLspItems)
                if (filteredLspItems.isEmpty()) return@runCatching

                // comparator 对所有 items 通用；重复设置是安全的（会触发一次排序 + UI 更新）
                publisher.setComparator(createCompletionItemComparator(filteredLspItems))
                publisher.addItems(filteredLspItems)
                publisher.updateList()
            }
        }
    }

    private fun computePrefix(text: ContentReference, position: CharPosition): String {
        val triggers = editor.completionTriggers.filterNot { trigger ->
            trigger.length == 1 && trigger[0].isLetterOrDigit()
        }
        if (triggers.isEmpty()) {
            return CompletionHelper.computePrefix(text, position) { key: Char ->
                MyCharacter.isJavaIdentifierPart(key)
            }
        }

        val delimiters = triggers.toMutableList().apply {
            // `#` 对多数语言都是“分隔符”（如 C/C++/C#/... 的预处理指令），
            // 若不加入会导致 prefix 包含 `#`，进而把 clangd 返回的 `include` 等结果过滤掉。
            addAll(listOf(" ", "\t", "\n", "\r", "#"))
        }

        val s = StringBuilder()

        val line = text.getLine(position.line)
        for (i in min(line.lastIndex, position.column - 1) downTo 0) {
            val char = line[i]
            if (delimiters.contains(char.toString())) {
                return s.reverse().toString()
            }
            s.append(char)
        }
        return s.reverse().toString()
    }

    private fun lspPrefixLengthForCompletionItem(
        completionItem: org.eclipse.lsp4j.CompletionItem,
        position: CharPosition,
        fallbackPrefixLength: Int,
    ): Int {
        val edit = completionItem.textEdit ?: return fallbackPrefixLength
        val range = when {
            edit.isLeft -> edit.left.range
            edit.isRight -> edit.right.insert
            else -> null
        } ?: return fallbackPrefixLength

        val start = range.start
        if (start.line != position.line) return fallbackPrefixLength

        val computed = position.column - start.character
        return if (computed >= 0) computed else fallbackPrefixLength
    }

    private fun completionKey(item: CompletionItem): String = buildString {
        append(item.label?.toString().orEmpty())
        append('\u0000')
        append(item.kind?.value ?: -1)
    }

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
        return wrapperLanguage?.interruptionLevel ?: 0
    }

    override fun useTab(): Boolean {
        return wrapperLanguage?.useTab() == true
    }

    override fun getFormatter(): Formatter {
        return _formatter ?: wrapperLanguage?.formatter ?: EmptyLanguage.EmptyFormatter.INSTANCE
    }

    fun setFormatter(formatter: Formatter) {
        this._formatter = formatter
    }

    override fun getSymbolPairs(): SymbolPairMatch {
        return wrapperLanguage?.symbolPairs ?: EmptyLanguage.EMPTY_SYMBOL_PAIRS
    }

    override fun getNewlineHandlers(): Array<NewlineHandler?> {
        return wrapperLanguage?.newlineHandlers ?: emptyArray()
    }

    override fun destroy() {
        formatter.destroy()
        wrapperLanguage?.destroy()
    }


}

