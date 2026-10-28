# What each host could support

A survey, 2026-10-27, of what the interface's capabilities and its planned additions ([`missing-api.md`](missing-api.md))
would be on the backends that exist and on those discussed: kqueue, NIO, the Network.framework backend, and io_uring,
Node (and Scala.js or Wasm on it), Deno, Cloudflare Workers, a browser, and WASI 0.2 and 0.3. Only the first three are
built. Marked *unverified* is what comes from memory of a host's documentation, not a probe or a check of its current
API; nothing here about the unbuilt backends has been run.

## The capabilities today

| | kqueue | NIO | NW framework | io_uring | Node | Deno | Workers | browser | WASI 0.2 |
|---|---|---|---|---|---|---|---|---|---|
| `IpStreams` | yes | yes | yes | yes | yes | yes | connect only, no listen | no | yes |
| `IpDatagrams` | yes | yes | yes | yes | yes | behind `--unstable-net` | no | no | yes |
| `UnixStreams` | yes | yes (JDK 16+) | yes, kqueue's | yes | yes | yes, *unverified* whether stable | no | no | no |
| `UnixDatagrams` | yes | no (no JDK channel) | yes, kqueue's | yes | no (removed from Node) | behind a flag, *unverified* | no | no | no |
| `RegularFiles` | blocker threads | blocker threads | kqueue's | the ring, truly asynchronous | libuv's thread pool | yes | no | OPFS in a worker, a capability of its own | `wasi:filesystem` |
| `Pipes` | yes | yes | kqueue's | yes | no in-process pipe | no | no | no | no |
| `Fifos` | yes, with `FifoWatcher` | no (can only block) | kqueue's | yes, plain reads | through `fs`, blocking the pool | *unverified* | no | no | no |
| `Resolving` | `getaddrinfo` on a blocker | the JDK's, on a blocker | kqueue's | `getaddrinfo` on a thread | `dns.lookup` (`getaddrinfo`) | `Deno.resolveDns`, DNS only, no hosts file | no | no | `ip-name-lookup` |
| `Blocking` | blocker threads | blocker threads | kqueue's | threads | no | no | no | no | no (no threads in 0.2) |
| kind | reactor | reactor or caller | reactor | reactor | the host's loop | the host's loop | the host's loop | the host's loop | reactor over `wasi:io/poll` |
| addresses, at once | yes | yes | yes | yes (`getsockname`) | yes; a Unix peer remembered | yes | from `opened`, *unverified* | n/a | yes |
| pooled read | `NativePool` | `DirectPool` | the framework's content | provided-buffer rings | Node's own `Buffer`s | yes | yes | n/a | the host's lists of bytes |

What this says about the interface:

- **The JavaScript hosts need a third kind.** `internal.Submitter` already says a backend may have "no loop of its own, such as
  one over a JavaScript host": neither a `ReactorBackend`, whose loop the caller drives, nor a `CallerBackend`, which
  blocks. No trait or `Io` for it exists yet; Node, Deno and Workers each need one before they can be backends.
  WASI 0.2 does not: one instance's thread polls `wasi:io/poll`, a reactor; 0.3's native async would be the host's
  loop again.
- **The capabilities split as the hosts do.** Workers have IP streams and nothing else, and only to connect; Node and
  NIO have no Unix datagrams; WASI and Workers have no Unix sockets. Each is a capability now, so each backend offers
  what it has. Connecting apart from listening is the one split left, needed only by a Workers backend.
- **The contracts assume pipes and blocking ops.** `ReactorContract` asks for `Pipes` and `Blocking`, since
  its streams are pipes; no JavaScript host has in-process pipes, and none but WASI has a loop the contract can drive.
  A backend there would need contracts whose streams are sockets, and a way to run them on the host's loop.
- **Files and FIFOs are where io_uring differs most**: its file ops are asynchronous at the device, with no blocker
  threads, and a FIFO's read is a read, with no `select` thread.

## Semantics that change on a completion-based backend

