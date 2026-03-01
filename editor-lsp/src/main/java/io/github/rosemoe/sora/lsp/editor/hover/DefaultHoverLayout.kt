package io.github.rosemoe.sora.lsp.editor.hover

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import io.github.rosemoe.sora.lsp.R
import io.github.rosemoe.sora.lsp.editor.curvedTextScale
import io.github.rosemoe.sora.lsp.editor.text.SimpleMarkdownRenderer
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Hover

class DefaultHoverLayout : HoverLayout {
    private lateinit var window: HoverWindow
    private lateinit var root: View
    private lateinit var container: ScrollView
    private lateinit var hoverTextView: TextView
    private var textColor: Int = 0
    private var highlightColor: Int = 0
    private var codeTypeface: Typeface = Typeface.MONOSPACE
    private var baselineEditorTextSize: Float? = null
    private var baselineHoverTextSize: Float? = null
    private var latestEditorTextSize: Float? = null
    private var asyncRenderJob: Job? = null
    private var renderGeneration: Long = 0L
    private var plainFallbackRunnable: Runnable? = null
    private val renderCache = LinkedHashMap<Int, RenderCacheEntry>(16, 0.75f, true)

    override fun attach(window: HoverWindow) {
        this.window = window
    }

    override fun createView(inflater: LayoutInflater): View {
        root = inflater.inflate(R.layout.hover_tooltip_window, null, false)
        container = root.findViewById(R.id.hover_scroll_container)
        hoverTextView = root.findViewById(R.id.hover_text)
        hoverTextView.movementMethod = LinkMovementMethod()
        baselineHoverTextSize = hoverTextView.textSize
        latestEditorTextSize?.let { applyEditorScale(it) }
        return root
    }

    override fun applyColorScheme(colorScheme: EditorColorScheme, typeface: Typeface) {
        val editor = window.editor
        textColor = colorScheme.getColor(EditorColorScheme.HOVER_TEXT_NORMAL)
        highlightColor = colorScheme.getColor(EditorColorScheme.HOVER_TEXT_HIGHLIGHTED)
        codeTypeface = typeface
        synchronized(renderCache) { renderCache.clear() }
        hoverTextView.setTextColor(textColor)

        val drawable = GradientDrawable().apply {
            cornerRadius = editor.dpUnit * 8
            setColor(colorScheme.getColor(EditorColorScheme.HOVER_BACKGROUND))
            val strokeWidth = editor.dpUnit.toInt().coerceAtLeast(1)
            setStroke(strokeWidth, colorScheme.getColor(EditorColorScheme.HOVER_BORDER))
        }
        root.background = drawable
    }

    override fun renderHover(hover: Hover) {
        val hoverText = buildHoverText(hover)
        trimCacheIfNeeded()
        val cacheKey = buildRenderKey(hoverText)
        getCachedRender(cacheKey, hoverText)?.let { cached ->
            ++renderGeneration
            cancelPendingFallback()
            asyncRenderJob?.cancel()
            hoverTextView.text = cached
            container.post { container.smoothScrollTo(0, 0) }
            return
        }

        val generation = ++renderGeneration
        schedulePlainFallback(generation, hoverText)
        asyncRenderJob?.cancel()
        asyncRenderJob = window.launchRender {
            val rendered = runCatching {
                withContext(Dispatchers.Default) {
                    SimpleMarkdownRenderer.renderAsync(
                        markdown = hoverText,
                        boldColor = highlightColor,
                        inlineCodeColor = highlightColor,
                        codeTypeface = codeTypeface,
                        linkColor = highlightColor
                    )
                }
            }.getOrElse {
                SpannableStringBuilder(hoverText)
            }
            if (generation != renderGeneration) {
                return@launchRender
            }
            cancelPendingFallback()
            putCachedRender(cacheKey, hoverText, rendered)
            hoverTextView.text = rendered
            container.post { container.smoothScrollTo(0, 0) }
        }.also { job ->
            job.invokeOnCompletion {
                if (asyncRenderJob === job) {
                    asyncRenderJob = null
                }
            }
        }
    }

    override fun onTextSizeChanged(oldSize: Float, newSize: Float) {
        if (!::hoverTextView.isInitialized) {
            return
        }
        if (newSize <= 0f) {
            return
        }
        if (baselineEditorTextSize == null) {
            if (oldSize <= 0f) {
                return
            }
            baselineEditorTextSize = oldSize
            baselineHoverTextSize = baselineHoverTextSize ?: hoverTextView.textSize
        }
        latestEditorTextSize = newSize
        applyEditorScale(newSize)
    }

    private fun applyEditorScale(targetEditorSize: Float) {
        val editorBaseline = baselineEditorTextSize ?: return
        val textBaseline = baselineHoverTextSize ?: hoverTextView.textSize
        val scale = targetEditorSize / editorBaseline
        if (scale <= 0f) {
            return
        }
        val curvedScale = curvedTextScale(scale)
        hoverTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textBaseline * curvedScale)
    }

    private fun schedulePlainFallback(generation: Long, hoverText: String) {
        cancelPendingFallback()
        val fallback = Runnable {
            if (generation != renderGeneration) {
                return@Runnable
            }
            hoverTextView.text = hoverText
            container.post { container.smoothScrollTo(0, 0) }
        }
        plainFallbackRunnable = fallback
        hoverTextView.postDelayed(fallback, window.hoverMarkdownFallbackDelayMillis)
    }

    private fun cancelPendingFallback() {
        val runnable = plainFallbackRunnable ?: return
        hoverTextView.removeCallbacks(runnable)
        plainFallbackRunnable = null
    }

    private fun getCachedRender(key: Int, hoverText: String): CharSequence? {
        val capacity = currentCacheCapacity()
        if (capacity <= 0) {
            return null
        }
        synchronized(renderCache) {
            val entry = renderCache[key] ?: return null
            if (entry.markdown != hoverText) {
                return null
            }
            return entry.rendered
        }
    }

    private fun putCachedRender(key: Int, hoverText: String, rendered: CharSequence) {
        val capacity = currentCacheCapacity()
        if (capacity <= 0) {
            synchronized(renderCache) {
                renderCache.clear()
            }
            return
        }
        synchronized(renderCache) {
            renderCache[key] = RenderCacheEntry(hoverText, rendered)
            trimCacheLocked(capacity)
        }
    }

    private fun buildRenderKey(hoverText: String): Int {
        var result = hoverText.hashCode()
        result = 31 * result + highlightColor
        result = 31 * result + codeTypeface.style
        return result
    }

    private fun trimCacheIfNeeded() {
        synchronized(renderCache) {
            trimCacheLocked(currentCacheCapacity())
        }
    }

    private fun trimCacheLocked(capacity: Int) {
        if (capacity <= 0) {
            renderCache.clear()
            return
        }
        while (renderCache.size > capacity) {
            val iterator = renderCache.entries.iterator()
            if (!iterator.hasNext()) {
                return
            }
            iterator.next()
            iterator.remove()
        }
    }

    private fun currentCacheCapacity(): Int {
        return window.hoverMarkdownRenderCacheCapacity.coerceAtLeast(0)
    }


    private fun buildHoverText(hover: Hover): String {
        val hoverContents = hover.contents ?: return ""
        return if (hoverContents.isLeft) {
            val items = hoverContents.left.orEmpty()
            items.joinToString("\n\n") { either -> formatMarkedStringEither(either) ?: "" }
        } else {
            val markup = hoverContents.right
            formatMarkupContent(markup) ?: ""
        }
    }

    private data class RenderCacheEntry(
        val markdown: String,
        val rendered: CharSequence
    )

}
