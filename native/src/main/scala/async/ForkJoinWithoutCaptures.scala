package gears.async.native

import scalanative.runtime.{Continuations => nativeContinuations}

def run[R](body: nativeContinuations.BoundaryLabel[R] ?=> R): R =
  nativeContinuations.boundary(body) // SAFETY: tracked by this package's mechanism
