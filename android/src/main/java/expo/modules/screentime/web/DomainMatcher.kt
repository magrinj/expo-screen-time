package expo.modules.screentime.web

import android.icu.text.IDNA
import java.util.Locale

/** The only platform seam. Tests inject an ASCII/fixture mapper, never IDNA2003 as a substitute. */
internal fun interface DomainIdna { fun toAscii(name: String): String? }

internal object AndroidDomainIdna : DomainIdna {
  override fun toAscii(name: String): String? {
    val info = IDNA.Info()
    val result = StringBuilder()
    IDNA.getUTS46Instance(IDNA.NONTRANSITIONAL_TO_ASCII).nameToASCII(name, result, info)
    return if (info.hasErrors()) null else result.toString()
  }
}

/** Does not mutate stored domain strings. Invalid entries match nothing; callers may log [invalidNames] once. */
internal class DomainMatcher(names: Iterable<String>, private val idna: DomainIdna = AndroidDomainIdna) {
  enum class Match { ALLOW, NXDOMAIN, NODATA }
  private val blocked = mutableSetOf<String>()
  val invalidNames: Set<String>

  init {
    val invalid = mutableSetOf<String>()
    names.forEach { name -> normalize(name)?.let { blocked.add(it) } ?: invalid.add(name) }
    invalidNames = invalid.toSet()
  }

  fun match(name: String): Match {
    // Query names are DNS labels, not hostnames: IDNA/LDH rejection here would bypass suffix blocks.
    // DnsMessage escapes literal dots/backslashes inside labels as decimal escapes, so only real
    // label separators are walked. Unicode list entries have already become A-labels at construction.
    var candidate = name.map { if (it in 'A'..'Z') it + 32 else it }.joinToString("").removeSuffix(".")
    if (candidate == "_dns.resolver.arpa") return Match.NODATA
    if (candidate == "use-application-dns.net") return Match.NXDOMAIN
    while (true) {
      if (candidate in blocked) return Match.NXDOMAIN
      val dot = candidate.indexOf('.')
      if (dot < 0) return Match.ALLOW
      candidate = candidate.substring(dot + 1)
    }
  }

  private fun map(name: String): String? = idna.toAscii(name)?.lowercase(Locale.ROOT)?.removeSuffix(".")
  private fun normalize(name: String): String? = map(name)?.let { validHost(it) }

  private fun validHost(ascii: String): String? {
    if (ascii.isEmpty() || ascii.length > 253) return null
    val labels = ascii.split('.')
    if (labels.any { label -> label.isEmpty() || label.length > 63 || label.first() == '-' || label.last() == '-' ||
        label.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' } }) return null
    // Reject numeric forms accepted by common IP parsers, including shortened/octal/hex IPv4.
    if (labels.all { it.all { c -> c in '0'..'9' } ||
        (it.startsWith("0x") && it.length > 2 && it.drop(2).all { c -> c in '0'..'9' || c in 'a'..'f' }) }) return null
    return ascii
  }
}
