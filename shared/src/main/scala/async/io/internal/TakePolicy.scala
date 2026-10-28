package gears.async.io
package internal

/** Which of a worker's tasks other workers may take when they run out of work. */
enum TakePolicy {

  /** Any queued task. Resumes then join a worker's queue in order with everything else, so a task a resume spawns waits
    * for the next round, after a turn of the reactor, where under the other policies it runs in the same round: a new
    * connection's first read, in `connections`, takes twice as long, even with one worker.
    */
  case Free

  /** Only tasks that are not resumes from the worker's own reactor, such as new futures and yields: a task doing I/O
    * stays on the worker whose reactor has its descriptors. The default.
    */
  case Affine

  /** As `Affine`, except that a worker with a backlog of resumes shares half of it. */
  case Hybrid
}
