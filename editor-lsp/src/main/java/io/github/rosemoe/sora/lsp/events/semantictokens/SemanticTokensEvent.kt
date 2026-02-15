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

package io.github.rosemoe.sora.lsp.events.semantictokens

import android.os.SystemClock
import android.util.Log
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.events.AsyncEventListener
import io.github.rosemoe.sora.lsp.events.EventContext
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.requests.Timeout
import io.github.rosemoe.sora.lsp.requests.Timeouts
import io.github.rosemoe.sora.lsp.utils.FileUri
import io.github.rosemoe.sora.lsp.utils.createTextDocumentIdentifier
import io.github.rosemoe.sora.lang.styling.color.EditorColor
import io.github.rosemoe.sora.lang.styling.patching.StylePatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensDelta
import org.eclipse.lsp4j.SemanticTokensDeltaParams
import org.eclipse.lsp4j.SemanticTokensEdit
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensRangeParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@OptIn(FlowPreview::class)
class SemanticTokensEvent : AsyncEventListener() {
    private companion object {
        private const val MAX_STYLE_PATCHES = 50_000
        private const val DELTA_BACKOFF_MS = 15_000L
        private const val MAX_FULL_TOKENS_CACHE_INTS = 500_000
    }

    override val eventName: String = EventType.semanticTokens

    override val isAsync: Boolean = true

    var future: CompletableFuture<Void>? = null

    private val requestFlows = ConcurrentHashMap<FileUri, MutableSharedFlow<SemanticTokensRequest>>()
    private val requestSeqByUri = ConcurrentHashMap<FileUri, AtomicLong>()
    private val editorColorCache = ConcurrentHashMap<Int, EditorColor>()
    private val fullTokensCacheByUri = ConcurrentHashMap<FileUri, FullTokensCache>()
    private val deltaDisabledUntilByUri = ConcurrentHashMap<FileUri, Long>()

    private data class FullTokensCache(
        val resultId: String,
        val data: IntArray
    )

    data class SemanticTokensRequest(
        val editor: LspEditor,
        val uri: FileUri,
        val requestSeq: Long,
        val range: Range?
    )

    private fun getOrCreateFlow(
        coroutineScope: CoroutineScope,
        uri: FileUri
    ): MutableSharedFlow<SemanticTokensRequest> {
        return requestFlows.getOrPut(uri) {
            val flow = MutableSharedFlow<SemanticTokensRequest>(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )

            coroutineScope.launch(Dispatchers.Main) {
                flow
                    .debounce(120)
                    .collect { request ->
                        processSemanticTokensRequest(request)
                    }
            }

            flow
        }
    }

    override suspend fun handleAsync(context: EventContext) {
        val editor = context.get<LspEditor>("lsp-editor")
        if (!editor.isEnableSemanticTokens) {
            return
        }

        val uri = editor.uri
        val flow = getOrCreateFlow(editor.coroutineScope, uri)
        val seq = requestSeqByUri.getOrPut(uri) { AtomicLong(0) }.incrementAndGet()
        val range = context.getOrNull<Range>("arg0")
        flow.tryEmit(SemanticTokensRequest(editor, uri, seq, range))
    }

