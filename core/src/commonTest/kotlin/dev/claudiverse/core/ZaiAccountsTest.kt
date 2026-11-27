package dev.claudiverse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The operator's "one block per account": a Mistral account's API key and Vibe session shown together, its allowances once. */
class ZaiAccountsTest {

    private fun w(window: String, percent: Int) = ZaiWindow(window, null, null, null, percent, "2026-12-01T00:00:00Z")

    private fun key(
        id: Long, provider: String?, account: String?, priority: Int? = null,
        checkedAt: String? = "2026-11-27T08:00:00Z", windows: List<ZaiWindow> = emptyList(),
    ) = ZaiKey(id, "k$id", null, "ok", null, checkedAt, windows, provider, account, priority)

    @Test
    fun oneAccountsCredentialsAreOneBlockWhereTheFirstWas() {
        val zai = key(1, "zai", null)
        val api = key(5, "mistral", "a@example.com", priority = 25, windows = listOf(w("month", 1), w("vibe", 3)))
        val og = key(3, "opencode-go", null)
        val session = key(8, "vibe", "a@example.com", priority = 20, checkedAt = "2026-11-27T08:05:00Z", windows = listOf(w("month", 2), w("vibe", 4)))
        val other = key(9, "vibe", "b@example.com", priority = 30)
        val entries = zaiEntries(listOf(zai, api, og, session, other))
        assertEquals(listOf("single 1", "account a@example.com", "single 3", "account b@example.com"), entries.map {
            when (it) { is ZaiEntry.Single -> "single ${it.key.id}"; is ZaiEntry.Account -> "account ${it.account}" }
        })
        val a = entries[1] as ZaiEntry.Account
        assertEquals(listOf(8L, 5L), a.keys.map { it.id }, "by priority")
        assertEquals(listOf(2, 4), a.windows.map { it.percent }, "the windows of the credential read last")
        assertEquals(listOf("Vibe session", "API key"), a.keys.map { it.kind })
        assertEquals(listOf("API ($30)", "Vibe ($300)"), a.windows.map(::zaiAccountWindowLabel))
        assertEquals("Mistral (Vibe Pro)", session.providerName)
    }

    @Test
    fun aCredentialNotReadYetDoesNotBlankTheAccount() {
        val read = key(5, "mistral", "a@example.com", checkedAt = "2026-11-27T08:00:00Z", windows = listOf(w("month", 1), w("vibe", 3)))
        val fresh = key(10, "mistral", "a@example.com", checkedAt = "2026-11-27T09:00:00Z")
        val a = zaiEntries(listOf(read, fresh)).single() as ZaiEntry.Account
        assertEquals(listOf(1, 3), a.windows.map { it.percent })
    }

    @Test
    fun onlyMistralCredentialsNamingAnAccountAreGrouped() {
        val keys = listOf(
            key(1, "mistral", null), key(2, "vibe", null), key(3, "vibe", "  "),
            key(4, "zai", "a@example.com"), key(6, "opencode-go", "a@example.com"),
        )
        val entries = zaiEntries(keys)
        assertTrue(entries.all { it is ZaiEntry.Single }, entries.toString())
        assertEquals(keys.map { it.id }, entries.map { (it as ZaiEntry.Single).key.id })
    }
}
