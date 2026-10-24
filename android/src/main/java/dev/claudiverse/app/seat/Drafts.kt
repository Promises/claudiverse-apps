package dev.claudiverse.app.seat

import android.content.Context

/**
 * What was typed to each seat but not sent, by seat id: kept in
 * SharedPreferences, so it survives leaving the seat for another and the
 * app being closed. An empty draft is removed.
 */
class Drafts(context: Context) {
    private val prefs = context.getSharedPreferences("claudiverse_drafts", Context.MODE_PRIVATE)

    fun get(seatId: Long): String = prefs.getString(key(seatId), null).orEmpty()

    fun set(seatId: Long, text: String) {
        val edit = prefs.edit()
        if (text.isEmpty()) edit.remove(key(seatId)) else edit.putString(key(seatId), text)
        edit.apply()
    }

    private fun key(seatId: Long) = "seat:$seatId"
}
