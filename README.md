# gears

![GitHub Release](https://img.shields.io/github/v/release/lampepfl/gears)
![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/lampepfl/gears/ci.yml)
[![Homepage](https://img.shields.io/badge/website-homepage-brightgreen)](https://lampepfl.github.io/gears)
[![API Documentation Link](https://img.shields.io/badge/api-documentation-brightgreen)](https://lampepfl.github.io/gears/api)

An Experimental Asynchronous Programming Library for Scala 3. It aims to be:
- **Simple**: enables direct-style programming (suspending with `.await`, calling Async-functions directly) and comes with few simple concepts.
- **Structured**: allows an idiomatic way of structuring concurrent programs minimizing computation leaking (*structured concurrency*), while
  providing a toolbox for dealing with external, unstructured events.
- **Cross-platform**: Works on both JVM >=21 and Scala Native.

## Getting Started

The [Gears Book](https://natsukagami.github.io/gears-book) is a great way to getting started with programming using Gears.
It provides a tutorial, as well as a guided walkthrough of all concepts available within Gears.

### Adding `gears` to your dependencies

With `sbt`:
```scala
  libraryDependencies += "ch.epfl.lamp" %%% "gears" % "<version>",
```

With `mill`:
```scala
def ivyDeps = Agg(
  // ... other dependencies
  ivy"ch.epfl.lamp::gears:<version>"
)
```

With `scala` (since 3.5.0) or `scala-cli`:
```scala
//> using dep "ch.epfl.lamp::gears:<version>"
```

## Setting up on an unpublished version of Gears

You will need JDK >= 21 and [Scala Native](https://scala-native.org) set up.
```bash
sbt publishLocal
```

## Contributing

We are happy to take **issues**, **pull requests** and **discussions**!

For a quick look at our development environment and workflow, check our the [contributing guide](./CONTRIBUTING.md).

### Related Projects

You might also be interested in:
- [**ox**](https://github.com/softwaremill/ox): Safe direct-style concurrency and resiliency for Scala on the JVM.

## License

Copyright 2024 LAMP, EPFL.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

See [LICENSE](./LICENSE) for more details.
