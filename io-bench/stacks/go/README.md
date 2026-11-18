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
