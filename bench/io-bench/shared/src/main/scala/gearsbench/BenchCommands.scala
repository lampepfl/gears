package gearsbench

import gears.async.io.IpAddress
import gears.async.io.IpStreams
import gears.async.io.StreamSockets
import gears.async.io.UnixAddress
import gears.async.io.UnixStreams
import gears.async.io.internal.TakePolicy
import gears.io.demos.{BackendChoice, DemoBackends, DemoRunner, Endpoint}

import mainargs.{arg, main}

/** The gears bench's command line, the same for every backend: `bench` and `bench-serve` over the backends a demo
  * application offers (`DemoBackends`), whose runners start the bench's runs, so that the scenarios, like the demos,
  * only take an `Io`. Each platform's `Main` extends it with its own commands, and runs it as `<executable> gears
  * <command>`, which is what bench/compare.sc runs.
  */
class BenchCommands(backends: DemoBackends) {

  @main(doc = "Stress tests to measure reactor changes against; see bench/compare.sc")
  def bench(
      @arg(doc = "comma-separated scenarios to run, or all of them when empty") scenarios: String = "",
      @arg(doc = "multiplies every scenario's operation count") scale: Double = 1.0,
      @arg(doc = "connections in the concurrent scenario") connections: Int = 64,
      @arg(doc = "use TCP on --host at --port instead of a Unix socket") tcp: mainargs.Flag,
      @arg(doc = "the TCP address: 127.0.0.1, or this machine's, or with --no-server another machine's") host: String =
        "127.0.0.1",
      @arg port: Int = 19876,
      @arg(doc =
        "connect to an echo server already listening (`bench-serve`), instead of starting one"
      ) noServer: mainargs.Flag,
      @arg(doc = "worker threads for the clients, and as many again for the server") workers: Int = 1,
      @arg(doc = "which tasks idle workers take from others: free, affine or hybrid") policy: String = "affine",
      @arg(doc =
        "run every scenario once at a fifth of its count first, so the JIT has compiled it"
      ) warmup: mainargs.Flag,
      choice: BackendChoice
  ): Unit = {
    val names = scenarios.split(',').toSeq.map(_.trim).filter(_.nonEmpty)
    def run[B <: StreamSockets](endpoint: Endpoint[B])(using DemoRunner[B]): Unit =
      GearsBench.run(
        names,
        scale,
        connections,
        endpoint,
        workers,
        takePolicy(policy),
        warmup = warmup.value,
        server = !noServer.value
      )
    if tcp.value then
      run(Endpoint.Ip(IpAddress.IPv4(host, port)))(using
        backends.needing(choice.backend, "IP stream sockets") { case b: IpStreams => b }
      )
    else
      run(Endpoint.Unix(UnixAddress(s"/tmp/gears-bench-${System.nanoTime() % 1_000_000}.sock")))(using
        backends.needing(choice.backend, "Unix stream sockets") { case b: UnixStreams => b }
      )
  }

  @main(name = "bench-serve", doc = "The bench's echo server alone, for a bench on another machine with --no-server")
  def benchServe(
      @arg(doc = "the address to listen on: this machine's, or 0.0.0.0") host: String = "0.0.0.0",
      @arg port: Int = 19876,
      @arg(doc = "worker threads for the server") workers: Int = 1,
      @arg(doc = "which tasks idle workers take from others: free, affine or hybrid") policy: String = "affine",
      choice: BackendChoice
  ): Unit =
    GearsBench.serveForever(Endpoint.Ip(IpAddress.IPv4(host, port)), workers, takePolicy(policy))(using
      backends.needing(choice.backend, "IP stream sockets") { case b: IpStreams => b }
    )

  private def takePolicy(policy: String): TakePolicy = TakePolicy.values
    .find(_.toString.equalsIgnoreCase(policy))
    .getOrElse(throw new IllegalArgumentException(s"Unknown policy `$policy`; try free, affine or hybrid"))
}
