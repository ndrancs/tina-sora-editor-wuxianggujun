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
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this library; if not, write to the Free Software Foundation,
 *     Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/

package io.github.rosemoe.sora.lsp.events.semantictokens

import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes
import org.eclipse.lsp4j.SemanticTokensLegend
import java.util.Locale

interface SemanticTokensStyleProvider {
    fun createMapper(editor: LspEditor, legend: SemanticTokensLegend): SemanticTokensStyleMapper
}

interface SemanticTokensStyleMapper {
    /**
     * Indexed by legend token type index.
     *
     * -1 means this token type should be ignored.
     */
    val foregroundColorIdByTokenTypeIndex: IntArray

    /**
     * Base text attributes for each token type index. Bitmask values are in [SemanticTokenTextAttributes].
     */
    val baseTextAttributesByTokenTypeIndex: IntArray

    /**
     * Returns the text attributes (bitmask) derived from a token modifiers bitset.
     */
    fun modifierTextAttributes(tokenModifiersBitset: Int): Int
}

object SemanticTokenTextAttributes {
    const val BOLD = 1
    const val ITALICS = 1 shl 1
    const val STRIKETHROUGH = 1 shl 2
}

object DefaultSemanticTokensStyleProvider : SemanticTokensStyleProvider {

    override fun createMapper(editor: LspEditor, legend: SemanticTokensLegend): SemanticTokensStyleMapper {
        val tokenTypes = legend.tokenTypes ?: emptyList()
        val tokenModifiers = legend.tokenModifiers ?: emptyList()

        val foregroundByTypeIndex = IntArray(tokenTypes.size) { idx ->
            mapTokenTypeToColorId(tokenTypes[idx]) ?: -1
        }
        val baseAttrsByTypeIndex = IntArray(tokenTypes.size)

        val deprecatedIndex = tokenModifiers.indexOf(SemanticTokenModifiers.Deprecated)
        val readonlyIndex = tokenModifiers.indexOf(SemanticTokenModifiers.Readonly)
        val abstractIndex = tokenModifiers.indexOf(SemanticTokenModifiers.Abstract)

        return DefaultSemanticTokensStyleMapper(
            foregroundColorIdByTokenTypeIndex = foregroundByTypeIndex,
            baseTextAttributesByTokenTypeIndex = baseAttrsByTypeIndex,
            deprecatedIndex = deprecatedIndex,
            readonlyIndex = readonlyIndex,
            abstractIndex = abstractIndex
        )
    }

    private class DefaultSemanticTokensStyleMapper(
        override val foregroundColorIdByTokenTypeIndex: IntArray,
        override val baseTextAttributesByTokenTypeIndex: IntArray,
        private val deprecatedIndex: Int,
        private val readonlyIndex: Int,
        private val abstractIndex: Int
    ) : SemanticTokensStyleMapper {

        override fun modifierTextAttributes(tokenModifiersBitset: Int): Int {
            var attrs = 0
            if (deprecatedIndex >= 0 && (tokenModifiersBitset and (1 shl deprecatedIndex)) != 0) {
                attrs = attrs or SemanticTokenTextAttributes.STRIKETHROUGH
            }
            if (readonlyIndex >= 0 && (tokenModifiersBitset and (1 shl readonlyIndex)) != 0) {
                attrs = attrs or SemanticTokenTextAttributes.BOLD
            }
            if (abstractIndex >= 0 && (tokenModifiersBitset and (1 shl abstractIndex)) != 0) {
                attrs = attrs or SemanticTokenTextAttributes.ITALICS
            }
            return attrs
        }
    }

    private fun mapTokenTypeToColorId(tokenType: String): Int? {
        return when (tokenType) {
            SemanticTokenTypes.Keyword,
            SemanticTokenTypes.Modifier -> EditorColorScheme.KEYWORD

            SemanticTokenTypes.Comment -> EditorColorScheme.COMMENT

            SemanticTokenTypes.Operator -> EditorColorScheme.OPERATOR

            SemanticTokenTypes.String,
            SemanticTokenTypes.Number,
            SemanticTokenTypes.Regexp -> EditorColorScheme.LITERAL

            SemanticTokenTypes.Function,
            SemanticTokenTypes.Method -> EditorColorScheme.FUNCTION_NAME

            SemanticTokenTypes.Macro,
            SemanticTokenTypes.Decorator -> EditorColorScheme.ANNOTATION

            SemanticTokenTypes.Namespace,
            SemanticTokenTypes.Type,
            SemanticTokenTypes.Class,
            SemanticTokenTypes.Enum,
            SemanticTokenTypes.Interface,
            SemanticTokenTypes.Struct,
            SemanticTokenTypes.TypeParameter -> EditorColorScheme.IDENTIFIER_NAME

            SemanticTokenTypes.Parameter,
            SemanticTokenTypes.Variable,
            SemanticTokenTypes.Property,
            SemanticTokenTypes.EnumMember,
            SemanticTokenTypes.Event -> EditorColorScheme.IDENTIFIER_VAR

            else -> {
                val normalized = tokenType.lowercase(Locale.ROOT)
                when (normalized) {
                    "tag",
                    "xmltag",
                    "htmltag" -> EditorColorScheme.HTML_TAG

                    "attribute",
                    "attributename" -> EditorColorScheme.ATTRIBUTE_NAME

                    "attributevalue" -> EditorColorScheme.ATTRIBUTE_VALUE

                    "builtintype",
                    "typealias",
                    "generic",
                    "lifetime",
                    "label" -> EditorColorScheme.IDENTIFIER_NAME

                    "this",
                    "self",
                    "selfkeyword",
                    "selftypekeyword" -> EditorColorScheme.KEYWORD

                    "boolean",
                    "bool",
                    "constant" -> EditorColorScheme.LITERAL

                    else -> when {
                        normalized.contains("keyword") -> EditorColorScheme.KEYWORD
                        normalized.contains("comment") -> EditorColorScheme.COMMENT
                        normalized.contains("operator") -> EditorColorScheme.OPERATOR
                        normalized.contains("string") || normalized.contains("number") || normalized.contains("regexp") -> EditorColorScheme.LITERAL
                        normalized.contains("function") || normalized.contains("method") -> EditorColorScheme.FUNCTION_NAME
                        normalized.contains("annotation") || normalized.contains("decorator") || normalized.contains("attribute") || normalized.contains("macro") -> EditorColorScheme.ANNOTATION
                        normalized.contains("tag") -> EditorColorScheme.HTML_TAG
                        normalized.contains("type") || normalized.contains("class") || normalized.contains("struct") || normalized.contains("enum") || normalized.contains("interface") -> EditorColorScheme.IDENTIFIER_NAME
                        normalized.contains("variable") || normalized.contains("property") || normalized.contains("parameter") -> EditorColorScheme.IDENTIFIER_VAR
                        else -> null
                    }
                }
            }
        }
    }
}

