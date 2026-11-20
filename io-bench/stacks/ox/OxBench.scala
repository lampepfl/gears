//> using scala 3.8.4
//> using jvm system
//> using dep com.softwaremill.ox::core:1.0.2
//> using files ../loom/BlockingScenarios.scala ../loom/Cache.scala
//> using mainClass OxBench

import ox.*

/** The scenarios on Ox: the blocking code of `../loom`, each side of a connection a fork of a supervised scope, which
  * ends when every fork has and fails with the first that fails.
  */
object OxBench {
  private object Forks extends BlockingScenarios.Tasks {
    def all(tasks: Seq[() => Unit]): Unit =
      supervised {
        tasks.map(task => fork(task())).foreach(_.join())
      }
  }

  def main(args: Array[String]): Unit = {
    val config = BlockingScenarios.parse(args.toList)
    BlockingScenarios.carriers(config.workers)
    supervised {
      fork(new BlockingScenarios(Forks).run(config)).join()
    }
  }
}
