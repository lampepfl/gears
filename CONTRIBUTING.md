# Contributing

## Building `gears`

`gears` currently require:
- **On the JVM**: JVM with support for virtual threads. This usually means JVM 21+, or 19+ with `--enable-preview`.
- **On Scala Native**: Scala Native with delimited continuations support. See the pinned versions in [`dependencies`](./dependencies/README.md).
