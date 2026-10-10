package dev.claudiverse.app.update

import dev.claudiverse.core.AppBuild
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    private fun build(code: Long) = AppBuild("1", code, "1.0.$code", "abcd1234", "00", 1, "/api/app/builds/1/apk", null, null)

    @Test
    fun onlyAHigherVersionIsAnUpdate() {
        assertTrue(isUpdate(2056, build(2057)))
        assertFalse(isUpdate(2056, build(2056)))
        assertFalse(isUpdate(2056, build(2055)))
    }
}
