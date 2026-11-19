#!/bin/bash
# Runs a command in the Linux container this project builds and runs in (docker/Dockerfile), over a copy of the
# working tree as it is now, uncommitted changes and all:
#
#   docker/run.sh                                          a shell
#   docker/run.sh sbt --batch ioPollerNative/testFull      the native backend's tests, over epoll
#   SCALA_NATIVE_NIGHTLY=1 docker/run.sh sbt --batch ioCapBenchNative/nativeLink
#
# The copy, and what sbt and coursier download, are kept in volumes between runs, so only the first is slow. The
# host's tree is mounted read-only: nothing the container builds comes back to it. What an editor leaves in the tree
# for itself (metals.sbt, *.semanticdb) is not copied.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
image=gears-linux-build

docker build -q -t "$image" "$here/docker" > /dev/null

tty=()
if [ -t 0 ] && [ -t 1 ]; then tty=(-it); fi

sync='rsync -a --delete --exclude target --exclude .bsp --exclude .bloop --exclude .metals --exclude .claude --exclude .scala-build --exclude metals.sbt --exclude '*.semanticdb' /src/ /work/'
if [ $# -eq 0 ]; then set -- bash; fi

exec docker run --rm ${tty[@]+"${tty[@]}"} \
  -v "$here":/src:ro \
  -v gears-linux-work:/work \
  -v gears-linux-cache:/root/.cache \
  -v gears-linux-ivy:/root/.ivy2 \
  -v gears-linux-sbt:/root/.sbt \
  -e SCALA_NATIVE_NIGHTLY="${SCALA_NATIVE_NIGHTLY:-}" \
  -e SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS="${SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS:-}" \
  "$image" bash -c "$sync && exec \"\$@\"" -- "$@"
