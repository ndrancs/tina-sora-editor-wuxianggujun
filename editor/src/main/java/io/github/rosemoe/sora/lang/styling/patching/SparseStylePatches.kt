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

import java.lang.IllegalStateException
import java.lang.UnsupportedOperationException
import java.util.Arrays
import java.util.Collections

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
class SparseStylePatches {

    private val patches = mutableListOf<StylePatch>()

    private var immutable = false

    private fun getInsertionPoint(patch: StylePatch): Int {
        val result = patches.binarySearch(patch)
        val insertionPoint = if (result < 0) {
            -(result + 1)
        } else {
            result
        }
        return insertionPoint
    }

    fun addPatch(patch: StylePatch) {
        if (immutable) throw IllegalStateException("the patch list is already set immutable")
        if (patch.startLine != patch.endLine) throw UnsupportedOperationException("crossline patch is not supported now")
        patches.add(getInsertionPoint(patch), patch)
    }

    fun addAll(patches: Iterable<StylePatch>) {
        if (immutable) throw IllegalStateException("the patch list is already set immutable")

        // Hot path: Replace an empty list (common for semantic tokens updates)
        if (this.patches.isEmpty()) {
            for (patch in patches) {
                if (patch.startLine != patch.endLine) {
                    throw UnsupportedOperationException("crossline patch is not supported now")
                }
                this.patches.add(patch)
            }
            this.patches.sort()
            return
        }

        for (patch in patches) {
            addPatch(patch)
        }
    }

    fun clear() {
        if (immutable) throw IllegalStateException("the patch list is already set immutable")
        patches.clear()
    }

    fun isEmpty(): Boolean = patches.isEmpty()

    fun size(): Int = patches.size

    fun getPatchesOnLine(line: Int, out: MutableList<StylePatch>) {
        out.clear()
        val startIndex = findFirstPatchIndexOnLine(line)
        if (startIndex < 0) return
        var i = startIndex
        while (i < patches.size) {
            val patch = patches[i]
            if (patch.startLine != line) break
            out.add(patch)
            i++
        }
    }

    fun removeLineRange(startLine: Int, endLine: Int) {
        if (immutable) throw IllegalStateException("the patch list is already set immutable")
        if (patches.isEmpty()) return
        if (startLine > endLine) return

        val coordinator = StylePatch(startLine, 0, startLine, 0)
        var index = getInsertionPoint(coordinator)
        while (index < patches.size) {
            val patch = patches[index]
            if (patch.startLine > endLine) break
            patches.removeAt(index)
        }
    }

    fun replaceLineRange(startLine: Int, endLine: Int, patches: Iterable<StylePatch>) {
        if (immutable) throw IllegalStateException("the patch list is already set immutable")
        if (startLine > endLine) return

        removeLineRange(startLine, endLine)

        val toInsert = when (patches) {
            is Collection<*> -> ArrayList<StylePatch>(patches.size)
            else -> ArrayList()
        }
        for (patch in patches) {
            if (patch.startLine != patch.endLine) {
                throw UnsupportedOperationException("crossline patch is not supported now")
            }
            toInsert.add(patch)
        }
        if (toInsert.isEmpty()) return
        toInsert.sort()

        val insertionPoint = getInsertionPoint(StylePatch(startLine, 0, startLine, 0))
        this.patches.addAll(insertionPoint, toInsert)
    }

    fun pruneOutsideLineRange(startLine: Int, endLine: Int) {
        if (immutable) throw IllegalStateException("the patch list is already set immutable")
        if (patches.isEmpty()) return

        if (startLine > endLine) {
            patches.clear()
            return
        }

        val startIndex = getInsertionPoint(StylePatch(startLine, 0, startLine, 0))
        if (startIndex > 0) {
            patches.subList(0, startIndex).clear()
        }

        val afterEndLine = if (endLine == Int.MAX_VALUE) Int.MAX_VALUE else endLine + 1
        val endIndex = getInsertionPoint(StylePatch(afterEndLine, 0, afterEndLine, 0))
        if (endIndex < patches.size) {
            patches.subList(endIndex, patches.size).clear()
        }
    }

    fun setImmutable() {
        immutable = true
    }

    fun updateForInsertion(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) {
        val coordinator = StylePatch(startLine, 0, startLine, 0)
        var index = getInsertionPoint(coordinator)
        val delta = endLine - startLine
        while (index < patches.size) {
            val e = patches[index++]
            if (e.startLine == startLine && e.startColumn >= startColumn) {
                val length = e.endColumn - e.startColumn
                e.startLine = endLine
                e.endLine = endLine
                e.startColumn = endColumn + (e.startColumn - startColumn)
                e.endColumn = e.startColumn + length
            } else if (e.startLine > startLine) {
                if (delta == 0) break
                e.startLine += delta
                e.endLine += delta
            }
        }
    }