kqueue and NIO wait for readiness, then do the operation, so an op cancelled or timed out has done nothing. io_uring,
Network.framework, Node and WASI's streams hand the operation over first. The Network.framework backend already shows
what keeping the same promises costs:

- **A cancelled read must not lose bytes.** A receive once issued cannot be withdrawn there, so what it brings goes to a
  stash the next read takes. io_uring is the same: a cancel (`IORING_OP_ASYNC_CANCEL`) can lose the race, and the
  completion then carries bytes, which must go to the stash, or with provided buffers stay in the ring's buffer for the
  next read. Node reads in paused mode (`socket.read`), taking nothing it is not asked for; WASI's reads only take
  what is there, returning at once.
- **"A write that timed out wrote nothing"** (a contract check) holds on readiness and with a window: the
  Network.framework backend hands over only what its send window has room for. Node's `writable.write` returning false
  at its high-water mark, and WASI's `check-write`, which says how many bytes may be written now, are the same window.
  io_uring's send is the exception: one cancelled after the kernel copied part of it completes with that count, which
  the contract would take for a write that did something after its time limit. It keeps the promise only by waiting
  for writability first (`IORING_OP_POLL_ADD`, then a non-blocking send), which gives back what the ring saves, or the
  contract narrows to "a write that timed out sent nothing it reports", which the reactor delivers as a completion. To
  decide before an io_uring backend.
- **Closing completes at once** on all of them, as a kernel socket's close does, with the Network.framework backend's
  linger limit where a close only queues a cancel.

## The planned additions

| | kqueue (macOS) | NIO | NW framework's own sockets | io_uring (Linux) | Node | WASI 0.2 |
|---|---|---|---|---|---|---|
| `TCP_NODELAY` | `setsockopt` | yes | `nw_tcp_options_set_no_delay` | `setsockopt`, or the ring's own (6.7+) | `setNoDelay` | *unverified*: not in 0.2 as far as I know |
| keepalive and its timings | yes | on, and timings through `ExtendedSocketOptions` | `nw_tcp_options` | yes | on, and its first delay only | yes |
| buffer sizes | yes | yes | not exposed for TCP | yes | datagrams only | yes |
| don't-fragment (QUIC) | `IP_DONTFRAG`, `IPV6_DONTFRAG` (probed) | `IP_DONTFRAGMENT` (JDK 19+, probed) | `nw_ip_options_set_disable_fragmentation` | `IP_MTU_DISCOVER` | no | no |
| `SO_REUSEPORT` | yes; the last bound gets everything (probed) | where the system has it | no | yes, spread by peer | recent versions, *unverified* | no |
| ECN, per packet | `IP_RECVTOS`, *unverified* on macOS | no (sets TOS, cannot read it) | per message metadata | `recvmsg` control messages | no | no |
| packet info | `IP_RECVPKTINFO` | no | no (no bound socket) | `recvmsg` control messages | no | no |
| fine re-armable timers | own heap | own heap | own heap | `IORING_OP_TIMEOUT`, updated in place | `setTimeout`, milliseconds | `monotonic-clock`, nanoseconds |
| vectored I/O | `readv`/`writev` | scattering and gathering channels | `dispatch_data` concatenated | `IORING_OP_READV`/`WRITEV` | `writev` on streams | lists of bytes |
| batched datagrams | no (private calls only) | no | batched sends | multishot `recvmsg`, GSO | no | a list per call |
| `sendfile` | yes | `transferTo` | no | `splice` | no | no |
| truncation flag | `MSG_TRUNC` | no | no | in the completion | no | no |
| native QUIC and TLS | no | no | yes ([`missing-api.md`](missing-api.md)) | kernel TLS only | TLS (`tls`), QUIC *unverified* | no |

So: options must be per option, each one a backend has or refuses clearly, since no option is everywhere; the
Network.framework backend takes its own sockets' options only as they open, and kqueue's sockets there take them at
any time, which the interface's options-at-open shape covers for both. Packet info, ECN, batching and `sendfile` are
optional capabilities, held by kqueue and io_uring; fine timers every backend can give, Node's to the millisecond.
