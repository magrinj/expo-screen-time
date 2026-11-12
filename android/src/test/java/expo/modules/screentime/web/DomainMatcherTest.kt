package expo.modules.screentime.web

import org.junit.Assert.*
import org.junit.Test

internal class DomainMatcherTest {
  // Plain JVM tests do not pretend java.net.IDN is UTS-46. The seam has explicit UTS-46 fixtures;
  // AndroidDomainIdna's actual ICU mapping is left to a check on a device.
  private val idna = DomainIdna { name ->
    when (name) {
      "faß.de" -> "xn--fa-hia.de"
      "www.faß.de" -> "www.xn--fa-hia.de"
      "bücher.de" -> "xn--bcher-kva.de"
      "ＥＸＡＭＰＬＥ。ＣＯＭ。" -> "example.com."
      "_dns。resolver。arpa。" -> "_dns.resolver.arpa."
      "bad\u200d.com" -> null
      else -> name.takeIf { value -> value.all { it.code < 128 } }
    }
  }
  private fun matcher(vararg names: String) = DomainMatcher(names.toList(), idna)

  @Test fun blockedNameIncludesItselfAndSubdomainsOnly() {
    val matcher = matcher("facebook.com")
    for (name in listOf("facebook.com", "www.facebook.com", "m.facebook.com", "graph.facebook.com", "a.b.facebook.com")) {
      assertEquals(DomainMatcher.Match.NXDOMAIN, matcher.match(name))
    }
    for (name in listOf("notfacebook.com", "facebook.net", "facebook.com.evil", "com")) {
      assertEquals(DomainMatcher.Match.ALLOW, matcher.match(name))
    }
  }
  @Test fun blockingWwwDoesNotBlockParent() {
    val matcher = matcher("www.facebook.com")
    assertEquals(DomainMatcher.Match.ALLOW, matcher.match("facebook.com"))
    assertEquals(DomainMatcher.Match.NXDOMAIN, matcher.match("a.www.facebook.com"))
  }
  @Test fun caseAndOneTrailingDotNormalizeOnBothSides() {
    assertEquals(DomainMatcher.Match.NXDOMAIN, matcher("FACEbook.COM.").match("www.Facebook.cOm."))
    assertEquals(DomainMatcher.Match.ALLOW, matcher("facebook.com..").match("facebook.com"))
  }
  @Test fun uts46FixtureKeepsSharpSDistinctFromSs() {
    val matcher = matcher("faß.de")
    assertEquals(DomainMatcher.Match.NXDOMAIN, matcher.match("xn--fa-hia.de"))
    assertEquals(DomainMatcher.Match.NXDOMAIN, matcher.match("www.xn--fa-hia.de"))
    assertEquals(DomainMatcher.Match.ALLOW, matcher.match("fass.de"))
  }
  @Test fun unicodeAndAlabelShareIdentity() {
    assertEquals(DomainMatcher.Match.NXDOMAIN, matcher("xn--bcher-kva.de").match("xn--bcher-kva.de"))
    assertEquals(DomainMatcher.Match.NXDOMAIN, matcher("bücher.de").match("xn--bcher-kva.de"))
  }
  @Test fun uts46WidthAndDotMappingHappensBeforeHostValidation() {
    assertEquals(DomainMatcher.Match.NXDOMAIN, matcher("ＥＸＡＭＰＬＥ。ＣＯＭ。").match("example.com"))
  }
  @Test fun invalidListEntriesNeverBecomeBlockedSuffixes() {
    val invalid = listOf("*.x", "127.0.0.1", "127.1", "0x7f000001", "2130706433", "0177.0.0.1",
      "::1", "[2001:db8::1]", "https://example.com", "example.com/path", "", ".", "a..com", "-a.com",
      "a-.com", "a_b.com", " example.com", "a".repeat(64) + ".com", "bad\u200d.com")
    val matcher = DomainMatcher(invalid, idna)
    assertEquals(invalid.toSet(), matcher.invalidNames)
    invalid.forEach { assertEquals(it, DomainMatcher.Match.ALLOW, matcher.match(it)) }
  }
  @Test fun nonHostnameQueryLabelsStillMatchTheirBlockedSuffix() {
    val matcher = matcher("facebook.com", "example.com", "x")
    for (prefix in listOf("_443._tcp", "_8443._https", "*", "a_b", "ab--c", "\\000", "a\\046b")) {
      assertEquals(prefix, DomainMatcher.Match.NXDOMAIN, matcher.match("$prefix.FACEBOOK.COM."))
    }
    assertEquals(DomainMatcher.Match.NXDOMAIN, matcher.match("*.x"))
    assertEquals(DomainMatcher.Match.ALLOW, matcher.match("badexample.com"))
    assertEquals(DomainMatcher.Match.ALLOW, matcher.match("x\\046example.com"))
  }
  @Test fun idnaIsCalledOnlyForListEntries() {
    var calls = 0
    val matcher = DomainMatcher(listOf("facebook.com"), DomainIdna { calls++; it })
    matcher.match("_443._tcp.FACEBOOK.COM.")
    matcher.match("ß.facebook.com")
    assertEquals(1, calls)
    assertEquals(DomainMatcher.Match.ALLOW, matcher.match("FACEBOOK。COM"))
  }

  @Test fun invalidNamesAreDeduplicatedForTheCallersOnceOnlyLog() {
    assertEquals(setOf("*.x"), matcher("*.x", "*.x").invalidNames)
  }
  @Test fun firefoxCanaryIsExactAndIndependentOfList() {
    val matcher = matcher()
    assertEquals(DomainMatcher.Match.NXDOMAIN, matcher.match("USE-APPLICATION-DNS.NET."))
    assertEquals(DomainMatcher.Match.ALLOW, matcher.match("x.use-application-dns.net"))
  }
  @Test fun ddrIsLocalNodataEvenWhenItsParentIsBlocked() {
    val matcher = matcher("arpa", "resolver.arpa", "_dns.resolver.arpa")
    assertEquals(DomainMatcher.Match.NODATA, matcher.match("_DNS.RESOLVER.ARPA."))
    assertEquals(DomainMatcher.Match.ALLOW, matcher.match("_dns。resolver。arpa。"))
    assertEquals(DomainMatcher.Match.NXDOMAIN, matcher.match("x._dns.resolver.arpa"))
    assertEquals(DomainMatcher.Match.NXDOMAIN, matcher.match("ordinary.resolver.arpa"))
  }
  @Test fun signalDecisionsEncodeTheirSpecifiedDnsResponses() {
    for ((name, expected) in listOf("use-application-dns.net" to 3, "_dns.resolver.arpa" to 0)) {
      val decision = matcher().match(name)
      val answer = if (decision == DomainMatcher.Match.NXDOMAIN) DnsMessage.Answer.NXDOMAIN else DnsMessage.Answer.NODATA
      val wire = DnsMessage.response(query(queryWire(name = name, type = 64)), answer, IpPacket.Family.IPV4)
      assertEquals(expected, IpPacket.u16(wire, 2) and 15)
      assertEquals(0, IpPacket.u16(wire, 6))
    }
  }
}
