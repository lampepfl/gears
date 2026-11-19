//> using scala 3.8.4
//> using jvm system
//> using mainClass LoomBench

/** The scenarios on the JDK's virtual threads and nothing else: blocking channels, a virtual thread for each side of a
  * connection, started and joined by hand.
  */
object LoomBench {
  private object VirtualThreads extends BlockingScenarios.Tasks {
    def all(tasks: Seq[() => Unit]): Unit = {
      val failure = new java.util.concurrent.atomic.AtomicReference[Throwable | Null](null)
      val threads = tasks.map { task =>
        Thread.ofVirtual().start { () =>
          try task()
          catch case thrown: Throwable => failure.compareAndSet(null, thrown): Unit
        }
      }
      threads.foreach(_.join())
      val thrown = failure.get()
      if thrown != null then throw thrown
    }
  }

  def main(args: Array[String]): Unit = {
    val config = BlockingScenarios.parse(args.toList)
    BlockingScenarios.carriers(config.workers)
    // The scenarios themselves run on a virtual thread too, so that making the connections parks as the rest does.
    VirtualThreads.all(List(() => new BlockingScenarios(VirtualThreads).run(config)))
  }
}
