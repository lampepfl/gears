//! The IP socket scenarios of `io-bench/stacks/README.md`, on Tokio: a spawned task for each side of each pair.

use std::io;
use std::net::SocketAddr;
use std::process::ExitCode;
use std::time::Instant;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::task::JoinHandle;

const MESSAGE: usize = 64;
const CHUNK: usize = 65536;
const LOOPBACK: &str = "127.0.0.1:0";

const ALL: [&str; 7] = [
    "tcp-pingpong",
    "tcp-concurrent",
    "tcp-stream",
    "tcp-connect",
    "udp-pingpong",
    "udp-concurrent",
    "udp-connected",
];

struct Args {
    workers: usize,
    scenarios: Vec<String>,
    scale: f64,
    connections: usize,
    repeat: usize,
}

fn parse_args() -> Result<Args, String> {
    let mut args = Args {
        workers: 1,
        scenarios: ALL.iter().map(|s| s.to_string()).collect(),
        scale: 1.0,
        connections: 64,
        repeat: 1,
    };
    let mut rest = std::env::args().skip(1);
    while let Some(flag) = rest.next() {
        let value = rest.next().ok_or_else(|| format!("{flag} needs a value"))?;
        let bad = |_| format!("{flag}: not a number: {value}");
        match flag.as_str() {
            "--workers" => args.workers = value.parse().map_err(bad)?,
            "--scale" => args.scale = value.parse().map_err(|_| format!("{flag}: not a number: {value}"))?,
            "--connections" => args.connections = value.parse().map_err(bad)?,
            "--repeat" => args.repeat = value.parse().map_err(bad)?,
            "--scenarios" => {
                args.scenarios = value.split(',').filter(|s| !s.is_empty()).map(str::to_string).collect()
            }
            _ => return Err(format!("unknown argument: {flag}")),
        }
    }
    if args.workers == 0 {
        return Err("--workers must be at least 1".to_string());
    }
    Ok(args)
}

type Task = JoinHandle<io::Result<()>>;

/// Waits for every task, and gives the first failure of any.
async fn join(tasks: Vec<Task>) -> io::Result<()> {
    let mut result = Ok(());
    for task in tasks {
        let one = task.await.map_err(io::Error::other).and_then(|r| r);
        if result.is_ok() {
            result = one;
        }
    }
    result
}

fn report(name: &str, ops: u64, start: Instant) {
    let seconds = start.elapsed().as_secs_f64();
    println!(
        "bench: {:<20} {:>9} ops {:>8.3} s {:>9.3} us/op",
        name,
        ops,
        seconds,
        seconds * 1e6 / ops as f64
    );
}

// ---- TCP

/// `count` connections through one listener, one after another, each as (client end, server end).
async fn connected(count: usize) -> io::Result<(TcpListener, Vec<(TcpStream, TcpStream)>)> {
    let listener = TcpListener::bind(LOOPBACK).await?;
    let address = listener.local_addr()?;
    let mut pairs = Vec::with_capacity(count);
    for _ in 0..count {
        let connecting = tokio::spawn(TcpStream::connect(address));
        let (server, _) = listener.accept().await?;
        let client = connecting.await.map_err(io::Error::other)??;
        client.set_nodelay(true)?;
        server.set_nodelay(true)?;
        pairs.push((client, server));
    }
    Ok((listener, pairs))
}

async fn tcp_echo(name: &str, connections: usize, rounds: usize) -> io::Result<()> {
    let (listener, pairs) = connected(connections).await?;
    let mut tasks = Vec::with_capacity(2 * connections);
    let start = Instant::now();
    for (mut client, mut server) in pairs {
        tasks.push(tokio::spawn(async move {
            let mut buf = [0u8; MESSAGE];
            for _ in 0..rounds {
                server.read_exact(&mut buf).await?;
                server.write_all(&buf).await?;
            }
            Ok(server)
        }));
        tasks.push(tokio::spawn(async move {
            let out = [0u8; MESSAGE];
            let mut buf = [0u8; MESSAGE];
            for _ in 0..rounds {
                client.write_all(&out).await?;
                client.read_exact(&mut buf).await?;
            }
            Ok(client)
        }));
    }
    // The tasks give their streams back, so that closing them is outside the timing.
    let mut streams = Vec::with_capacity(tasks.len());
    let mut result: io::Result<()> = Ok(());
    for task in tasks {
        match task.await.map_err(io::Error::other).and_then(|r: io::Result<TcpStream>| r) {
            Ok(stream) => streams.push(stream),
            Err(e) if result.is_ok() => result = Err(e),
            Err(_) => {}
        }
    }
    result?;
    report(name, rounds as u64 * connections as u64, start);
    drop(streams);
    drop(listener);
    Ok(())
}

async fn tcp_stream(chunks: usize) -> io::Result<()> {
    let (listener, mut pairs) = connected(1).await?;
    let (mut client, mut server) = pairs.pop().expect("one connection");
    let start = Instant::now();
    let writer: JoinHandle<io::Result<TcpStream>> = tokio::spawn(async move {
        let buf = vec![0u8; CHUNK];
        for _ in 0..chunks {
            client.write_all(&buf).await?;
        }
        client.shutdown().await?;
        Ok(client)
    });
    let reader: JoinHandle<io::Result<TcpStream>> = tokio::spawn(async move {
        let mut buf = vec![0u8; CHUNK];
        while server.read(&mut buf).await? != 0 {}
        Ok(server)
    });
    let client = writer.await.map_err(io::Error::other);
    let server = reader.await.map_err(io::Error::other);
    let streams = (client??, server??);
    report("tcp-stream", chunks as u64, start);
    drop(streams);
    drop(listener);
    Ok(())
}

