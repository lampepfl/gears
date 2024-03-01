## Custom Dependencies for Scala Native

Scala Native requires some libraries to be compiled from source and `publishLocal`'d.

### TL; DR

You need to have all the [dependencies to build Scala Native](https://scala-native.org/en/stable/user/setup.html). Run:
```bash
./publish-deps.sh
```

Or if you have `nix` with `flake` enabled, run the following from this repo's root:
```
nix develop .#dependencies -c dependencies/publish-deps.sh
```

### What are included?

- A fork of `munit` that uses `scala-native` 0.5-RC1.
  Pinned in `munit`.
  ```bash
  sbt "munitNative/publishLocal"
  ```
