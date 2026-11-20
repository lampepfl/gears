# io-bench/stacks/go

The scenarios of `../README.md` in Go, standard library only: a goroutine for each side of each pair, blocking calls
on `net.TCPConn` and `net.UDPConn`, `io.ReadFull` for whole messages. `--workers` sets `GOMAXPROCS`.

Build, from this directory:

```
go build -o bench .
```

Run:

```
./bench --workers 1 --scenarios tcp-pingpong,udp-pingpong --scale 1.0 --connections 64 --repeat 1
```

Every argument is optional; see `../README.md`.

## The realistic scenarios

`tcp-realistic`, `tcp-realistic-bulk`, `tcp-realistic-gather` and `work-realistic` are here, with the workload of
`../node/bench.mjs`: the same requests and answers, byte for byte, every answer checked. RESP is written with `append`
and `strconv` and found by hand. Where it differs from the reference:

- Each side is a goroutine that blocks in `Read` and `Write`. A server answers the `n(2000)` requests of its
  connection and ends, as the gears servers do; it does not wait for the stream's end.
- Requests and answers are appended to slices that are used again, and written from there: there is no copy for each
  write, as Node.js makes to hand a buffer to its socket.
- A count is kept as its digits alone, with no `\r` after them.
- In `tcp-realistic-gather` the answers' parts are a `net.Buffers` written with `WriteTo`, which is `writev`. The lines
  for the lengths and the counts of a batch are in one slice, used again for the next, not a buffer for each.
- `work-realistic` is one goroutine, whatever `--workers` is.