async fn tcp_connect(count: usize) -> io::Result<()> {
    let listener = TcpListener::bind(LOOPBACK).await?;
    let address = listener.local_addr()?;
    let start = Instant::now();
    let server: JoinHandle<io::Result<TcpListener>> = tokio::spawn(async move {
        for _ in 0..count {
            let (accepted, _) = listener.accept().await?;
            drop(accepted);
        }
        Ok(listener)
    });
    let client: Task = tokio::spawn(async move {
        let mut buf = [0u8; 8];
        for _ in 0..count {
            let mut connection = TcpStream::connect(address).await?;
            // Until the server has closed its end.
            while connection.read(&mut buf).await? != 0 {}
            drop(connection);
        }
        Ok(())
    });
    let listener = server.await.map_err(io::Error::other);
    let client = client.await.map_err(io::Error::other);
    let listener = listener??;
    client??;
    report("tcp-connect", count as u64, start);
    drop(listener);
    Ok(())
}

// ---- UDP

/// The two tasks of an echo over unconnected sockets. As in `CapBench`, the sockets are bound within the timing, and
/// closed within it as each task ends.
async fn udp_echo(rounds: usize) -> io::Result<[Task; 2]> {
    let server = UdpSocket::bind(LOOPBACK).await?;
    let client = UdpSocket::bind(LOOPBACK).await?;
    let server_address: SocketAddr = server.local_addr()?;
    let client_address: SocketAddr = client.local_addr()?;
    let echo = tokio::spawn(async move {
        let mut buf = [0u8; MESSAGE];
        for _ in 0..rounds {
            let (length, _) = server.recv_from(&mut buf).await?;
            server.send_to(&buf[..length], client_address).await?;
        }
        Ok(())
    });
    let asking = tokio::spawn(async move {
        let out = [0u8; MESSAGE];
        let mut buf = [0u8; MESSAGE];
        for _ in 0..rounds {
            client.send_to(&out, server_address).await?;
            client.recv_from(&mut buf).await?;
        }
        Ok(())
    });
    Ok([echo, asking])
}

async fn udp_unconnected(name: &str, pairs: usize, rounds: usize) -> io::Result<()> {
    let start = Instant::now();
    let mut tasks = Vec::with_capacity(2 * pairs);
    for _ in 0..pairs {
        tasks.extend(udp_echo(rounds).await?);
    }
    join(tasks).await?;
    report(name, rounds as u64 * pairs as u64, start);
    Ok(())
}

async fn udp_connected(rounds: usize) -> io::Result<()> {
    let start = Instant::now();
    let server = UdpSocket::bind(LOOPBACK).await?;
    let client = UdpSocket::bind(LOOPBACK).await?;
    server.connect(client.local_addr()?).await?;
    client.connect(server.local_addr()?).await?;
    let echo: Task = tokio::spawn(async move {
        let mut buf = [0u8; MESSAGE];
        for _ in 0..rounds {
            let length = server.recv(&mut buf).await?;
            server.send(&buf[..length]).await?;
        }
        Ok(())
    });
    let asking: Task = tokio::spawn(async move {
        let out = [0u8; MESSAGE];
        let mut buf = [0u8; MESSAGE];
        for _ in 0..rounds {
            client.send(&out).await?;
            client.recv(&mut buf).await?;
        }
        Ok(())
    });
    join(vec![echo, asking]).await?;
    report("udp-connected", rounds as u64, start);
    Ok(())
}

// ---- the run

async fn run(args: &Args) -> io::Result<()> {
    let n = |x: usize| ((x as f64 * args.scale) as usize).max(1);
    for _ in 0..args.repeat {
        for scenario in &args.scenarios {
            match scenario.as_str() {
                "tcp-pingpong" => tcp_echo("tcp-pingpong", 1, n(100_000)).await?,
                "tcp-concurrent" => tcp_echo("tcp-concurrent", args.connections, n(5_000)).await?,
                "tcp-stream" => tcp_stream(n(20_000)).await?,
                "tcp-connect" => tcp_connect(n(1_000)).await?,
                "udp-pingpong" => udp_unconnected("udp-pingpong", 1, n(100_000)).await?,
                "udp-concurrent" => udp_unconnected("udp-concurrent", args.connections, n(5_000)).await?,
                "udp-connected" => udp_connected(n(100_000)).await?,
                other => eprintln!("unknown scenario: {other}"),
            }
        }
    }
    Ok(())
}

fn main() -> ExitCode {
    let args = match parse_args() {
        Ok(args) => args,
        Err(message) => {
            eprintln!("{message}");
            return ExitCode::from(2);
        }
    };
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(args.workers)
        .enable_all()
        .build()
        .expect("the runtime");
    match runtime.block_on(run(&args)) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("failed: {error}");
            ExitCode::FAILURE
        }
    }
}