    fun updateForDeletion(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) {
        if (patches.isEmpty()) return
        if (startLine > endLine || (startLine == endLine && startColumn >= endColumn)) return

        if (startLine == endLine) {
            updateForSingleLineDeletion(startLine, startColumn, endColumn)
            return
        }

        updateForMultiLineDeletion(startLine, startColumn, endLine, endColumn)
    }

    private fun updateForSingleLineDeletion(line: Int, startColumn: Int, endColumn: Int) {
        val coordinator = StylePatch(line, 0, line, 0)
        var index = getInsertionPoint(coordinator)
        val deltaColumns = endColumn - startColumn
        while (index < patches.size) {
            val patch = patches[index]
            if (patch.startLine != line) break

            val patchStart = patch.startColumn
            val patchEnd = patch.endColumn

            if (patchEnd <= startColumn) {
                index++
                continue
            }

            if (patchStart >= endColumn) {
                patch.startColumn = patchStart - deltaColumns
                patch.endColumn = patchEnd - deltaColumns
                index++
                continue
            }

            // Overlap
            when {
                patchStart >= startColumn && patchEnd <= endColumn -> {
                    patches.removeAt(index)
                    continue
                }

                patchStart < startColumn && patchEnd > endColumn -> {
                    patch.endColumn = patchEnd - deltaColumns
                    index++
                }

                patchStart < startColumn && patchEnd <= endColumn -> {
                    patch.endColumn = startColumn
                    if (patch.endColumn <= patch.startColumn) {
                        patches.removeAt(index)
                        continue
                    }
                    index++
                }

                patchStart >= startColumn && patchEnd > endColumn -> {
                    patch.startColumn = startColumn
                    patch.endColumn = patchEnd - deltaColumns
                    if (patch.endColumn <= patch.startColumn) {
                        patches.removeAt(index)
                        continue
                    }
                    index++
                }

                else -> {
                    index++
                }
            }
        }
    }

    private fun updateForMultiLineDeletion(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) {
        val coordinator = StylePatch(startLine, 0, startLine, 0)
        var index = getInsertionPoint(coordinator)
        val deltaLines = endLine - startLine
        val movedPatchesToStartLine = mutableListOf<StylePatch>()

        while (index < patches.size) {
            val patch = patches[index]
            val line = patch.startLine
            when {
                line == startLine -> {
                    if (patch.endColumn <= startColumn) {
                        index++
                        continue
                    }
                    if (patch.startColumn < startColumn) {
                        patch.endColumn = startColumn
                        if (patch.endColumn <= patch.startColumn) {
                            patches.removeAt(index)
                            continue
                        }
                        index++
                        continue
                    }
                    patches.removeAt(index)
                    continue
                }

                line in (startLine + 1) until endLine -> {
                    patches.removeAt(index)
                    continue
                }

                line == endLine -> {
                    if (patch.endColumn <= endColumn) {
                        patches.removeAt(index)
                        continue
                    }
                    val newStartColumn = if (patch.startColumn < endColumn) {
                        startColumn
                    } else {
                        startColumn + (patch.startColumn - endColumn)
                    }
                    val newEndColumn = startColumn + (patch.endColumn - endColumn)
                    val movedPatch = StylePatch(startLine, newStartColumn, startLine, newEndColumn).also {
                        it.overrideForeground = patch.overrideForeground
                        it.overrideBackground = patch.overrideBackground
                        it.overrideItalics = patch.overrideItalics
                        it.overrideBold = patch.overrideBold
                        it.overrideStrikeThrough = patch.overrideStrikeThrough
                    }
                    if (movedPatch.endColumn > movedPatch.startColumn) {
                        movedPatchesToStartLine.add(movedPatch)
                    }
                    patches.removeAt(index)
                    continue
                }

                line > endLine -> {
                    patch.startLine = line - deltaLines
                    patch.endLine = patch.endLine - deltaLines
                    index++
                }

                else -> {
                    index++
                }
            }
        }

        if (movedPatchesToStartLine.isNotEmpty()) {
            movedPatchesToStartLine.sort()
            val insertionPoint = getInsertionPoint(StylePatch(startLine, startColumn, startLine, startColumn))
            patches.addAll(insertionPoint, movedPatchesToStartLine)
        }
    }

    private fun findFirstPatchIndexOnLine(line: Int): Int {
        var low = 0
        var high = patches.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midLine = patches[mid].startLine
            when {
                midLine < line -> low = mid + 1
                midLine > line -> high = mid - 1
                else -> {
                    result = mid
                    high = mid - 1
                }
            }
        }
        return result
    }

}
