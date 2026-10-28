package cebench

object Platform {

  /** How the program was started. */
  def targetInfo: Seq[String] =
    Seq(s"java: ${System.getProperty("java.version")}", s"vm: ${System.getProperty("java.vm.name")}")
}
