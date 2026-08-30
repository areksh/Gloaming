package sh.arek.gloaming

import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a failure is allowed to look like once it reaches a human.
 *
 * These strings are not developer-only. The recorded reason is shown on the
 * Tonight screen as "Last check failed: ..." and written to the journal people
 * export and send in, so a failure that describes itself as `timeout`, or as a
 * bare hostname, costs somebody an evening of guessing.
 */
class FailuresTest {

    @Test
    fun `says what the exception says`() {
        val failure = IOException("GET https://example.test/status.json returned 503")

        assertEquals("GET https://example.test/status.json returned 503", failure.describe())
    }

    // Better than the empty string, which is what a bare `message` would give.
    @Test
    fun `falls back to the type when there is no message at all`() {
        assertEquals("IOException", IOException().describe())
    }

    /**
     * The case this exists for. A failure to resolve a name arrives as an
     * exception whose entire message is the hostname, so the message alone
     * reads as though the app is reporting a website rather than a fault.
     */
    @Test
    fun `names the type of a cause, which is where its meaning is`() {
        val failure = IOException(
            "GET https://example.test/status.json could not be sent",
            UnknownHostException("example.test"),
        )

        assertEquals(
            "GET https://example.test/status.json could not be sent: " +
                "UnknownHostException: example.test",
            failure.describe(),
        )
    }

    @Test
    fun `follows a chain of causes to the bottom`() {
        val failure = IOException(
            "the aurora grid could not be read",
            IllegalArgumentException("Aurora grid was truncated", IOException("unexpected end")),
        )

        assertEquals(
            "the aurora grid could not be read: " +
                "IllegalArgumentException: Aurora grid was truncated: IOException: unexpected end",
            failure.describe(),
        )
    }

    @Test
    fun `does not repeat a reason the wrapper has already stated`() {
        val failure = IOException(
            "could not sign in: the key expired",
            IllegalStateException("the key expired"),
        )

        assertEquals("could not sign in: the key expired", failure.describe())
    }

    /**
     * An exception chained to itself would otherwise walk forever, on a
     * background wake holding a wake lock.
     */
    @Test
    fun `stops rather than looping when the causes form a cycle`() {
        val outer = IOException("outer")
        val inner = IOException("inner", outer)
        outer.initCause(inner)

        val described = outer.describe()

        assertTrue(described.startsWith("outer"))
        assertTrue(described.length < 200)
    }
}
