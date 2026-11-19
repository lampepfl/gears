# io-bench/stacks/tokio

The scenarios of `../README.md` on [Tokio](https://tokio.rs): a `tokio::spawn`ed task for each side of each pair, on
the multi-thread runtime with `--workers` worker threads (also when that is 1).

Build (cargo 1.90 or later), from the repository's root:

```
cargo build --release --manifest-path io-bench/stacks/tokio/Cargo.toml
```

Run:

```
io-bench/stacks/tokio/target/release/io-bench-tokio --workers 1
```

with the arguments of `../README.md`.

The scenarios themselves run on the thread that called `block_on`, which is not a worker: it makes the connections,
spawns the tasks and waits for them, and does none of the timed work itself.

## The realistic scenarios

`tcp-realistic`, `tcp-realistic-bulk`, `tcp-realistic-gather` and `work-realistic` are here, with the workload of
`../node/bench.mjs`: the same requests, byte for byte, and every answer checked. How they differ from it:

- Each side of a connection is a task that reads into one buffer of its own and moves what is left of a value to the
  buffer's start before the next read, where Node.js is given a new buffer by each read.
- A server's task ends when it has answered its connection's `n(2000)` commands, not at the end of the stream.
- `tcp-realistic-gather` keeps each value as an `Arc<[u8]>` and writes a batch's answers with `write_vectored`, as many
  times as it takes; the lines for lengths and counts of a batch are in one buffer, each a part of the write.
- The tables are `HashMap`s with the standard library's hasher, keyed by the key's bytes. The client writes what an
  answer must be into a buffer it keeps for that place in the batch.
- `work-realistic` is one spawned task, so a worker thread does it, one client after another whatever `--workers` is.
- A scenario with an unknown name prints nothing on standard output, and a line on standard error, as before.
