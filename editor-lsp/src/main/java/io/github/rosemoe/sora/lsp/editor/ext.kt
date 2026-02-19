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

package io.github.rosemoe.sora.lsp.editor

import android.graphics.Color
import io.github.rosemoe.sora.lang.styling.color.ConstColor
import io.github.rosemoe.sora.lang.styling.inlayHint.ColorInlayHint
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.events.color.documentColor
import io.github.rosemoe.sora.lsp.events.inlayhint.inlayHint
import io.github.rosemoe.sora.lsp.events.semantictokens.semanticTokens
import io.github.rosemoe.sora.text.CharPosition
import org.eclipse.lsp4j.ColorInformation
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.Range
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.math.pow

suspend fun LspEditor.requestInlayHint(position: CharPosition) {
    if (!isEnableInlayHint) return

    eventManager.emitAsync(EventType.inlayHint) {
        put(position)
    }
}

suspend fun LspEditor.requestDocumentColor() {
    if (!isEnableInlayHint) return

    eventManager.emitAsync(EventType.documentColor)
}

suspend fun LspEditor.requestSemanticTokens(range: Range? = null) {
    if (!isEnableSemanticTokens) return
    if (range == null) {
        eventManager.emitAsync(EventType.semanticTokens)
    } else {
        eventManager.emitAsync(EventType.semanticTokens, range)
    }
}

internal fun curvedTextScale(rawScale: Float): Float {
    if (!rawScale.isFinite() || rawScale <= 0f) {
        return 1f
    }
    if (rawScale == 1f) {
        return 1f
    }
    return if (rawScale > 1f) {
        val diff = rawScale - 1f
        val curved = diff.toDouble().pow(0.75).toFloat()
        1f + diff + curved * 0.2f
    } else {
        val diff = 1f - rawScale
        val curved = diff.toDouble().pow(1.5).toFloat()
        (1f - curved).coerceAtLeast(0f)
    }
}

fun List<InlayHint>.inlayHintToDisplay() =
    asSequence()
        .mapNotNull { hint ->
            val text = if (hint.label.isLeft) {
                hint.label.left
            } else {
                hint.label.right
                    .orEmpty()
                    .joinToString(separator = "") { part -> part.value.orEmpty() }
            }
            if (text.isBlank()) {
                return@mapNotNull null
            }
            io.github.rosemoe.sora.lang.styling.inlayHint.TextInlayHint(
                hint.position.line,
                hint.position.character,
                text
            )
        }
        .toList()

fun List<ColorInformation>.colorInfoToDisplay() = map {
    // Always show on start
    // May we should use style patch to set background?
    ColorInlayHint(
        it.range.start.line, it.range.start.character,
        ConstColor(
            Color.argb(
                it.color.alpha.toFloat(),
                it.color.red.toFloat(),
                it.color.green.toFloat(),
                it.color.blue.toFloat()
            )
        )
    )
}

@OptIn(ExperimentalContracts::class)
fun <T> List<T>?.normalizeList(): List<T>? {
    contract {
        returnsNotNull() implies (this@normalizeList != null)
    }
    return if (this.isNullOrEmpty()) null else this
}

