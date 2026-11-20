# A Linux in which this project builds and runs: the JDK the build is written for, sbt, and clang for Scala Native,
# whose native backend is over epoll there. See `docker/run.sh`.
FROM eclipse-temurin:25-jdk

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      clang lld libc6-dev zlib1g-dev git curl rsync ca-certificates unzip procps \
 && rm -rf /var/lib/apt/lists/*

# The version project/build.properties asks for: the launcher fetches it either way, and this spares the first run.
ARG SBT_VERSION=2.0.12
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar xz -C /opt \
 && printf '#!/bin/sh\n# In the foreground: the thin client gives up on a server that is still fetching what it needs.\nexec /opt/sbt/bin/sbt --server "$@"\n' > /usr/local/bin/sbt \
 && chmod +x /usr/local/bin/sbt

# The sources are a copy, in a volume of the container's own (see run.sh): the host's `target` directories hold what
# its own system built, and are no use here.
WORKDIR /work
CMD ["bash"]
