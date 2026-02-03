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

import android.annotation.SuppressLint;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.github.rosemoe.sora.R;
import io.github.rosemoe.sora.event.ColorSchemeUpdateEvent;
import io.github.rosemoe.sora.event.DragSelectStopEvent;
import io.github.rosemoe.sora.event.EditorFocusChangeEvent;
import io.github.rosemoe.sora.event.EditorReleaseEvent;
import io.github.rosemoe.sora.event.EventManager;
import io.github.rosemoe.sora.event.HandleStateChangeEvent;
import io.github.rosemoe.sora.event.InterceptTarget;
import io.github.rosemoe.sora.event.LongPressEvent;
import io.github.rosemoe.sora.event.ScrollEvent;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorTouchEventHandler;
import io.github.rosemoe.sora.widget.base.EditorPopupWindow;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * 垂直分组折叠式文本操作菜单窗口
 * <p>
 * 当选中文本时显示此窗口，提供文本操作功能。
 * 菜单采用垂直布局，点击分组标题展开二级菜单。
 * <p>
 * 支持：
 * <ul>
 *     <li>分组菜单：可注册 {@link TextActionMenuGroup} 添加可折叠的菜单分组</li>
 *     <li>单项扩展：可注册 {@link ExtraButtonProvider} 添加单个扩展按钮</li>
 * </ul>
 *
 * @author Rosemoe
 * @author TinaIDE
 */
public class EditorTextActionWindow extends EditorPopupWindow implements EditorBuiltinComponent {
    private final static long DELAY = 200;
    private final static long CHECK_FOR_DISMISS_INTERVAL = 100;
    private final CodeEditor editor;

    // 面板视图
    private final View rootView;
    private final ViewGroup mainMenuPanel;
    private final ViewGroup subMenuPanel;
    private final ViewGroup groupTitlesContainer;
    private final ViewGroup subItemsContainer;
    private final TextView collapseBtn;
    private final View divider;

    private final EditorTouchEventHandler handler;
    private final EventManager eventManager;
    private long lastScroll;
    private int lastPosition;
    private int lastCause;
    private boolean enabled = true;

    // 分组菜单
    private final List<MenuGroupEntry> menuGroups = new ArrayList<>();
    @Nullable
    private TextActionMenuGroup expandedGroup = null;

    // 单项扩展按钮（向后兼容）
    private final List<ExtraButtonEntry> extraButtonEntries = new ArrayList<>();

    /**
     * Create a panel for the given editor
     *
     * @param editor Target editor
     */
    public EditorTextActionWindow(CodeEditor editor) {
        super(editor, FEATURE_SHOW_OUTSIDE_VIEW_ALLOWED);
        this.editor = editor;
        handler = editor.getEventHandler();
        eventManager = editor.createSubEventManager();

        // Since popup window does provide decor view, we have to pass null to this method
        @SuppressLint("InflateParams")
        View root = this.rootView = LayoutInflater.from(editor.getContext()).inflate(R.layout.text_compose_panel, null);

        // 主菜单面板
        mainMenuPanel = root.findViewById(R.id.panel_main_menu);
        groupTitlesContainer = root.findViewById(R.id.panel_group_titles_container);

        // 子菜单面板
        subMenuPanel = root.findViewById(R.id.panel_sub_menu);
        subItemsContainer = root.findViewById(R.id.panel_sub_items_container);
        collapseBtn = root.findViewById(R.id.panel_btn_collapse);
        divider = root.findViewById(R.id.panel_divider);

        collapseBtn.setOnClickListener(v -> collapseSubMenu());

        applyColorScheme();
        setContentView(root);
        getPopup().setAnimationStyle(R.style.text_action_popup_animation);

        subscribeEvents();
    }

    protected void applyTextColor(TextView textView, int color) {
        textView.setTextColor(color);
        // 同时着色 drawable
        for (Drawable drawable : textView.getCompoundDrawablesRelative()) {
            if (drawable != null) {
                drawable.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
            }
        }
    }

