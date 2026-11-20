//! The IP socket scenarios of `io-bench/stacks/README.md`, on Tokio: a spawned task for each side of each pair.

use std::collections::HashMap;
use std::io::{self, IoSlice, Write};
use std::net::SocketAddr;
use std::ops::Range;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Instant;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::task::JoinHandle;

const MESSAGE: usize = 64;
const CHUNK: usize = 65536;
const LOOPBACK: &str = "127.0.0.1:0";

const ALL: [&str; 11] = [
    "tcp-pingpong",
    "tcp-concurrent",
    "tcp-stream",
    "tcp-connect",
    "tcp-realistic",
    "tcp-realistic-bulk",
    "tcp-realistic-gather",
    "work-realistic",
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

/// Waits for every task and gives the streams they give back, so that closing them is outside the timing; or the
/// first failure of any.
async fn join_streams(tasks: Vec<JoinHandle<io::Result<TcpStream>>>) -> io::Result<Vec<TcpStream>> {
    let mut streams = Vec::with_capacity(tasks.len());
    let mut result: io::Result<()> = Ok(());
    for task in tasks {
        match task.await.map_err(io::Error::other).and_then(|r| r) {
            Ok(stream) => streams.push(stream),
            Err(e) if result.is_ok() => result = Err(e),
            Err(_) => {}
        }
    }
    result.map(|()| streams)
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
    let streams = join_streams(tasks).await?;
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

// ---- a session cache that speaks Redis's protocol, and a client of it (see ../README.md)

const LARGEST: usize = 2048;
/// How many requests go at once in `tcp-realistic-bulk`.
const BATCH: usize = 16;
const LARGEST_BATCH: usize = BATCH * LARGEST;

const OK: &[u8] = b"+OK\r\n";
const NOTHING: &[u8] = b"$-1\r\n";
const UNKNOWN: &[u8] = b"-ERR unknown command\r\n";
const CRLF: &[u8] = b"\r\n";

/// Where the line that begins at `at` ends, past its `\r\n`; `None` if it has not all come.
fn line_end(bytes: &[u8], at: usize) -> Option<usize> {
    bytes[at..].windows(2).position(|pair| pair == CRLF).map(|i| at + i + 2)
}

/// The decimal number from `at` to the end of its line.
fn number(bytes: &[u8], at: usize) -> i64 {
    let negative = bytes[at] == b'-';
    let mut value = 0i64;
    for &digit in bytes[at + negative as usize..].iter().take_while(|&&b| b != b'\r') {
        value = value * 10 + (digit - b'0') as i64;
    }
    if negative { -value } else { value }
}

/// Where the RESP value that begins at `at` ends; `None` if it has not all come.
fn resp_end(bytes: &[u8], at: usize) -> Option<usize> {
    let line = line_end(bytes, at)?;
    match bytes[at] {
        b'$' => {
            let length = number(bytes, at + 1);
            if length < 0 {
                return Some(line);
            }
            let end = line + length as usize + 2;
            (end <= bytes.len()).then_some(end)
        }
        b'*' => {
            let mut next = line;
            for _ in 0..number(bytes, at + 1) {
                next = resp_end(bytes, next)?;
            }
            Some(next)
        }
        _ => Some(line), // a status, an error or an integer: its line
    }
}

/// Writes a bulk string: `$`, its length, a line's end, its bytes, a line's end.
fn put_bulk(into: &mut Vec<u8>, bytes: &[u8]) {
    put_line(into, b'$', bytes.len() as u64);
    into.extend_from_slice(bytes);
    into.extend_from_slice(CRLF);
}

/// Writes `mark`, `number` in decimal and the end of a line.
fn put_line(into: &mut Vec<u8>, mark: u8, number: u64) {
    into.push(mark);
    write!(into, "{number}\r\n").expect("writing to a Vec");
}

/// A command the cache knows, its key and value still in the request's bytes.
enum Command<'a> {
    Set(&'a [u8], &'a [u8]),
    Get(&'a [u8]),
    Incr(&'a [u8]),
    Unknown,
}

/// The command that is the whole of `request`, an array of bulk strings.
fn command(request: &[u8]) -> Command<'_> {
    // The bulk string at `at`, and where the next begins. The command is whole, so its lines are.
    let bulk = |at: usize| {
        let length = number(request, at + 1) as usize;
        let start = line_end(request, at).expect("a whole command");
        (&request[start..start + length], start + length + 2)
    };
    let parts = number(request, 1);
    let (name, at) = bulk(line_end(request, 0).expect("a whole command"));
    let (key, at) = bulk(at);
    match (name.len(), name[0]) {
        (3, b'S') if parts == 3 => Command::Set(key, bulk(at).0),
        (3, b'G') => Command::Get(key),
        (4, b'I') => Command::Incr(key),
        _ => Command::Unknown,
    }
}

/// The cache: what it keeps for one connection, and how it answers `SET`, `GET` and `INCR`.
#[derive(Default)]
struct CacheServer {
    values: HashMap<Vec<u8>, Vec<u8>>,
}

impl CacheServer {
    /// Answers `request`, one whole command, at the end of `response`.
    fn handle(&mut self, request: &[u8], response: &mut Vec<u8>) {
        match command(request) {
            Command::Set(key, value) => {
                self.values.insert(key.to_vec(), value.to_vec()); // a copy: the cache's own
                response.extend_from_slice(OK);
            }
            Command::Get(key) => match self.values.get(key) {
                Some(value) => put_bulk(response, value),
                None => response.extend_from_slice(NOTHING),
            },
            Command::Incr(key) => {
                // Kept as Redis keeps a count, its digits (and the end of their line, for `number`).
                let held = self.values.entry(key.to_vec()).or_default();
                let count = if held.is_empty() { 0 } else { number(held, 0) } as u64 + 1;
                held.clear();
                write!(held, "{count}\r").expect("writing to a Vec");
                put_line(response, b':', count);
            }
            Command::Unknown => response.extend_from_slice(UNKNOWN),
        }
    }
}

/// A part of an answer that `tcp-realistic-gather` writes.
enum Part {
    Fixed(&'static [u8]),
    /// A line made for this answer, in the lines of its batch.
    Line(Range<usize>),
    /// A value the cache holds, itself.
    Value(Arc<[u8]>),
}

/// The cache as `tcp-realistic-gather` keeps it: each value a buffer of its own, and an answer its parts, a line for
/// the value's length, the value's own buffer and a line's end, to be written together with no copy of the value.
#[derive(Default)]
struct GatheringCache {
    values: HashMap<Vec<u8>, Arc<[u8]>>,
    counts: HashMap<Vec<u8>, u64>,
}

impl GatheringCache {
    /// Answers `request`, one whole command, at the end of `parts`; the lines it makes go at the end of `lines`.
    fn answer(&mut self, request: &[u8], lines: &mut Vec<u8>, parts: &mut Vec<Part>) {
        let mut line = |mark: u8, number: u64| {
            let start = lines.len();
            put_line(lines, mark, number);
            Part::Line(start..lines.len())
        };
        match command(request) {
            Command::Set(key, value) => {
                self.values.insert(key.to_vec(), Arc::from(value)); // a copy: the cache's own
                parts.push(Part::Fixed(OK));
            }
            Command::Get(key) => match self.values.get(key) {
                Some(value) => {
                    parts.push(line(b'$', value.len() as u64));
                    parts.push(Part::Value(value.clone()));
                    parts.push(Part::Fixed(CRLF));
                }
                None => parts.push(Part::Fixed(NOTHING)),
            },
            Command::Incr(key) => {
                let count = self.counts.entry(key.to_vec()).or_default();
                *count += 1;
                parts.push(line(b':', *count));
            }
            Command::Unknown => parts.push(Part::Fixed(UNKNOWN)),
        }
    }
}

/// A client of the cache, for the user `id`: it keeps the user's sessions there, reads them back, and counts the
/// user's requests. What it asks follows from the request's number, and it knows what each answer must be.
struct CacheClient {
    id: usize,
    kept: HashMap<Vec<u8>, Vec<u8>>,
    hits: u64,
}

impl CacheClient {
    fn new(id: usize) -> Self {
        CacheClient { id, kept: HashMap::new(), hits: 0 }
    }

    /// A session as a program would keep one: a document of JSON, some 500 bytes of it.
    fn session(&self, i: usize) -> Vec<u8> {
        let id = self.id;
        let mut text = Vec::with_capacity(640);
        write!(
            text,
            "{{\"user\":{id},\"seq\":{i},\"token\":\"{:x}-{:x}\",\"expires\":{},\"cart\":[",
            (id as u32).wrapping_mul(0x9e37_79b1).wrapping_add(i as u32),
            (i as u32).wrapping_mul(0x85eb_ca6b).wrapping_add(id as u32),
            1_795_000_000 + i
        )
        .expect("writing to a Vec");
        for item in 0..8 {
            if item > 0 {
                text.push(b',');
            }
            write!(
                text,
                "{{\"sku\":\"SKU-{}\",\"qty\":{},\"price\":{}}}",
                100_000 + (id * 131 + i * 17 + item * 7919) % 900_000,
                1 + (i + item) % 5,
                199 + (i * 37 + item * 101) % 9800
            )
            .expect("writing to a Vec");
        }
        let theme = if i % 2 == 0 { "dark" } else { "light" };
        write!(text, "],\"locale\":\"en-GB\",\"theme\":\"{theme}\"}}").expect("writing to a Vec");
        text
    }

    /// Writes request `i` at the end of `request`, and what its answer must be into `expected`. One in five keeps a
    /// session, one in five counts a request, and the rest read a session back.
    fn request(&mut self, i: usize, request: &mut Vec<u8>, expected: &mut Vec<u8>) {
        expected.clear();
        let mut key = Vec::with_capacity(24);
        if i % 5 == 3 {
            self.hits += 1;
            put_line(expected, b':', self.hits);
            write!(key, "hits:{}", self.id).expect("writing to a Vec");
            request.extend_from_slice(b"*2\r\n$4\r\nINCR\r\n");
            put_bulk(request, &key);
            return;
        }
        write!(key, "session:{}:{}", self.id, i * 7 % 50).expect("writing to a Vec");
        if i % 5 == 0 {
            let document = self.session(i);
            expected.extend_from_slice(OK);
            request.extend_from_slice(b"*3\r\n$3\r\nSET\r\n");
            put_bulk(request, &key);
            put_bulk(request, &document);
            self.kept.insert(key, document);
        } else {
            match self.kept.get(&key) {
                Some(document) => put_bulk(expected, document),
                None => expected.extend_from_slice(NOTHING),
            }
            request.extend_from_slice(b"*2\r\n$3\r\nGET\r\n");
            put_bulk(request, &key);
        }
    }

    /// Fails unless `answer` is `right`, the answer to request `i`.
    fn check(&self, i: usize, right: &[u8], answer: &[u8]) -> io::Result<()> {
        if right == answer {
            Ok(())
        } else {
            Err(io::Error::other(format!("request {i} of client {} was answered wrongly", self.id)))
        }
    }
}

/// What a connection has delivered, and the RESP values in it, however many reads each comes in.
struct Inbox {
    bytes: Vec<u8>,
    at: usize,
    until: usize,
}

impl Inbox {
    fn new(size: usize) -> Self {
        Inbox { bytes: vec![0; size], at: 0, until: 0 }
    }

    /// Where in `bytes` the next whole value is, if one has all come: it is taken.
    fn next(&mut self) -> Option<Range<usize>> {
        let end = resp_end(&self.bytes[..self.until], self.at)?;
        let value = self.at..end;
        self.at = end;
        Some(value)
    }

    /// Reads once more from `stream`, after what is left of the values not yet taken.
    async fn more(&mut self, stream: &mut TcpStream) -> io::Result<()> {
        self.bytes.copy_within(self.at..self.until, 0);
        self.until -= self.at;
        self.at = 0;
        if self.until == self.bytes.len() {
            self.bytes.resize(2 * self.until, 0);
        }
        match stream.read(&mut self.bytes[self.until..]).await? {
            0 => Err(io::ErrorKind::UnexpectedEof.into()),
            length => {
                self.until += length;
                Ok(())
            }
        }
    }
}

/// How the server of a realistic scenario answers.
#[derive(Clone, Copy)]
enum Serving {
    /// `tcp-realistic`: a write for each command.
    Each,
    /// `tcp-realistic-bulk`: one write for the commands a read brought.
    Together,
    /// `tcp-realistic-gather`: one gathering write for the commands a read brought, the values not copied.
    Gathering,
}

/// Answers `rounds` commands, each in a write of its own.
async fn serve_each(mut stream: TcpStream, rounds: usize) -> io::Result<TcpStream> {
    let mut cache = CacheServer::default();
    let mut inbox = Inbox::new(LARGEST);
    let mut response = Vec::with_capacity(LARGEST);
    let mut served = 0;
    while served < rounds {
        match inbox.next() {
            Some(request) => {
                response.clear();
                cache.handle(&inbox.bytes[request], &mut response);
                stream.write_all(&response).await?;
                served += 1;
            }
            None => inbox.more(&mut stream).await?,
        }
    }
    Ok(stream)
}

/// Answers `rounds` commands: every whole command a read brought in one write, a part of one kept for the next read.
async fn serve_together(mut stream: TcpStream, rounds: usize) -> io::Result<TcpStream> {
    let mut cache = CacheServer::default();
    let mut inbox = Inbox::new(LARGEST_BATCH);
    let mut responses = Vec::with_capacity(LARGEST_BATCH);
    let mut served = 0;
    while served < rounds {
        inbox.more(&mut stream).await?;
        responses.clear();
        while let Some(request) = inbox.next() {
            cache.handle(&inbox.bytes[request], &mut responses);
            served += 1;
        }
        if !responses.is_empty() {
            stream.write_all(&responses).await?;
        }
    }
    Ok(stream)
}

/// As `serve_together`, the answers written as their parts in a gathering write.
async fn serve_gathering(mut stream: TcpStream, rounds: usize) -> io::Result<TcpStream> {
    let mut cache = GatheringCache::default();
    let mut inbox = Inbox::new(LARGEST_BATCH);
    let mut lines = Vec::with_capacity(BATCH * 16);
    let mut parts = Vec::with_capacity(BATCH * 3);
    let mut served = 0;
    while served < rounds {
        inbox.more(&mut stream).await?;
        lines.clear();
        parts.clear();
        while let Some(request) = inbox.next() {
            cache.answer(&inbox.bytes[request], &mut lines, &mut parts);
            served += 1;
        }
        let mut slices: Vec<IoSlice<'_>> = parts
            .iter()
            .map(|part| {
                IoSlice::new(match part {
                    Part::Fixed(bytes) => bytes,
                    Part::Line(line) => &lines[line.clone()],
                    Part::Value(value) => value,
                })
            })
            .collect();
        let mut left = &mut slices[..];
        while !left.is_empty() {
            match stream.write_vectored(left).await? {
                0 => return Err(io::ErrorKind::WriteZero.into()),
                written => IoSlice::advance_slices(&mut left, written),
            }
        }
    }
    Ok(stream)
}

/// Sends the `rounds` requests of the user `id`, `batch` in each write, and checks their answers as they come.
async fn ask(mut stream: TcpStream, id: usize, rounds: usize, batch: usize) -> io::Result<TcpStream> {
    let mut user = CacheClient::new(id);
    let mut inbox = Inbox::new(batch * LARGEST);
    let mut requests = Vec::with_capacity(batch * LARGEST);
    let mut answers = vec![Vec::new(); batch];
    let mut i = 0;
    while i < rounds {
        let sent = batch.min(rounds - i);
        requests.clear();
        for (k, expected) in answers[..sent].iter_mut().enumerate() {
            user.request(i + k, &mut requests, expected);
        }
        stream.write_all(&requests).await?;
        let mut checked = 0;
        while checked < sent {
            match inbox.next() {
                Some(answer) => {
                    user.check(i + checked, &answers[checked], &inbox.bytes[answer])?;
                    checked += 1;
                }
                None => inbox.more(&mut stream).await?,
            }
        }
        i += sent;
    }
    Ok(stream)
}

async fn tcp_realistic(name: &str, connections: usize, rounds: usize, serving: Serving) -> io::Result<()> {
    let (listener, pairs) = connected(connections).await?;
    let mut tasks = Vec::with_capacity(2 * connections);
    let start = Instant::now();
    for (id, (client, server)) in pairs.into_iter().enumerate() {
        // The client first: if an answer is wrong, that is the failure to tell of, not the server's stream ending.
        tasks.push(match serving {
            Serving::Each => tokio::spawn(ask(client, id, rounds, 1)),
            Serving::Together | Serving::Gathering => tokio::spawn(ask(client, id, rounds, BATCH)),
        });
        tasks.push(match serving {
            Serving::Each => tokio::spawn(serve_each(server, rounds)),
            Serving::Together => tokio::spawn(serve_together(server, rounds)),
            Serving::Gathering => tokio::spawn(serve_gathering(server, rounds)),
        });
    }
    let streams = join_streams(tasks).await?;
    report(name, rounds as u64 * connections as u64, start);
    drop(streams);
    drop(listener);
    Ok(())
}

/// The work of `tcp-realistic` with nothing between the two sides: what the scenario costs beside its I/O. In one
/// task, so that a worker does it as it does the others' work.
async fn work_realistic(connections: usize, rounds: usize) -> io::Result<()> {
    let start = Instant::now();
    let work: Task = tokio::spawn(async move {
        let mut request = Vec::with_capacity(LARGEST);
        let mut response = Vec::with_capacity(LARGEST);
        let mut expected = Vec::new();
        for id in 0..connections {
            let mut cache = CacheServer::default();
            let mut user = CacheClient::new(id);
            for i in 0..rounds {
                request.clear();
                response.clear();
                user.request(i, &mut request, &mut expected);
                cache.handle(&request, &mut response);
                user.check(i, &expected, &response)?;
            }
        }
        Ok(())
    });
    join(vec![work]).await?;
    report("work-realistic", rounds as u64 * connections as u64, start);
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
                "tcp-realistic" => tcp_realistic(scenario, args.connections, n(2_000), Serving::Each).await?,
                "tcp-realistic-bulk" => tcp_realistic(scenario, args.connections, n(2_000), Serving::Together).await?,
                "tcp-realistic-gather" => {
                    tcp_realistic(scenario, args.connections, n(2_000), Serving::Gathering).await?
                }
                "work-realistic" => work_realistic(args.connections, n(2_000)).await?,
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
