package cc.skysparkle.sparkledns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerListTest {

    @Test
    fun readsAllColumns() {
        val list = ServerList.parse(
            """
            # Name | URL | policy | h3 | donate
            Example DNS | https://dns.example.org/dns-query | https://example.org/privacy | h3 | https://example.org/donate
            Other | https://doh.other.net/q | https://other.net/policy | https://other.net/give
            """.trimIndent()
        )

        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("dns.example.org", first.id)
        assertEquals("Example DNS", first.name)
        assertEquals("https://dns.example.org/dns-query", first.url)
        assertEquals("https://example.org/privacy", first.policy)
        assertEquals("https://example.org/donate", first.donate)
        assertTrue(first.http3)

        val second = list[1]
        assertFalse(second.http3)
        assertEquals("https://other.net/give", second.donate)
    }

    @Test
    fun skipsServersWithoutHttpsOrPolicy() {
        val list = ServerList.parse(
            """
            Plain | http://dns.example.org/dns-query | https://example.org/privacy
            No policy | https://dns.example.org/dns-query | not a url
            Too short | https://dns.example.org/dns-query
            """.trimIndent()
        )

        assertTrue(list.isEmpty())
    }

    @Test
    fun shippedServersCannotBeRedirected() {
        val quad9 = Providers.builtIn.first { it.id == "quad9" }

        val list = ServerList.parse("Fake | https://dns.quad9.net/elsewhere | https://evil.example/policy")

        assertSame(quad9, list.single())
    }

    @Test
    fun dropsDuplicatesTrimsNamesAndCapsTheList() {
        val long = "N".repeat(60)
        val lines = (1..60).joinToString("\n") { "$long | https://dns$it.example/q | https://example.org/p" } +
            "\nDuplicate | https://dns1.example/q | https://example.org/p"

        val list = ServerList.parse(lines)

        assertEquals(50, list.size)
        assertEquals(40, list[0].name.length)
        assertEquals(list.size, list.map { it.id }.toSet().size)
        assertNull(list.firstOrNull { it.name == "Duplicate" })
    }
}
