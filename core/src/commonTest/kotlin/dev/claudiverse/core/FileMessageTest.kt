package dev.claudiverse.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a seat is told about files the operator sent: it fetches each with its own token. */
class FileMessageTest {
    private val shot = UploadedFile("1", "pasted 10.21.png", 2048, "/api/files/1")
    private val log = UploadedFile("2", "build.log", 99, "/api/files/2")

    /** Unchanged from before several files could go at once: seats already read this shape. */
    @Test
    fun oneFileKeepsItsMessage() {
        assertEquals(
            "The operator sent you a file: pasted 10.21.png (2048 bytes). Fetch it with:\n" +
                "curl -fsS -H \"Authorization: Bearer \$CLAUDIVERSE_TOKEN\" \"\$CLAUDIVERSE_URL/api/files/1\" -o pasted_10.21.png\n\n" +
                "look at this",
            SeatSession.fileMessage(shot, " look at this "),
        )
    }

    @Test
    fun severalFilesAreNumberedInOrderWithTheNoteLast() {
        assertEquals(
            "The operator sent you 2 files:\n" +
                "1. pasted 10.21.png (2048 bytes). Fetch it with:\n" +
                "curl -fsS -H \"Authorization: Bearer \$CLAUDIVERSE_TOKEN\" \"\$CLAUDIVERSE_URL/api/files/1\" -o pasted_10.21.png\n\n" +
                "2. build.log (99 bytes). Fetch it with:\n" +
                "curl -fsS -H \"Authorization: Bearer \$CLAUDIVERSE_TOKEN\" \"\$CLAUDIVERSE_URL/api/files/2\" -o build.log\n\n" +
                "the crash is in both",
            SeatSession.filesMessage(listOf(shot, log), "the crash is in both"),
        )
    }
}
