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
