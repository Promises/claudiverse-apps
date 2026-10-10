package dev.claudiverse.app.seat

import android.content.Context
import dev.claudiverse.core.OutboxStore

/** The outbox's queue in SharedPreferences, under the same file and key it has always used. */
class PrefsOutboxStore(context: Context) : OutboxStore {
    private val prefs = context.getSharedPreferences("claudiverse_outbox", Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY, null)

    override fun save(json: String) {
        prefs.edit().putString(KEY, json).apply()
    }

    private companion object {
        const val KEY = "queue"
    }
}