    private suspend fun processSemanticTokensRequest(request: SemanticTokensRequest) =
        withContext(Dispatchers.IO) {
            val editor = request.editor
            if (!editor.isEnableSemanticTokens) {
                return@withContext
            }

            val requestManager = editor.requestManager ?: return@withContext
            val capabilities = requestManager.capabilities ?: return@withContext
            val provider = capabilities.semanticTokensProvider ?: return@withContext
            val legend = provider.legend ?: return@withContext

            val textDocument = request.uri.createTextDocumentIdentifier()
            val params = SemanticTokensParams(textDocument)

            try {
                var usedFullTokens = false

                suspend fun requestFullTokens(): SemanticTokens? {
                    usedFullTokens = true

                    val now = SystemClock.elapsedRealtime()
                    val cached = fullTokensCacheByUri[request.uri]
                    val deltaDisabledUntil = deltaDisabledUntilByUri.getOrDefault(request.uri, 0L)
                    if (cached != null && now >= deltaDisabledUntil) {
                        val deltaParams = SemanticTokensDeltaParams().apply {
                            this.textDocument = textDocument
                            this.previousResultId = cached.resultId
                        }
                        val deltaFuture = requestManager.semanticTokensFullDelta(deltaParams)
                        if (deltaFuture != null) {
                            this@SemanticTokensEvent.future = deltaFuture.thenAccept { }
                            val deltaEither: Either<SemanticTokens, SemanticTokensDelta>? = runCatching {
                                deltaFuture.await()
                            }.getOrNull()

                            val tokensFromDelta = deltaEither?.let { either ->
                                if (either.isLeft) {
                                    either.left
                                } else {
                                    val delta = either.right
                                    val edits = delta.edits ?: emptyList()
                                    val newData = applyDeltaEdits(cached.data, edits)
                                    SemanticTokens().apply {
                                        data = newData.toList()
                                        resultId = delta.resultId
                                    }
                                }
                            }

                            if (tokensFromDelta != null) {
                                deltaDisabledUntilByUri.remove(request.uri)
                                return tokensFromDelta
                            }
                            deltaDisabledUntilByUri[request.uri] = now + DELTA_BACKOFF_MS
                        }
                    }

                    val fullFuture = requestManager.semanticTokensFull(params) ?: return null
                    this@SemanticTokensEvent.future = fullFuture.thenAccept { }
                    return fullFuture.await()
                }

                suspend fun requestRangeTokens(range: Range): SemanticTokens? {
                    val rangeParams = SemanticTokensRangeParams().apply {
                        this.textDocument = textDocument
                        this.range = range
                    }
                    val rangeFuture = requestManager.semanticTokensRange(rangeParams) ?: return null
                    this@SemanticTokensEvent.future = rangeFuture.thenAccept { }
                    return rangeFuture.await()
                }

                val semanticTokens: SemanticTokens? = withTimeout(Timeout[Timeouts.SEMANTIC_TOKENS].toLong()) {
                    val range = request.range
                    if (range != null) {
                        requestRangeTokens(range) ?: requestFullTokens()
                    } else {
                        requestFullTokens()
                    }
                }

                val latestSeq = requestSeqByUri[request.uri]?.get() ?: request.requestSeq
                if (latestSeq != request.requestSeq) {
                    return@withContext
                }

                if (usedFullTokens) {
                    val resultId = semanticTokens?.resultId
                    val data = semanticTokens?.data
                    if (resultId.isNullOrBlank() || data.isNullOrEmpty()) {
                        fullTokensCacheByUri.remove(request.uri)
                    } else if (data.size > MAX_FULL_TOKENS_CACHE_INTS) {
                        fullTokensCacheByUri.remove(request.uri)
                    } else {
                        val dataArray = IntArray(data.size) { idx -> data[idx] }
                        fullTokensCacheByUri[request.uri] = FullTokensCache(resultId, dataArray)
                    }
                }

                val patches = if (semanticTokens == null) {
                    null
                } else {
                    convertToStylePatches(editor, semanticTokens, legend)
                }

                val appliedRange = if (request.range != null && !usedFullTokens) request.range else null

                withContext(Dispatchers.Main) {
                    val latestSeqOnMain = requestSeqByUri[request.uri]?.get() ?: request.requestSeq
                    if (latestSeqOnMain != request.requestSeq) {
                        return@withContext
                    }
                    editor.showSemanticTokens(patches, appliedRange)
                }
            } catch (exception: Exception) {
                exception.printStackTrace()
                Log.e("LSP client", "semantic tokens request failed", exception)
            }
        }

    override fun dispose() {
        future?.cancel(true)
        future = null
        requestFlows.clear()
        requestSeqByUri.clear()
        editorColorCache.clear()
        fullTokensCacheByUri.clear()
        deltaDisabledUntilByUri.clear()
    }

