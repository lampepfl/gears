package gears.async.io.internal

/** Transfers an acquired resource to a consumer, or discards it if publication is declined or throws. The caller
  * supplies its own publication policy, including any cancellation lock; this algorithm owns no lifecycle state.
  */
private[io] object ResourcePublication {
  inline def apply[A](resource: A)(inline publish: A => Boolean)(inline discard: A => Unit): Unit = {
    var published = false
    try published = publish(resource)
    finally if !published then discard(resource)
  }
}
