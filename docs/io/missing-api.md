# What the interface lacks for TLS, QUIC, HTTP and WebSockets

A proposal, recorded 2026-10-27: steps 1 to 4 of the order below done by 2026-10-28, step 5 left for follow-ups. Nothing in the backend interface stops these protocols being
built today: each runs over reliable byte streams or datagrams, which the interface has, and TLS can run in user space
over plain `read` and `write`, as the JDK's `SSLEngine` and rustls do. What follows is what a correct or a fast
implementation would miss, what each backend could offer for it, and what deliberately stays out of the interface.

Where a backend lacks something below, it should fail clearly, not ignore it, as every op misused does. Claims about
Network.framework's options are from its headers, not yet probed.

## Gaps that block a correct implementation

**A socket's local and peer addresses.** Done (2026-10-27): `localAddress` and `peerAddress`, on stream sockets of
either family and on datagram sockets of each, plain methods rather than ops, since every backend answers at once once
the socket is open (`getsockname` and `getpeername` on kqueue, the channels' addresses on NIO, the path's endpoints and
the listener's port on Network.framework). A listener or bound socket on port 0 tells its port, and `peerAddress` of an
accepted connection tells who connected, so `accept` writes nothing more. Contract checks on every backend. The
contract suites still find free ports for the checks that need a fixed one.

**Socket options.** Begun (2026-10-27): `SocketOption` keys and `SocketOptions` given as an IP socket opens
(`connect`, `listen`, `bindDatagram`, `connectDatagram`), `supports` and `canChange` asked of a backend's ops when the
program runs, since support depends on the host as much as the backend (a JDK's version, macOS's), `option` to read one
back and `setOption` to change one where the backend can; an option a backend lacks, or a stream socket's on a datagram
socket, fails as the op is built. Done: `NoDelay`, on unless set off on every backend, as Go has it; `KeepAlive`, off or
on with its timings; `ReceiveBuffer` and `SendBuffer`, requests the system may round. A listener's options are each
connection's it accepts. kqueue and NIO have all four and change them at any time; the Network.framework backend has
`NoDelay` and `KeepAlive`, on its own sockets as they open only, read back as they opened, since it neither changes
nor reports them after, and no buffer sizes. Then `DontFragment`, for QUIC (RFC 9000 §14), on datagram sockets, and
`ReusePort`, on listeners and bound datagram sockets, given as they open only: kqueue and NIO have both, the
Network.framework backend don't-fragment on its sockets as they open, and no reuse-port, since Network.framework shares
no port. Probed on macOS 27.0, loopback's MTU 16384: a 20000-byte datagram goes fragmented, and with don't-fragment
fails, `EMSGSIZE`, on IPv4 and IPv6, the IPv6 option covering a dual-stack socket's IPv4 sends; `SO_REUSEPORT` lets
sockets that set it share a port, refusing one that does not, and macOS gives every connection and datagram to the
socket bound last, where Linux spreads them, which the option's documentation says rather than hides. An option says
which kinds of socket it applies to (`SocketOption.Kind`), and one place says what each opener and each accepted
connection sets (`SocketOption.opening`, `accepted`), so the backends cannot differ there. Contract checks follow each
backend's `supports` and `canChange`, and check the effect of each where the backend can show it. Still to come:
options on Unix sockets.

**A QUIC server on a wildcard address.** On a host with several addresses, a server bound to `0.0.0.0` must reply from
the address the client sent to, or the client drops the reply. That needs the destination address with each received
datagram and a chosen source address on each send (`IP_PKTINFO`, `IPV6_RECVPKTINFO`). NIO cannot: the JDK has no
ancillary data, and the portable workaround is a socket bound to each local address. Proposed: an optional packet-info
capability on kqueue and Network.framework, absent on NIO.

**Timer resolution and cost.** Done (2026-10-27). `timer(milliseconds: Int)` was too coarse for QUIC's pacing and
acknowledgement-delay timers, and QUIC re-arms a timer for almost every packet, which was cancelling a `Future`, about
20 µs a timer on kqueue (see "What is open" in [`runtime.md`](runtime.md)). Now `timer(duration: FiniteDuration)`
completes no earlier than its duration, to what the backend can wait for: the nanosecond `kevent` takes on kqueue, the
millisecond NIO's selector waits in; the `Int` form stays, a shorthand. And `deadlineTimer(nanoTime)` is a timer at a
deadline on `System.nanoTime`'s clock that may be moved while it is pending (`Deadline.moveTo`), from any thread, with
no cancel and nothing thrown: a move later stores the new deadline, which the reactor finds when the old one comes, and
files the timer again, as Go's runtime and Netty do; a move earlier files it again on the reactor's thread, or wakes the
reactor from another. The arithmetic is one trait every backend's timer mixes in (`internal.TimerState`). Contract
checks on every backend: a timer finer than a millisecond, moves later and earlier, from another thread to a reactor
blocked waiting, a deadline passed, and a time limit coming first. Measured (release, kqueue, 3 runs, on a machine
loaded to 19, [`bench/results/native-rearm-timers.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/native-rearm-timers.json), `-4-workers.json`): re-arming by cancel and a new future 63.7
µs on one worker and 42.2 on four; a move later 0.003 µs on either; a move earlier 0.003 on one worker and 0.09 on four,
where the mover is often another worker's thread and wakes the timer's reactor.

## Gaps that make them slow, not impossible

| Proposal | Who needs it | Backends |
| --- | --- | --- |
| Vectored reads and writes (`readv`, `writev`) | a TLS record's header with its payload, HTTP/2 and WebSocket frames, HTTP headers with a body, all without copying | kqueue `readv`/`writev`; NIO's scattering and gathering channels; Network.framework gathers a write into one buffer it owns, one copy as a single write has, and scatters what it received. All three, and the datagram sends, IP and Unix. |
| Batched datagrams, several packets a call, with segmentation offload | QUIC's throughput, dominated by system calls per packet | Linux `sendmmsg`/`recvmmsg`, macOS only private calls; none on the JDK; batched sends on Network.framework. An optional capability. |
| A file sent to a socket without copying (`sendfile`, `transferTo`) | static files over HTTP/1 | kqueue and NIO; not Network.framework. An optional capability. |
| Telling that a datagram was truncated (option B when truncation was settled) | mostly diagnostics, as QUIC fixes its packet size | all three, with a copy on NIO |
| `TCP_NOTSENT_LOWAT` | HTTP/2's prioritisation, keeping the kernel's send queue short | through the options |
| An abortive close (RST, through `SO_LINGER` 0) | a server dropping an abusive client | through the options, or a flag on `close` |
| A listen backlog of the caller's choosing | busy servers; it is 4096 on kqueue and NIO, capped by the kernel | kqueue and NIO; Network.framework has a connection limit instead |

## QUIC and TLS from Network.framework itself

Network.framework has QUIC built in (since macOS 12), with its own TLS 1.3, multiplexed streams and QUIC's unreliable
datagrams (RFC 9221), and TLS over TCP; the Network.framework backend, which now uses the framework where it adds what
kqueue cannot (see [`nwframework.md`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/docs/io/nwframework.md)), could offer both as optional capabilities that only it has, so
that a program asking for them does not compile against kqueue or NIO. They map onto the interface's kinds:

- **A QUIC connection** is like a listener of streams: an op opens it (address, ALPN, TLS settings), an accept-like op
  takes each stream the peer opens, and an op opens one of its own.
- **A bidirectional stream** is a `Connection`: `read`, `write`, `shutdownOutput` (its FIN) and `close` as they are; a
  unidirectional one is a `Readable` or a `Writable`.
- **QUIC's datagrams** are a send and a receive on the connection, and **a QUIC listener** accepts connections as a
  TCP listener does.
- **TLS over TCP** is a `Connection` opened with TLS settings, the rest as for any connection.

That leaves two kinds of QUIC for gears, which are not exclusive: the framework's, as a capability of one backend, little
work and macOS's alone; and QUIC in user space over datagrams, on every backend, a large project needing everything in
the lists above (don't-fragment, fine timers re-armed in place, packet info, ECN, batching). What every server needs
either way, addresses and socket options, stays first.

Probed on macOS 27.0, over loopback ([`bench/network-framework-quic.c`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/network-framework-quic.c), a self-signed identity imported into memory):

- **The pieces exist in the C interface since macOS 12**: `nw_parameters_create_quic` with ALPN, TLS identity and
  verification, stream and datagram limits set on its options; a listener whose QUIC connections each arrive as a
  connection group (`nw_listener_set_new_connection_group_handler`); a client's connection group with a multiplex
  descriptor; streams opened with `nw_connection_group_extract_connection` and accepted with the group's new connection
  handler, on either side.
- **A stream is an `nw_connection_t`, read and written as a TCP connection is.** Three client streams (ids 4, 8, 12), a
  unidirectional one (id 2) and a server-opened one (id 5) each carried their bytes and then their end as the
  backend's TCP connections deliver it, a receive complete in the final context: the same events, so the backend's
  stream handling, its send window and its pooled read would serve them as they are.
- **Datagrams are a flow of their own** (options with `nw_quic_set_stream_is_datagram`), whose messages keep their
  boundaries, complete and not final, as a connected UDP socket's do.
- **Opening and accepting are what the interface has**: a stream's opener completes when the stream is ready, and the
  group's handler hands over the peer's streams as a listener hands over connections, so a QUIC connection maps onto
  an op that accepts streams and one that opens them.
- **A stream's type from its metadata is not to be trusted**: the server's own stream, bidirectional by its id (5, a
  server-opened bidirectional stream under RFC 9000) and by what it carried, reported itself a datagram stream on both
  sides, with or without options saying otherwise; its id is what tells.
- **Not probed yet**: a stream reset and its application error, flow control filling a stream's window, the connection
  closing with streams open, and the cost against kqueue over TCP.

## What stays out of the backend interface

- **TLS itself, ALPN and SNI, on kqueue and NIO.** They belong to a TLS layer over `read` and `write`. A native TLS
  library that drives a socket itself would need readiness and the raw descriptor, which only kqueue has; kernel TLS on
  Linux would be a capability of its own, as Network.framework's TLS would (above). Both are optional extras.
- **The WebSocket upgrade.** The connection carries on after HTTP's 101 response. The catch is in a higher level: a
  buffered reader such as `chunks` that has read past the headers must hand those bytes back, which is a point for
  `higherlevel`, not the backends.
- **HTTP/2 reading and writing at once.** A read and a write may already be pending on one handle together;
  serialising many writers is HTTP/2's own.
- **Happy Eyeballs and connect time limits.** Futures racing `connect` ops do this already, and a cancelled `connect`
  leaves nothing open.

## The order proposed

| Step | Change | What it unblocks |
| --- | --- | --- |
| 1 | Local and peer addresses (done) | correctness for every server |
| 2 | Options as a socket opens (done: no-delay, keepalive, buffer sizes, don't-fragment, reuse-port) | correct and fast TLS, HTTP and WebSockets; QUIC's compliance |
| 3 | Nanosecond timers re-armed in place (done) | QUIC |
| 4 | Vectored reads and writes (done) | throughput for everything that frames its data |
| 5 | Optional capabilities: packet info, batched datagrams, files sent without copying | fast QUIC and HTTP on the backends that can |

Each comes with contract checks on every backend that has it.
