# io-bench/stacks/node

The scenarios of `../README.md` on Node.js: `node:net` and `node:dgram` with event callbacks, no dependencies.
Node runs its callbacks on one thread, so `--workers` is accepted and ignored.

From the repository root:

```
node io-bench/stacks/node/bench.mjs --scale 0.5
```
