package dev.yyblite.ui

import java.util.concurrent.atomic.AtomicInteger

/** 追蹤是否有 Activity 在前景：前景時可直接啟動安裝器，背景需懸浮視窗授權。 */
object AppVisibility {
    private val active = AtomicInteger(0)

    fun onStart() {
        active.incrementAndGet()
    }

    fun onStop() {
        active.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun isVisible(): Boolean = active.get() > 0
}
