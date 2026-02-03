/*
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
 */
package io.github.rosemoe.sora.widget.component;

import androidx.annotation.NonNull;

import java.util.List;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * 文本操作菜单分组接口
 * <p>
 * 实现此接口可以向文本操作窗口添加可折叠的菜单分组。
 * 每个分组在主菜单中显示为一个带有展开指示器的按钮，
 * 点击后展开显示该分组下的所有菜单项。
 *
 * <p>使用示例：
 * <pre>
 * public class EditMenuGroup implements TextActionMenuGroup {
 *     {@literal @}Override
 *     public String getGroupLabel() { return "编辑"; }
 *
 *     {@literal @}Override
 *     public List&lt;MenuItem&gt; getMenuItems(CodeEditor editor) {
 *         return Arrays.asList(
 *             new MenuItem("撤销", () -> editor.undo()),
 *             new MenuItem("重做", () -> editor.redo())
 *         );
 *     }
 * }
 * </pre>
 *
 * @author TinaIDE
 */
public interface TextActionMenuGroup {

    /**
     * 获取分组在主菜单中显示的标签文本
     * @return 分组标签，如 "编辑"、"导航" 等
     */
    @NonNull
    String getGroupLabel();

    /**
     * 获取该分组下的所有菜单项
     * @param editor 编辑器实例，用于判断菜单项状态
     * @return 菜单项列表
     */
    @NonNull
    List<MenuItem> getMenuItems(@NonNull CodeEditor editor);

    /**
     * 判断分组是否应该显示（用于条件显示）
     * 默认返回 true
     * @param editor 编辑器实例
     * @return 如果应该显示返回 true
     */
    default boolean shouldShowGroup(@NonNull CodeEditor editor) {
        return true;
    }

    /**
     * 获取分组的优先级（用于排序）
     * 数值越小越靠前，默认为 100
     * @return 优先级数值
     */
    default int getPriority() {
        return 100;
    }

    /**
     * 菜单项数据类
     */
    class MenuItem {
        private final String label;
        private final Runnable action;
        private final EnabledChecker enabledChecker;
        private final VisibilityChecker visibilityChecker;

        /**
         * 创建一个始终可见且可用的菜单项
         * @param label 菜单项标签
         * @param action 点击时执行的动作
         */
        public MenuItem(@NonNull String label, @NonNull Runnable action) {
            this(label, action, null, null);
        }

        /**
         * 创建一个带条件判断的菜单项
         * @param label 菜单项标签
         * @param action 点击时执行的动作
         * @param enabledChecker 判断是否可用的检查器（null 表示始终可用）
         * @param visibilityChecker 判断是否可见的检查器（null 表示始终可见）
         */
        public MenuItem(@NonNull String label, @NonNull Runnable action,
                       EnabledChecker enabledChecker, VisibilityChecker visibilityChecker) {
            this.label = label;
            this.action = action;
            this.enabledChecker = enabledChecker;
            this.visibilityChecker = visibilityChecker;
        }

        @NonNull
        public String getLabel() {
            return label;
        }

        @NonNull
        public Runnable getAction() {
            return action;
        }

        /**
         * 检查菜单项是否可用
         * @param editor 编辑器实例
         * @return 如果可用返回 true
         */
        public boolean isEnabled(@NonNull CodeEditor editor) {
            return enabledChecker == null || enabledChecker.isEnabled(editor);
        }

        /**
         * 检查菜单项是否应该显示
         * @param editor 编辑器实例
         * @return 如果应该显示返回 true
         */
        public boolean isVisible(@NonNull CodeEditor editor) {
            return visibilityChecker == null || visibilityChecker.isVisible(editor);
        }
    }

    /**
     * 菜单项可用性检查器
     */
    @FunctionalInterface
    interface EnabledChecker {
        boolean isEnabled(@NonNull CodeEditor editor);
    }

    /**
     * 菜单项可见性检查器
     */
    @FunctionalInterface
    interface VisibilityChecker {
        boolean isVisible(@NonNull CodeEditor editor);
    }
}
