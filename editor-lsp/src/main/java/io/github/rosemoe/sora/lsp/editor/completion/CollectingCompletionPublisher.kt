package io.github.rosemoe.sora.lsp.editor.completion

import android.os.Handler
import android.os.Looper
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import java.util.Comparator

internal class CollectingCompletionPublisher(
    languageInterruptionLevel: Int,
) : CompletionPublisher(Handler(Looper.getMainLooper()), Runnable { }, languageInterruptionLevel) {

    private val _items = mutableListOf<CompletionItem>()
    private var cancelled = false

    fun snapshot(): List<CompletionItem> = _items.toList()

    override fun setComparator(comparator: Comparator<CompletionItem>?) {
        // ignored - we re-run filter/sort on merged results
    }

    override fun addItems(items: Collection<CompletionItem>) {
        checkCancelled()
        _items.addAll(items)
    }

    override fun addItem(item: CompletionItem) {
        checkCancelled()
        _items.add(item)
    }

    override fun updateList() {
        // no-op
    }

    override fun updateList(forced: Boolean) {
        // no-op
    }

    override fun cancel() {
        cancelled = true
    }

    override fun checkCancelled() {
        if (Thread.interrupted() || cancelled) {
            cancelled = true
            throw CompletionCancelledException()
        }
    }
}
