# Contributing

## Building `gears`

`gears` currently require:
- **On the JVM**: JVM with support for virtual threads. This usually means JVM 21+, or 19+ with `--enable-preview`.
- **On Scala Native**: Scala Native with delimited continuations support. See the pinned versions in [`dependencies`](./dependencies/README.md).

All of the needed dependencies can be loaded by the included Nix Flake. If you have `nix` with `flake` enabled, run
```
nix develop
```
to enter the development environment with all the dependencies loaded. You can also use [direnv](https://direnv.net/)'s `use flake` to automate this process.
