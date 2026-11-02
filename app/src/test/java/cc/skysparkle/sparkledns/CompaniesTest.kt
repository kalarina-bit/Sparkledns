package cc.skysparkle.sparkledns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompaniesTest {

    @Test
    fun matchesDomainsAndTheirSubdomains() {
        assertEquals(Company.GOOGLE, Companies.of("youtube.com"))
        assertEquals(Company.GOOGLE, Companies.of("r3---sn-abc.googlevideo.com"))
        assertEquals(Company.META, Companies.of("scontent.cdninstagram.com"))
        assertEquals(Company.X, Companies.of("t.co"))
        assertEquals(Company.MICROSOFT, Companies.of("api.github.com"))
    }

    @Test
    fun ignoresCaseAndTrailingDots() {
        assertEquals(Company.APPLE, Companies.of("WWW.Apple.COM"))
        assertEquals(Company.AMAZON, Companies.of("s3.amazonaws.com."))
        assertEquals(Company.AMAZON, Companies.of("s3.amazonaws.com.."))
    }

    @Test
    fun knowsGoogleCountrySites() {
        assertEquals(Company.GOOGLE, Companies.of("google.de"))
        assertEquals(Company.GOOGLE, Companies.of("www.google.co.uk"))
        assertEquals(Company.GOOGLE, Companies.of("google.com.br"))
        assertNull(Companies.of("google.example.org"))
        assertNull(Companies.of("google."))
    }

    @Test
    fun leavesOtherDomainsAlone() {
        assertNull(Companies.of("example.com"))
        assertNull(Companies.of("notyoutube.com"))
        assertNull(Companies.of("youtube.com.example"))
        assertNull(Companies.of("com"))
        assertNull(Companies.of(""))
        assertNull(Companies.of("."))
    }
}
