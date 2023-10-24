## Custom Dependencies for Scala Native

Scala Native requires some libraries to be compiled from source and `publishLocal`'d.

### TL; DR

```bash
./publish-deps.sh
```

### What are included?

- The current snapshot version of Scala Native, pinned in `scala-native`: for the delimited continuation support.
  This needs to be published for both `3.3.1` (for `gears`) and `3.1.2` (for `munit`):
  ```bash
  sbt "publish-local-dev 3; ++3.1.2 publishLocal"
  ```
- A fork of `munit` that uses the above snapshot, with a simple fix (https://github.com/scalameta/munit/pull/714) to make it compile.
  Pinned in `munit`.
  ```bash
  sbt "munitNative/publishLocal"
  ```
