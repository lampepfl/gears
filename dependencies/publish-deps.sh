#!/usr/bin/env bash

set -e

cd "$(dirname "${BASH_SOURCE[0]}")" # change to this directory

cd scala-native && sbt "publish-local-dev 3; ++3.1.2 publishLocal" && cd ..
cd munit && sbt "++3.1.2 munitNative/publishLocal"
