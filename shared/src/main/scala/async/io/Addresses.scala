package gears.async.io

/** Where an IP socket lives: a numeric IPv4 or IPv6 address and a port. An op taking one refuses, with an
  * `IllegalArgumentException` as it is built, a host that is not numeric or not of its case's family, an IPv6 scope
  * (`fe80::1%en0`), and a port outside 0 to 65535; it takes the address in canonical form (`IpText.canonical`), so that
  * `::1` and `0:0:0:0:0:0:0:1` are one address, as every address a backend reports is. An IPv6 socket on `::` is dual
  * stack on every backend, taking IPv4 peers too, which it reports, and sends to, by their IPv4 addresses.
  */
enum IpAddress {
  case IPv4(host: String, port: Int)
  case IPv6(host: String, port: Int)
}

/** Where a Unix domain socket lives: a path, or the empty path for an unnamed socket, as a sender is reported; an op
  * given the empty path, or one with a NUL, refuses it with an `IllegalArgumentException` as it is built.
  */
final case class UnixAddress(path: String)

enum AddressFamily {
  case IPv4, IPv6
}

final case class ResolvedAddress(family: AddressFamily, host: String)
