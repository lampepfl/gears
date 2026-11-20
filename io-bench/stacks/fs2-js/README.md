# io-bench/stacks/fs2-js

The realistic scenarios of `../README.md`, and the echo they are set beside (`tcp-concurrent`), on
[fs2-io](https://fs2.io) 3.16.0 and Cats Effect 3.7.1 for Scala.js 1.22.0, over Node.js's sockets: `Network[IO]`
sockets as `Resource`s, a fiber for each side of a pair, each read a `Socket.read` and each write a `Socket.write`.
It has one thread, Node's; `--workers` is taken and ignored.

`Cache.scala` is the session cache, its client and the protocol, the same source as `CapBench`'s
(`io-bench/shared`), copied: the work is the work the gears backends do. fs2 has no gathering write, so
`tcp-realistic-gather` puts an answer's chunks together (`++`), a value's own chunk among them, and writes that.

It is built two ways, to JavaScript and to WebAssembly. From the root of the repository (needs `scala-cli`, and
Node.js 26 or later for the second):

```
scala-cli --power package io-bench/stacks/fs2-js --js-mode release -o /tmp/fs2-js/main.mjs
node /tmp/fs2-js/main.mjs --scenarios tcp-realistic,work-realistic

scala-cli --power package io-bench/stacks/fs2-js --js-mode release --js-emit-wasm -o /tmp/fs2-wasm
node /tmp/fs2-wasm/main.js --scenarios tcp-realistic,work-realistic
```
