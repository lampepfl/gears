#!/usr/bin/env bash

set -e

cd "$(dirname "${BASH_SOURCE[0]}")" # change to this directory

cd munit && sbt "++3.1.2 munitNative/publishLocal" && cd ..