    protected void applyColorScheme() {
        int bgColor = editor.getColorScheme().getColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND);
        int textColor = editor.getColorScheme().getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR);

        // 主菜单面板背景
        GradientDrawable mainBg = new GradientDrawable();
        mainBg.setCornerRadius(8 * editor.getDpUnit());
        mainBg.setColor(bgColor);
        mainMenuPanel.setBackground(mainBg);

        // 子菜单面板背景
        GradientDrawable subBg = new GradientDrawable();
        subBg.setCornerRadius(8 * editor.getDpUnit());
        subBg.setColor(bgColor);
        subMenuPanel.setBackground(subBg);

        // 收起按钮颜色
        applyTextColor(collapseBtn, textColor);

        // 分隔线颜色
        divider.setBackgroundColor((textColor & 0x00FFFFFF) | 0x20000000);

        // 分组标题按钮颜色
        for (MenuGroupEntry entry : menuGroups) {
            applyTextColor(entry.titleButton, textColor);
        }

        // 额外按钮颜色
        for (ExtraButtonEntry entry : extraButtonEntries) {
            applyTextColor(entry.button, textColor);
        }
    }

    protected void subscribeEvents() {
        eventManager.subscribeAlways(SelectionChangeEvent.class, this::onSelectionChange);
        eventManager.subscribeAlways(ScrollEvent.class, this::onEditorScroll);
        eventManager.subscribeAlways(HandleStateChangeEvent.class, this::onHandleStateChange);
        eventManager.subscribeAlways(LongPressEvent.class, this::onEditorLongPress);
        eventManager.subscribeAlways(EditorFocusChangeEvent.class, this::onEditorFocusChange);
        eventManager.subscribeAlways(EditorReleaseEvent.class, this::onEditorRelease);
        eventManager.subscribeAlways(ColorSchemeUpdateEvent.class, this::onEditorColorChange);
        eventManager.subscribeAlways(DragSelectStopEvent.class, this::onDragSelectingStop);
    }

    protected void onEditorColorChange(@NonNull ColorSchemeUpdateEvent event) {
        applyColorScheme();
    }

    protected void onEditorFocusChange(@NonNull EditorFocusChangeEvent event) {
        if (!event.isGainFocus()) {
            dismiss();
        }
    }

    protected void onDragSelectingStop(@NonNull DragSelectStopEvent event) {
        displayWindow();
    }

    protected void onEditorRelease(@NonNull EditorReleaseEvent event) {
        setEnabled(false);
    }

    protected void onEditorLongPress(@NonNull LongPressEvent event) {
        if (editor.getCursor().isSelected() && lastCause == SelectionChangeEvent.CAUSE_SEARCH) {
            var idx = event.getIndex();
            if (idx >= editor.getCursor().getLeft() && idx <= editor.getCursor().getRight()) {
                lastCause = 0;
                displayWindow();
            }
            event.intercept(InterceptTarget.TARGET_EDITOR);
        }
    }

    protected void onEditorScroll(@NonNull ScrollEvent event) {
        var last = lastScroll;
        lastScroll = System.currentTimeMillis();
        if (lastScroll - last < DELAY && lastCause != SelectionChangeEvent.CAUSE_SEARCH) {
            postDisplay();
        }
    }

    protected void onHandleStateChange(@NonNull HandleStateChangeEvent event) {
        if (event.isHeld()) {
            postDisplay();
        }
        if (!event.getEditor().getCursor().isSelected()
                && event.getHandleType() == HandleStateChangeEvent.HANDLE_TYPE_INSERT
                && !event.isHeld()) {
            displayWindow();
            // Also, post to hide the window on handle disappearance
            editor.postDelayedInLifecycle(new Runnable() {
                @Override
                public void run() {
                    if (!editor.getEventHandler().shouldDrawInsertHandle()
                            && !editor.getCursor().isSelected()) {
                        dismiss();
                    } else if (!editor.getCursor().isSelected()) {
                        editor.postDelayedInLifecycle(this, CHECK_FOR_DISMISS_INTERVAL);
                    }
                }
            }, CHECK_FOR_DISMISS_INTERVAL);
        }
    }

    protected void onSelectionChange(@NonNull SelectionChangeEvent event) {
        if (handler.hasAnyHeldHandle() || event.getCause() == SelectionChangeEvent.CAUSE_DEAD_KEYS) {
            return;
        }
        if (handler.isDragSelecting()) {
            dismiss();
            return;
        }
        lastCause = event.getCause();
        if (event.isSelected() || event.getCause() == SelectionChangeEvent.CAUSE_LONG_PRESS && editor.getText().length() == 0) {
            // Always post show. See #193
            if (event.getCause() != SelectionChangeEvent.CAUSE_SEARCH) {
                editor.postInLifecycle(this::displayWindow);
            } else {
                dismiss();
            }
            lastPosition = -1;
        } else {
            var show = false;
            if (event.getCause() == SelectionChangeEvent.CAUSE_TAP && event.getLeft().index == lastPosition && !isShowing() && !editor.getText().isInBatchEdit() && editor.isEditable()) {
                editor.postInLifecycle(this::displayWindow);
                show = true;
            } else {
                dismiss();
            }
            if (event.getCause() == SelectionChangeEvent.CAUSE_TAP && !show) {
                lastPosition = event.getLeft().index;
            } else {
                lastPosition = -1;
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        eventManager.setEnabled(enabled);
        if (!enabled) {
            dismiss();
        }
    }

    /**
     * Get the view root of the panel.
     *
     * @see R.id#panel_root
     */
    public ViewGroup getView() {
        return (ViewGroup) getPopup().getContentView();
    }

    private void postDisplay() {
        if (!isShowing()) {
            return;
        }
        dismiss();
        if (!editor.getCursor().isSelected()) {
            return;
        }
        editor.postDelayedInLifecycle(new Runnable() {
            @Override
            public void run() {
                if (!handler.hasAnyHeldHandle() && !editor.getSnippetController().isInSnippet() && System.currentTimeMillis() - lastScroll > DELAY
                        && editor.getScroller().isFinished()) {
                    displayWindow();
                } else {
                    editor.postDelayedInLifecycle(this, DELAY);
                }
            }
        }, DELAY);
    }

    private int selectTop(@NonNull RectF rect) {
        var rowHeight = editor.getRowHeight();
        if (rect.top - rowHeight * 3 / 2F > getHeight()) {
            return (int) (rect.top - rowHeight * 3 / 2 - getHeight());
        } else {
            return (int) (rect.bottom + rowHeight / 2);
        }
    }

    public void displayWindow() {
        // 重置为主菜单
        expandedGroup = null;
        showMainMenu();

        updateMenuState();

        // 先测量内容大小
        measureAndUpdateSize();

        int top;
        var cursor = editor.getCursor();
        if (cursor.isSelected()) {
            var leftRect = editor.getLeftHandleDescriptor().position;
            var rightRect = editor.getRightHandleDescriptor().position;
            var top1 = selectTop(leftRect);
            var top2 = selectTop(rightRect);
            top = Math.min(top1, top2);
        } else {
            top = selectTop(editor.getInsertHandleDescriptor().position);
        }
        top = Math.max(0, Math.min(top, editor.getHeight() - getHeight() - 5));
        float handleLeftX = editor.getOffset(editor.getCursor().getLeftLine(), editor.getCursor().getLeftColumn());
        float handleRightX = editor.getOffset(editor.getCursor().getRightLine(), editor.getCursor().getRightColumn());
        int panelX = (int) ((handleLeftX + handleRightX) / 2f - rootView.getMeasuredWidth() / 2f);
        setLocationAbsolutely(panelX, top);
        show();
    }

    /**
     * 显示主菜单，隐藏子菜单
     */
    private void showMainMenu() {
        mainMenuPanel.setVisibility(View.VISIBLE);
        subMenuPanel.setVisibility(View.GONE);
    }

    /**
     * 展开分组菜单
     * @param group 要展开的分组
     */
    private void expandGroup(@NonNull TextActionMenuGroup group) {
        expandedGroup = group;

        // 切换到子菜单面板
        mainMenuPanel.setVisibility(View.GONE);
        subMenuPanel.setVisibility(View.VISIBLE);

        // 设置收起按钮文本
        collapseBtn.setText(group.getGroupLabel());

        // 清空并重新填充子菜单项
        subItemsContainer.removeAllViews();

        List<TextActionMenuGroup.MenuItem> items = group.getMenuItems(editor);
        int textColor = editor.getColorScheme().getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR);

        for (TextActionMenuGroup.MenuItem item : items) {
            if (!item.isVisible(editor)) {
                continue;
            }

            TextView itemBtn = createVerticalMenuItem(item.getLabel());
            itemBtn.setEnabled(item.isEnabled(editor));
            applyTextColor(itemBtn, textColor);

            // 禁用状态的透明度
            if (!item.isEnabled(editor)) {
                itemBtn.setAlpha(0.5f);
            }

            itemBtn.setOnClickListener(v -> {
                item.getAction().run();
                dismiss();
            });

            subItemsContainer.addView(itemBtn);
        }

        // 重新测量并更新窗口大小
        measureAndUpdateSize();
    }

    /**
     * 收起子菜单，返回主菜单
     */
    private void collapseSubMenu() {
        expandedGroup = null;
        showMainMenu();
        measureAndUpdateSize();
    }

    /**
     * 创建垂直菜单项按钮
     */
    private TextView createVerticalMenuItem(String text) {
        TextView button = new TextView(editor.getContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (int) (40 * editor.getDpUnit())
        );
        button.setLayoutParams(params);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(
            (int) (16 * editor.getDpUnit()), 0,
            (int) (16 * editor.getDpUnit()), 0
        );
        button.setText(text);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        button.setSingleLine(true);

        // 设置背景
        int[] attrs = new int[] { android.R.attr.selectableItemBackground };
        TypedArray ta = editor.getContext().obtainStyledAttributes(attrs);
        Drawable bg = ta.getDrawable(0);
        ta.recycle();
        button.setBackground(bg);

        return button;
    }

    /**
     * 创建分组标题按钮（带展开指示器）
     */
    @SuppressLint("UseCompatLoadingForDrawables")
    private TextView createGroupTitleButton(String text) {
        TextView button = createVerticalMenuItem(text);

        // 设置右侧展开指示器
        Drawable expandIcon;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            expandIcon = editor.getContext().getDrawable(R.drawable.ic_expand_more);
        } else {
            expandIcon = editor.getContext().getResources().getDrawable(R.drawable.ic_expand_more);
        }
        if (expandIcon != null) {
            int iconSize = (int) (16 * editor.getDpUnit());
            expandIcon.setBounds(0, 0, iconSize, iconSize);
            button.setCompoundDrawablesRelative(null, null, expandIcon, null);
            button.setCompoundDrawablePadding((int) (8 * editor.getDpUnit()));
        }

        return button;
    }

    /**
     * 测量并更新窗口大小
     */
    private void measureAndUpdateSize() {
        rootView.measure(
            View.MeasureSpec.makeMeasureSpec(1000000, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(100000, View.MeasureSpec.AT_MOST)
        );
        int width = Math.min(rootView.getMeasuredWidth(), (int) (editor.getDpUnit() * 200));
        int height = rootView.getMeasuredHeight();
        setSize(width, height);
    }

    /**
     * 更新菜单状态
     */
    private void updateMenuState() {
        // 更新分组标题按钮
        updateGroupTitleButtons();

        // 更新额外按钮的可见性
        updateExtraButtonVisibility();
    }

    /**
     * 更新分组标题按钮
     */
    private void updateGroupTitleButtons() {
        int textColor = editor.getColorScheme().getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR);

        for (MenuGroupEntry entry : menuGroups) {
            boolean shouldShow = entry.group.shouldShowGroup(editor);
            if (shouldShow) {
                // 检查分组是否有可见的菜单项
                List<TextActionMenuGroup.MenuItem> items = entry.group.getMenuItems(editor);
                boolean hasVisibleItems = false;
                for (TextActionMenuGroup.MenuItem item : items) {
                    if (item.isVisible(editor)) {
                        hasVisibleItems = true;
                        break;
                    }
                }
                shouldShow = hasVisibleItems;
            }

            entry.titleButton.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
            if (shouldShow) {
                applyTextColor(entry.titleButton, textColor);
            }
        }
    }

    /**
     * 更新所有额外按钮的可见性
     */
    private void updateExtraButtonVisibility() {
        for (ExtraButtonEntry entry : extraButtonEntries) {
            boolean shouldShow = entry.provider.shouldShowButton(editor);
            entry.button.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
        }
    }

    // ==================== 分组菜单管理 API ====================

    /**
     * 添加菜单分组
     * @param group 菜单分组
     */
    public void addMenuGroup(@NonNull TextActionMenuGroup group) {
        // 检查是否已存在
        for (MenuGroupEntry entry : menuGroups) {
            if (entry.group == group) {
                return;
            }
        }

        // 创建分组标题按钮
        TextView titleButton = createGroupTitleButton(group.getGroupLabel());
        titleButton.setVisibility(View.GONE);
        titleButton.setOnClickListener(v -> expandGroup(group));

        // 应用颜色
        int textColor = editor.getColorScheme().getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR);
        applyTextColor(titleButton, textColor);

        // 添加到容器
        groupTitlesContainer.addView(titleButton);

        // 保存条目
        MenuGroupEntry entry = new MenuGroupEntry(group, titleButton);
        menuGroups.add(entry);

        // 按优先级排序
        Collections.sort(menuGroups, Comparator.comparingInt(e -> e.group.getPriority()));

        // 重新排列按钮顺序
        reorderGroupButtons();
    }

    /**
     * 移除菜单分组
     * @param group 要移除的分组
     */
    public void removeMenuGroup(@NonNull TextActionMenuGroup group) {
        MenuGroupEntry toRemove = null;
        for (MenuGroupEntry entry : menuGroups) {
            if (entry.group == group) {
                toRemove = entry;
                break;
            }
        }

        if (toRemove != null) {
            groupTitlesContainer.removeView(toRemove.titleButton);
            menuGroups.remove(toRemove);

            // 如果当前展开的是被移除的分组，收起菜单
            if (expandedGroup == group) {
                collapseSubMenu();
            }
        }
    }

    /**
     * 清除所有菜单分组
     */
    public void clearMenuGroups() {
        for (MenuGroupEntry entry : menuGroups) {
            groupTitlesContainer.removeView(entry.titleButton);
        }
        menuGroups.clear();
        expandedGroup = null;
    }

    /**
     * 获取所有菜单分组
     */
    @NonNull
    public List<TextActionMenuGroup> getMenuGroups() {
        List<TextActionMenuGroup> groups = new ArrayList<>();
        for (MenuGroupEntry entry : menuGroups) {
            groups.add(entry.group);
        }
        return groups;
    }

    /**
     * 重新排列分组按钮顺序
     */
    private void reorderGroupButtons() {
        groupTitlesContainer.removeAllViews();
        for (MenuGroupEntry entry : menuGroups) {
            groupTitlesContainer.addView(entry.titleButton);
        }
    }

    /**
     * 菜单分组条目
     */
    private static class MenuGroupEntry {
        final TextActionMenuGroup group;
        final TextView titleButton;

        MenuGroupEntry(TextActionMenuGroup group, TextView titleButton) {
            this.group = group;
            this.titleButton = titleButton;
        }
    }

    // ==================== 单项扩展按钮 API（向后兼容） ====================

    /**
     * 添加额外按钮提供者
     * @param provider 按钮提供者
     */
    public void addExtraButtonProvider(@NonNull ExtraButtonProvider provider) {
        // 检查是否已经添加过相同的提供者
        for (ExtraButtonEntry entry : extraButtonEntries) {
            if (entry.provider == provider) {
                return;
            }
        }

        // 创建新的文本按钮
        TextView button = createVerticalMenuItem(provider.getButtonLabel());
        button.setVisibility(View.GONE);
        button.setOnClickListener(v -> {
            provider.onButtonClick(editor);
            dismiss();
        });

        // 应用颜色
        int color = editor.getColorScheme().getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR);
        applyTextColor(button, color);

        // 添加到分组标题容器中
        groupTitlesContainer.addView(button);

        // 保存到列表
        extraButtonEntries.add(new ExtraButtonEntry(provider, button));
    }

    /**
     * 移除额外按钮提供者
     * @param provider 要移除的按钮提供者
     */
    public void removeExtraButtonProvider(@NonNull ExtraButtonProvider provider) {
        ExtraButtonEntry toRemove = null;
        for (ExtraButtonEntry entry : extraButtonEntries) {
            if (entry.provider == provider) {
                toRemove = entry;
                break;
            }
        }

        if (toRemove != null) {
            groupTitlesContainer.removeView(toRemove.button);
            extraButtonEntries.remove(toRemove);
        }
    }

    /**
     * 清除所有额外按钮提供者
     */
    public void clearExtraButtonProviders() {
        for (ExtraButtonEntry entry : extraButtonEntries) {
            groupTitlesContainer.removeView(entry.button);
        }
        extraButtonEntries.clear();
    }

    /**
     * 获取所有额外按钮提供者
     */
    @NonNull
    public List<ExtraButtonProvider> getExtraButtonProviders() {
        List<ExtraButtonProvider> providers = new ArrayList<>();
        for (ExtraButtonEntry entry : extraButtonEntries) {
            providers.add(entry.provider);
        }
        return providers;
    }

    /**
     * 额外按钮条目
     */
    private static class ExtraButtonEntry {
        final ExtraButtonProvider provider;
        final TextView button;

        ExtraButtonEntry(ExtraButtonProvider provider, TextView button) {
            this.provider = provider;
            this.button = button;
        }
    }

    /**
     * 额外按钮提供者接口
     */
    public interface ExtraButtonProvider {
        /**
         * 获取按钮显示的文本标签
         * @return 按钮上显示的文字
         */
        @NonNull
        String getButtonLabel();

        /**
         * 判断是否应该显示按钮
         * @param editor 编辑器实例
         * @return 如果应该显示返回 true
         */
        boolean shouldShowButton(@NonNull CodeEditor editor);

        /**
         * 按钮点击回调
         * @param editor 编辑器实例
         */
        void onButtonClick(@NonNull CodeEditor editor);
    }

    @Override
    public void show() {
        if (!enabled || editor.getSnippetController().isInSnippet() || !editor.hasFocus() || editor.isInMouseMode()) {
            return;
        }
        // 检查是否有可显示的菜单项
        boolean hasVisibleGroups = false;
        for (MenuGroupEntry entry : menuGroups) {
            if (entry.titleButton.getVisibility() == View.VISIBLE) {
                hasVisibleGroups = true;
                break;
            }
        }
        boolean hasVisibleExtras = false;
        for (ExtraButtonEntry entry : extraButtonEntries) {
            if (entry.button.getVisibility() == View.VISIBLE) {
                hasVisibleExtras = true;
                break;
            }
        }
        if (!hasVisibleGroups && !hasVisibleExtras) {
            return;
        }
        super.show();
    }

}
