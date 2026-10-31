package cc.skysparkle.sparkledns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlocklistParserTest {

    @Test
    fun understandsCommonListFormats() {
        assertEquals("ads.example.com", BlocklistParser.parseLine("0.0.0.0 ads.example.com"))
        assertEquals("ads.example.com", BlocklistParser.parseLine("127.0.0.1\tads.example.com # tracker"))
        assertEquals("ads.example.com", BlocklistParser.parseLine("ads.example.com"))
        assertEquals("tracker.example", BlocklistParser.parseLine("||tracker.example^"))
        assertEquals("tracker.example", BlocklistParser.parseLine("||tracker.example^\$third-party"))
        assertEquals("bad.example", BlocklistParser.parseLine("address=/bad.example/0.0.0.0"))
        assertEquals("bad.example", BlocklistParser.parseLine("server=/bad.example/"))
        assertEquals("bad.example", BlocklistParser.parseLine("local-zone: \"bad.example\" always_nxdomain"))
    }

    @Test
    fun skipsCommentsRulesAndLocalNames() {
        assertNull(BlocklistParser.parseLine(""))
        assertNull(BlocklistParser.parseLine("# comment"))
        assertNull(BlocklistParser.parseLine("! adblock comment"))
        assertNull(BlocklistParser.parseLine("[Adblock Plus 2.0]"))
        assertNull(BlocklistParser.parseLine("@@||allowed.example^"))
        assertNull(BlocklistParser.parseLine("example.com##.banner"))
        assertNull(BlocklistParser.parseLine("127.0.0.1 localhost"))
        assertNull(BlocklistParser.parseLine("0.0.0.0 0.0.0.0"))
        assertNull(BlocklistParser.parseLine("::1 ip6-localhost"))
    }

    @Test
    fun normalizesUserInput() {
        assertEquals("example.com", BlocklistParser.normalize("  HTTPS://Example.COM/path?q=1 "))
        assertEquals("example.com", BlocklistParser.normalize("*.example.com"))
        assertEquals("example.com", BlocklistParser.normalize("example.com."))
        assertEquals("example.com", BlocklistParser.normalize("example.com:8080"))
        assertNull(BlocklistParser.normalize("localhost"))
        assertNull(BlocklistParser.normalize("bad..example"))
        assertNull(BlocklistParser.normalize("-bad.example"))
        assertNull(BlocklistParser.normalize("exa mple.com"))
        assertNull(BlocklistParser.normalize("192.168.0.1"))
    }

    @Test
    fun parentHashMatchesHashOfTheSuffix() {
        val name = "a.b.example.com"
        assertEquals(DomainHash.of("example.com"), DomainHash.of(name, name.indexOf("example")))
        assertEquals(DomainHash.of(name), DomainHash.of(name, 0))
        assertEquals(DomainHash.of("b.example"), DomainHash.of(name, 2, name.indexOf(".com")))
    }
}