    private fun applyDeltaEdits(previous: IntArray, edits: List<SemanticTokensEdit>): IntArray {
        if (edits.isEmpty()) return previous

        val sortedEdits = edits.sortedBy { it.start }
        var deleteTotal = 0
        var insertTotal = 0
        for (edit in sortedEdits) {
            deleteTotal += edit.deleteCount
            insertTotal += edit.data?.size ?: 0
        }

        val resultSize = previous.size - deleteTotal + insertTotal
        if (resultSize <= 0) return IntArray(0)

        val result = IntArray(resultSize)
        var resultIndex = 0
        var previousIndex = 0

        for (edit in sortedEdits) {
            val start = edit.start.coerceAtLeast(0).coerceAtMost(previous.size)
            val copyLen = (start - previousIndex).coerceAtLeast(0)
            if (copyLen > 0) {
                System.arraycopy(previous, previousIndex, result, resultIndex, copyLen)
                resultIndex += copyLen
            }

            previousIndex = (start + edit.deleteCount).coerceAtMost(previous.size)

            val insertData = edit.data
            if (!insertData.isNullOrEmpty()) {
                for (v in insertData) {
                    if (resultIndex >= result.size) break
                    result[resultIndex++] = v
                }
            }
        }

        val tailLen = (previous.size - previousIndex).coerceAtLeast(0)
        val remaining = result.size - resultIndex
        if (tailLen > 0 && remaining > 0) {
            System.arraycopy(previous, previousIndex, result, resultIndex, minOf(tailLen, remaining))
        }

        return result
    }

    private fun convertToStylePatches(editor: LspEditor, tokens: SemanticTokens, legend: SemanticTokensLegend): List<StylePatch>? {
        val data = tokens.data ?: return null
        if (data.isEmpty()) return null
        if (data.size % 5 != 0) return null

        val styleMapper = editor.project.semanticTokensStyleProvider.createMapper(editor, legend)
        val colorIdByTokenTypeIndex = styleMapper.foregroundColorIdByTokenTypeIndex
        val baseAttrsByTokenTypeIndex = styleMapper.baseTextAttributesByTokenTypeIndex

        var line = 0
        var character = 0

        val patches = ArrayList<StylePatch>(data.size / 5)
        var lastPatch: StylePatch? = null
        var i = 0
        while (i < data.size) {
            val deltaLine = data[i]
            val deltaStart = data[i + 1]
            val length = data[i + 2]
            val tokenTypeIndex = data[i + 3]
            val tokenModifiersBitset = data[i + 4]
            i += 5

            line += deltaLine
            character = if (deltaLine == 0) character + deltaStart else deltaStart
            if (length <= 0) continue

            if (tokenTypeIndex < 0 || tokenTypeIndex >= colorIdByTokenTypeIndex.size) continue
            val colorId = colorIdByTokenTypeIndex[tokenTypeIndex]
            if (colorId < 0) continue

            val baseAttrs = if (tokenTypeIndex < baseAttrsByTokenTypeIndex.size) baseAttrsByTokenTypeIndex[tokenTypeIndex] else 0
            val modifierAttrs = if (tokenModifiersBitset == 0) 0 else styleMapper.modifierTextAttributes(tokenModifiersBitset)
            val attrs = baseAttrs or modifierAttrs

            val foreground = editorColorCache.computeIfAbsent(colorId) { EditorColor(it) }
            val bold = if ((attrs and SemanticTokenTextAttributes.BOLD) != 0) true else null
            val italics = if ((attrs and SemanticTokenTextAttributes.ITALICS) != 0) true else null
            val strikeThrough = if ((attrs and SemanticTokenTextAttributes.STRIKETHROUGH) != 0) true else null

            val start = character
            val end = character + length
            if (end <= start) continue

            val last = lastPatch
            if (last != null &&
                last.startLine == line &&
                last.endColumn == start &&
                last.overrideForeground === foreground &&
                last.overrideBackground == null &&
                last.overrideItalics == italics &&
                last.overrideBold == bold &&
                last.overrideStrikeThrough == strikeThrough
            ) {
                last.endColumn = end
            } else {
                val patch = StylePatch(line, start, line, end).apply {
                    overrideForeground = foreground
                    overrideItalics = italics
                    overrideBold = bold
                    overrideStrikeThrough = strikeThrough
                }
                patches.add(patch)
                if (patches.size >= MAX_STYLE_PATCHES) {
                    Log.w("LSP client", "semantic tokens ignored: too many patches ($MAX_STYLE_PATCHES)")
                    return null
                }
                lastPatch = patch
            }
        }

        return patches.ifEmpty { null }
    }
}

val EventType.semanticTokens: String
    get() = "textDocument/semanticTokens/full"
