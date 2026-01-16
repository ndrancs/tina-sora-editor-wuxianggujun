package io.github.rosemoe.sora.lsp.editor.completion

/**
 * LSP 补全行为配置。
 *
 * 说明：editor-lsp 作为通用模块，不直接依赖宿主 App 的偏好设置。
 * 宿主可在应用启动时或设置变更时更新这些字段。
 */
object LspCompletionConfig {

    const val FUNCTION_ARG_PLACEHOLDER_MODE_OFF = 0
    const val FUNCTION_ARG_PLACEHOLDER_MODE_SMART = 1
    const val FUNCTION_ARG_PLACEHOLDER_MODE_ALWAYS = 2

    /**
     * 函数参数占位符（参数名补全）模式。
     *
     * - [FUNCTION_ARG_PLACEHOLDER_MODE_OFF]：仅插入括号并将光标置于括号内
     * - [FUNCTION_ARG_PLACEHOLDER_MODE_SMART]：尽量少填参数名，但保留 Tab 跳转
     * - [FUNCTION_ARG_PLACEHOLDER_MODE_ALWAYS]：完全按 LSP snippet 插入参数占位符/参数名
     */
    @JvmStatic
    @Volatile
    var functionArgPlaceholderMode: Int = FUNCTION_ARG_PLACEHOLDER_MODE_ALWAYS
}
