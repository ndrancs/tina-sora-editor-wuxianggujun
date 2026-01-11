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

package io.github.rosemoe.sora.lsp.utils

import java.io.File
import java.net.URI
import kotlin.io.path.pathString
import kotlin.io.path.toPath

/**
 * 将 FileUri 转换为 LSP 协议的 file:// URI 字符串
 *
 * 注意：确保路径是规范化的文件路径（而非目录）
 */
fun FileUri.toFileUri(): String {
    val normalizedPath = this.path.normalizePath()
    return "file://$normalizedPath"
}

/**
 * 从字符串路径创建 FileUri
 *
 * 会自动规范化路径（处理反斜杠、多余斜杠等）
 */
fun String.toFileUri(): FileUri {
    return FileUri(this.normalizePath())
}

fun String.toURI(): URI {
    return URI(this)
}

fun URI.toFileUri(): FileUri {
    return FileUri(this.toPath().pathString.normalizePath())
}

/**
 * FileUri 值类：表示文件的路径标识符
 *
 * 建议：传入的 path 应该是文件的绝对路径，而不是目录
 */
@JvmInline
value class FileUri(
    val path: String
) {
    /**
     * 转换为 File 对象
     */
    fun toFile(): File {
        return File(toUri())
    }

    /**
     * 转换为 URI 对象
     */
    fun toUri(): URI {
        return URI(this.toFileUri())
    }

    /**
     * 检查路径是否看起来像一个文件（而非目录）
     *
     * 注意：这只是启发式检查，不保证文件系统中确实存在
     */
    fun looksLikeFile(): Boolean {
        val file = File(path)
        return when {
            // 如果文件存在，直接判断
            file.exists() -> file.isFile
            // 如果不存在，检查是否有文件扩展名
            else -> file.extension.isNotEmpty()
        }
    }
}

/**
 * 路径规范化：统一使用正斜杠 /，移除多余斜杠
 *
 * 示例：
 * - "C:\\Users\\test\\main.cpp" -> "/C:/Users/test/main.cpp"
 * - "/path//to///file.cpp" -> "/path/to/file.cpp"
 * - "path/to/file.cpp" -> "/path/to/file.cpp" (补充前导 /)
 */
private fun String.normalizePath(): String {
    var normalized = this.replace('\\', '/')

    // 移除多余的连续斜杠
    normalized = normalized.replace(Regex("/+"), "/")

    // 确保路径以 / 开头（LSP file:// URI 要求）
    if (!normalized.startsWith("/")) {
        // Windows 绝对路径：C:/... -> /C:/...
        normalized = "/$normalized"
    }

    return normalized
}
