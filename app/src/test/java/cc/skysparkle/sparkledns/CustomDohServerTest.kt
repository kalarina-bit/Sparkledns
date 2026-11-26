package cc.skysparkle.sparkledns

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class CustomDohServerTest {
    @Test
    fun `creates CustomDohServer with all fields`() {
        val server = CustomDohServer(
            id = "test-id",
            name = "Test Server",
            url = "https://dns.example.com/dns-query",
            bootstrap = listOf("1.1.1.1", "8.8.8.8"),
            http3 = true
        )

        assertEquals("test-id", server.id)
        assertEquals("Test Server", server.name)
        assertEquals("https://dns.example.com/dns-query", server.url)
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), server.bootstrap)
        assertTrue(server.http3)
    }

    @Test
    fun `converts CustomDohServer to DohProvider`() {
        val server = CustomDohServer(
            id = "abc-123",
            name = "My DNS",
            url = "https://my.dns/query",
            bootstrap = listOf("10.0.0.1"),
            http3 = false
        )

        val provider = server.toDohProvider()

        assertEquals("custom:abc-123", provider.id)
        assertEquals("My DNS", provider.name)
        assertEquals("https://my.dns/query", provider.url)
        assertEquals(listOf("10.0.0.1"), provider.bootstrap)
        assertFalse(provider.http3)
    }

    @Test
    fun `serializes CustomDohServer to JSON`() {
        val server = CustomDohServer(
            id = "uuid-456",
            name = "Office DNS",
            url = "https://dns.office.local/dns-query",
            bootstrap = listOf("192.168.1.1", "192.168.1.2"),
            http3 = true
        )

        val json = server.toJson()

        assertEquals("uuid-456", json.getString("id"))
        assertEquals("Office DNS", json.getString("name"))
        assertEquals("https://dns.office.local/dns-query", json.getString("url"))
        assertEquals(true, json.getBoolean("http3"))

        val bootstrapArray = json.getJSONArray("bootstrap")
        assertEquals(2, bootstrapArray.length())
        assertEquals("192.168.1.1", bootstrapArray.getString(0))
        assertEquals("192.168.1.2", bootstrapArray.getString(1))
    }

    @Test
    fun `deserializes CustomDohServer from JSON`() {
        val json = JSONObject().apply {
            put("id", "test-789")
            put("name", "Test DNS")
            put("url", "https://test.dns/query")
            put("http3", false)
            put("bootstrap", JSONArray(listOf("9.9.9.9")))
        }

        val server = CustomDohServer.fromJson(json)

        assertEquals("test-789", server.id)
        assertEquals("Test DNS", server.name)
        assertEquals("https://test.dns/query", server.url)
        assertEquals(listOf("9.9.9.9"), server.bootstrap)
        assertFalse(server.http3)
    }

    @Test
    fun `handles empty bootstrap list`() {
        val server = CustomDohServer(
            id = "empty-bootstrap",
            name = "No Bootstrap",
            url = "https://dns.example.com/dns-query",
            bootstrap = emptyList(),
            http3 = false
        )

        val json = server.toJson()
        val deserialized = CustomDohServer.fromJson(json)

        assertEquals(emptyList<String>(), deserialized.bootstrap)
    }

    @Test
    fun `generates unique UUID for new server`() {
        val server1 = CustomDohServer.create(
            name = "Server 1",
            url = "https://dns1.example.com/dns-query",
            bootstrap = emptyList(),
            http3 = false
        )

        val server2 = CustomDohServer.create(
            name = "Server 2",
            url = "https://dns2.example.com/dns-query",
            bootstrap = emptyList(),
            http3 = false
        )

        assertNotEquals(server1.id, server2.id)
        // Verify UUID format
        UUID.fromString(server1.id) // throws if invalid
        UUID.fromString(server2.id)
    }
}
