#!/usr/bin/env bash
# Drives the bench's session cache, served by each gears backend and by plain Node.js, and Redis itself, with
# `redis-benchmark`: the same client for every server, and one that is not this repository's. See README.md.
#
#   io-bench/redis/run.sh [requests] [pipelined requests]
#
# Needs redis-server and redis-benchmark, node, a JDK, and the gears bench built:
#   SCALA_NATIVE_NIGHTLY=1 sbt --server 'ioCapBenchJS/fullLinkJS; ioCapBenchJVM/compile; ioCapBenchNative/nativeLink'
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
requests="${1:-200000}"
pipelined="${2:-2000000}"
port=16400
out="$(mktemp -d)"

drive() { # name, then the server's command; it must print a line once it listens
  local name="$1"; shift
  port=$((port + 1))
  "$@" > "$out/server.log" 2>&1 &
  local server=$!
  for _ in $(seq 1 900); do # sbt takes its time to start the JVM's
    if nc -z 127.0.0.1 "$port" 2>/dev/null; then break; fi
    sleep 0.1
  done
  if ! nc -z 127.0.0.1 "$port" 2>/dev/null; then
    echo "$name: did not start" >&2; tail -5 "$out/server.log" >&2; kill "$server" 2>/dev/null; return
  fi
  # A run to warm the server up, then the two measured: one request at a time, and 16 together.
  redis-benchmark -p "$port" -t set,get,incr -n 50000 -c 64 -d 512 -P 16 -q > /dev/null 2>&1
  redis-benchmark -p "$port" -t set,get,incr -n "$requests" -c 64 -d 512 --csv 2>> "$out/client.log" |
    awk -F, -v name="$name" 'NR > 1 { gsub(/"/, ""); printf "%s,single,%s,%s,%s,%s,%s\n", name, $1, $2, $5, $6, $7 }'
  redis-benchmark -p "$port" -t set,get,incr -n "$pipelined" -c 64 -d 512 -P 16 --csv 2>> "$out/client.log" |
    awk -F, -v name="$name" 'NR > 1 { gsub(/"/, ""); printf "%s,pipelined,%s,%s,%s,%s,%s\n", name, $1, $2, $5, $6, $7 }'
  if ! kill -0 "$server" 2>/dev/null; then echo "$name: the server ended" >&2; tail -5 "$out/server.log" >&2; fi
  # The server itself, which for the JVM's is a process sbt forked, and then whatever started it.
  pkill -f -- "--serve $port" 2>/dev/null
  pkill -f -- "--port $port" 2>/dev/null
  kill "$server" 2>/dev/null
  wait "$server" 2>/dev/null
}

echo "server,mode,test,requests per second,p50 ms,p95 ms,p99 ms"
next() { echo $((port + 1)); }

drive "Redis" redis-server --port "$(next)" --save "" --appendonly no
drive "plain Node.js" node io-bench/stacks/node/bench.mjs --serve "$(next)"
drive "gears on Node.js" node target/out/sjs1/scala-3.9.0/iocapbench/iocapbench-opt/main.js --serve "$(next)"
native=target/out/native0.5/scala-3.9.0/iocapbench/iocapbench
for workers in 1 4; do
  SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0 drive "gears kqueue on $workers" "$native" --workers "$workers" --serve "$(next)"
done
for workers in 1 4; do
  for backend in pool threads; do
    SCALA_NATIVE_NIGHTLY=1 drive "gears NIO $backend on $workers" sbt --server -batch -no-colors \
      "ioCapBenchJVM/run --backend $backend --workers $workers --serve $(next)"
  done
done
rm -rf "$out"
