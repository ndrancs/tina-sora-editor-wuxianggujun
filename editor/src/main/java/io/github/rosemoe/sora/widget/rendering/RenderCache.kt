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

package io.github.rosemoe.sora.widget.rendering

import androidx.collection.MutableIntList
import java.util.LinkedHashMap

/**
 * Cache for editor rendering, including line-based data and measure
 * cache for recently accessed lines.
 *
 * This object is expected to be accessed from UI thread.
 *
 * @author Rosemoe
 */
class RenderCache {
    private val lines = MutableIntList()
    private var maxCacheCount = 75
    private var cache = createMeasureCache()

    private fun createMeasureCache(): LinkedHashMap<Int, MeasureCacheItem> {
        return object : LinkedHashMap<Int, MeasureCacheItem>(maxCacheCount, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, MeasureCacheItem>?): Boolean {
                return size > maxCacheCount
            }
        }
    }

    fun getOrCreateMeasureCache(line: Int): MeasureCacheItem {
        return cache[line] ?: MeasureCacheItem(line, null, 0L).also { cache[line] = it }
    }

    fun queryMeasureCache(line: Int) = cache[line]


    fun getStyleHash(line: Int) = lines[line]

    fun setStyleHash(line: Int, hash: Int) {
        lines[line] = hash
    }

    fun updateForInsertion(startLine: Int, endLine: Int) {
        if (startLine != endLine) {
            val deltaLines = endLine - startLine
            if (endLine - startLine == 1) {
                lines.add(startLine, 0)
            } else {
                lines.addAll(startLine, IntArray(endLine - startLine))
            }
            if (cache.isNotEmpty()) {
                val oldCache = cache
                cache = createMeasureCache()
                for ((_, item) in oldCache) {
                    val newLine = if (item.line > startLine) item.line + deltaLines else item.line
                    item.line = newLine
                    cache[newLine] = item
                }
            }
        }
    }

    fun updateForDeletion(startLine: Int, endLine: Int) {
        if (startLine != endLine) {
            val deltaLines = endLine - startLine
            lines.removeRange(startLine, endLine)
            if (cache.isNotEmpty()) {
                val oldCache = cache
                cache = createMeasureCache()
                for ((_, item) in oldCache) {
                    when {
                        item.line in startLine..endLine -> Unit
                        item.line > endLine -> {
                            val newLine = item.line - deltaLines
                            item.line = newLine
                            cache[newLine] = item
                        }
                        else -> cache[item.line] = item
                    }
                }
            }
        }
    }

    fun reset(lineCount: Int) {
        if (lines.size > lineCount) {
            lines.removeRange(lineCount, lines.size)
        } else if (lines.size < lineCount) {
            repeat(lineCount - lines.size) {
                lines.add(0)
            }
        }
        lines.indices.forEach { lines[it] = 0 }
        cache.clear()
    }

}
