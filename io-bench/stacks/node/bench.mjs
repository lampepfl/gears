// The IP socket scenarios of io-bench/stacks/README.md, on Node's `net` and `dgram`.
import net from 'node:net';
import dgram from 'node:dgram';

const HOST = '127.0.0.1';
const MESSAGE = 64;
const CHUNK = 65536;

const ALL = [
  'tcp-pingpong',
  'tcp-concurrent',
  'tcp-stream',
  'tcp-connect',
  'tcp-realistic',
  'tcp-realistic-bulk',
  'tcp-realistic-gather',
  'work-realistic',
  'udp-pingpong',
  'udp-concurrent',
  'udp-connected',
];

// ---- command line

const options = { workers: 1, scenarios: ALL, scale: 1.0, connections: 64, repeat: 1, serve: 0 };
{
  const args = process.argv.slice(2);
  for (let i = 0; i < args.length; i += 2) {
    const value = args[i + 1];
    if (value === undefined) throw new Error(`missing value for ${args[i]}`);
    switch (args[i]) {
      case '--workers': options.workers = parseInt(value, 10); break; // one thread: ignored
      case '--scenarios': options.scenarios = value.split(',').filter((s) => s.length > 0); break;
      case '--scale': options.scale = parseFloat(value); break;
      case '--connections': options.connections = parseInt(value, 10); break;
      case '--repeat': options.repeat = parseInt(value, 10); break;
      case '--serve': options.serve = parseInt(value, 10); break;
      default: throw new Error(`unknown argument ${args[i]}`);
    }
  }
}

const n = (x) => Math.max(1, Math.floor(x * options.scale));

function report(name, ops, nanos) {
  const seconds = Number(nanos) / 1e9;
  const micros = Number(nanos) / 1e3 / ops;
  process.stdout.write(
    `bench: ${name.padEnd(20)} ${String(ops).padStart(9)} ops ` +
      `${seconds.toFixed(3).padStart(8)} s ${micros.toFixed(3).padStart(9)} us/op\n`,
  );
}

const now = () => process.hrtime.bigint();

// ---- helpers

function listen(serverOptions) {
  return new Promise((resolve, reject) => {
    const server = net.createServer(serverOptions);
    server.once('error', reject);
    server.listen(0, HOST, () => resolve(server));
  });
}

/** One connection through `server`, as [client end, server end], both with TCP_NODELAY. */
function connectPair(server, clientOptions) {
  return new Promise((resolve, reject) => {
    let client;
    let accepted;
    let isConnected = false;
    const done = () => {
      if (isConnected && accepted) resolve([client, accepted]);
    };
    server.once('connection', (socket) => {
      socket.setNoDelay(true);
      accepted = socket;
      done();
    });
    client = net.connect({ ...clientOptions, port: server.address().port, host: HOST }, () => {
      isConnected = true;
      done();
    });
    client.setNoDelay(true);
    client.once('error', reject);
  });
}

function closeSocket(socket) {
  return new Promise((resolve) => {
    if (socket.destroyed) return resolve();
    socket.once('close', resolve);
    socket.destroy();
  });
}

const closeServer = (server) => new Promise((resolve) => server.close(resolve));

/** Calls `onMessage` each time `socket` has delivered another whole message into `buffer`. */
function onWholeMessages(socket, buffer, onMessage) {
  let filled = 0;
  socket.on('data', (data) => {
    let offset = 0;
    const length = data.length;
    while (offset < length) {
      const take = Math.min(MESSAGE - filled, length - offset);
      data.copy(buffer, filled, offset, offset + take);
      filled += take;
      offset += take;
      if (filled === MESSAGE) {
        filled = 0;
        onMessage();
      }
    }
  });
}

/** Starts `rounds` rounds of ping-pong over a connection; `done` is called after the client's last read. */
function echoPair(client, server, rounds, done) {
  const serverBuffer = Buffer.alloc(MESSAGE);
  const clientIn = Buffer.alloc(MESSAGE);
  const clientOut = Buffer.alloc(MESSAGE, 1);
  onWholeMessages(server, serverBuffer, () => {
    server.write(serverBuffer);
  });
  let remaining = rounds;
  onWholeMessages(client, clientIn, () => {
    if (--remaining === 0) done();
    else client.write(clientOut);
  });
  client.write(clientOut);
}

// ---- TCP

async function tcpEcho(name, connections, rounds) {
  const server = await listen();
  const pairs = [];
  for (let i = 0; i < connections; i++) pairs.push(await connectPair(server));

  const start = now();
  await new Promise((resolve, reject) => {
    let running = connections;
    for (const [client, accepted] of pairs) {
      client.on('error', reject);
      accepted.on('error', reject);
      echoPair(client, accepted, rounds, () => {
        if (--running === 0) resolve();
      });
    }
  });
  const end = now();
  report(name, rounds * connections, end - start);

  await Promise.all(pairs.flatMap(([client, accepted]) => [closeSocket(client), closeSocket(accepted)]));
  await closeServer(server);
}

async function tcpStream(name, chunks) {
  const server = await listen({ allowHalfOpen: true });
  const [client, accepted] = await connectPair(server);
  const chunk = Buffer.alloc(CHUNK, 1);

  const start = now();
  let received = 0;
  await new Promise((resolve, reject) => {
    let pending = 2;
    const finished = () => {
      if (--pending === 0) resolve();
    };
    client.on('error', reject);
    accepted.on('error', reject);

    accepted.on('data', (data) => {
      received += data.length;
    });
    accepted.on('end', finished);

    let written = 0;
    const write = () => {
      while (written < chunks) {
        written++;
        if (!client.write(chunk)) {
          client.once('drain', write);
          return;
        }
      }
      client.end(finished); // 'finish': everything written and the output shut down
    };
    write();
  });
  const end = now();
  if (received !== chunks * CHUNK) throw new Error(`${name}: received ${received} bytes of ${chunks * CHUNK}`);
  report(name, chunks, end - start);

  await Promise.all([closeSocket(client), closeSocket(accepted)]);
  await closeServer(server);
}

async function tcpConnect(name, count) {
  const server = await listen();
  // The server accepts and closes at once.
  server.on('connection', (socket) => {
    socket.setNoDelay(true);
    socket.on('error', () => {});
    socket.end();
  });
  const port = server.address().port;

  const start = now();
  await new Promise((resolve, reject) => {
    let remaining = count;
    const next = () => {
      const client = net.connect({ port, host: HOST });
      client.setNoDelay(true);
      client.on('error', reject);
      client.on('data', () => {}); // reading, so that the end of the stream is seen
      client.on('end', () => {
        client.destroy();
        if (--remaining === 0) resolve();
        else next();
      });
    };
    next();
  });
  const end = now();
  report(name, count, end - start);

  await closeServer(server); // once every accepted socket has closed
}

// ---- a session cache that speaks Redis's protocol, and a client of it (see ../README.md)

const LARGEST = 2048;
const BATCH = 16; // how many requests go at once in `tcp-realistic-bulk`
const LARGEST_BATCH = BATCH * LARGEST;
const CR = 13, LF = 10, DOLLAR = 36, STAR = 42, COLON = 58, MINUS = 45, ZERO = 48;

/** RESP, the protocol of Redis: values written into buffers, and found in them. */
const latin1 = (text) => Buffer.from(text, 'latin1');

/** Writes `bytes` at `at`: where it ends. */
function put(into, at, bytes) {
  bytes.copy(into, at);
  return at + bytes.length;
}

/** Writes `number` in decimal and the end of a line. */
function putLine(into, at, number) {
  const end = at + into.latin1Write(String(number), at);
  into[end] = CR;
  into[end + 1] = LF;
  return end + 2;
}

/** Writes a bulk string: `$`, its length, a line's end, its bytes, a line's end. */
function putBulk(into, at, from, start, length) {
  into[at] = DOLLAR;
  const body = putLine(into, at + 1, length);
  from.copy(into, body, start, start + length);
  into[body + length] = CR;
  into[body + length + 1] = LF;
  return body + length + 2;
}

/** Where the line that begins at `at` ends, past its `\r\n`; -1 if it has not all come. */
function lineEnd(buffer, at, until) {
  for (let i = at; i + 1 < until; i++) if (buffer[i] === CR && buffer[i + 1] === LF) return i + 2;
  return -1;
}

/** The decimal number from `at` to the end of its line. */
function number(buffer, at) {
  let i = at;
  const negative = buffer[i] === MINUS;
  if (negative) i++;
  let value = 0;
  while (buffer[i] !== CR) value = value * 10 + (buffer[i++] - ZERO);
  return negative ? -value : value;
}

/** Where the value that begins at `at` ends; -1 if it has not all come. */
function respEnd(buffer, at, until) {
  const line = lineEnd(buffer, at, until);
  if (line < 0) return -1;
  switch (buffer[at]) {
    case DOLLAR: {
      const length = number(buffer, at + 1);
      if (length < 0) return line;
      return line + length + 2 <= until ? line + length + 2 : -1;
    }
    case STAR: {
      let next = line;
      for (let left = number(buffer, at + 1); left > 0 && next >= 0; left--) next = respEnd(buffer, next, until);
      return next;
    }
    default: return line; // a status, an error or an integer: its line
  }
}

const OK = latin1('+OK\r\n');
const NOTHING = latin1('$-1\r\n');
const UNKNOWN = latin1('-ERR unknown command\r\n');
const SET = latin1('*3\r\n$3\r\nSET\r\n');
const GET = latin1('*2\r\n$3\r\nGET\r\n');
const INCR = latin1('*2\r\n$4\r\nINCR\r\n');
const S = 83, G = 71, I = 73;

/** The cache: what it keeps for one connection, and how it answers `SET`, `GET` and `INCR`. */
class CacheServer {
  constructor(values = new Map()) { this.values = values; }

  /** Answers the command that begins at `from` in `request`, an array of bulk strings, into `response` at `out`:
   * where the answer ends. */
  handle(request, from, response, out) {
    const until = request.length;
    const parts = number(request, from + 1);
    let at = lineEnd(request, from, until);
    const nameLength = number(request, at + 1);
    const name = lineEnd(request, at, until);
    at = name + nameLength + 2;
    const keyLength = number(request, at + 1);
    const keyAt = lineEnd(request, at, until);
    const key = request.latin1Slice(keyAt, keyAt + keyLength);
    at = keyAt + keyLength + 2;
    if (nameLength === 3 && request[name] === S && parts === 3) {
      const length = number(request, at + 1);
      const valueAt = lineEnd(request, at, until);
      this.values.set(key, Buffer.from(request.subarray(valueAt, valueAt + length))); // a copy: the cache's own
      return put(response, out, OK);
    }
    if (nameLength === 3 && request[name] === G) {
      const value = this.values.get(key);
      return value === undefined ? put(response, out, NOTHING) : putBulk(response, out, value, 0, value.length);
    }
    if (nameLength === 4 && request[name] === I) {
      const held = this.values.get(key);
      const count = (held === undefined ? 0 : number(held, 0)) + 1;
      this.values.set(key, latin1(count + '\r')); // kept as Redis keeps a count, its digits
      response[out] = COLON;
      return putLine(response, out + 1, count);
    }
    return put(response, out, UNKNOWN);
  }
}

const CRLF = latin1('\r\n');

/** The cache as `tcp-realistic-gather` keeps it: an answer is written as its parts, a line for its length, the value's
 * own buffer and a line's end, for Node to send together with no copy of the value. */
class GatheringCache {
  values = new Map();
  counts = new Map();

  /** Answers the command that begins at `from` in `request`, writing the answer's parts to `socket`. */
  answer(request, from, socket) {
    const until = request.length;
    const parts = number(request, from + 1);
    let at = lineEnd(request, from, until);
    const nameLength = number(request, at + 1);
    const name = lineEnd(request, at, until);
    at = name + nameLength + 2;
    const keyLength = number(request, at + 1);
    const keyAt = lineEnd(request, at, until);
    const key = request.latin1Slice(keyAt, keyAt + keyLength);
    at = keyAt + keyLength + 2;
    if (nameLength === 3 && request[name] === S && parts === 3) {
      const length = number(request, at + 1);
      const valueAt = lineEnd(request, at, until);
      this.values.set(key, Buffer.from(request.subarray(valueAt, valueAt + length))); // a copy: the cache's own
      socket.write(OK);
    } else if (nameLength === 3 && request[name] === G) {
      const value = this.values.get(key);
      if (value === undefined) socket.write(NOTHING);
      else {
        socket.write(latin1(`$${value.length}\r\n`));
        socket.write(value);
        socket.write(CRLF);
      }
    } else if (nameLength === 4 && request[name] === I) {
      const count = (this.counts.get(key) ?? 0) + 1;
      this.counts.set(key, count);
      socket.write(latin1(`:${count}\r\n`));
    } else socket.write(UNKNOWN);
  }
}

/** A client of the cache, for the user `id`: it keeps the user's sessions there, reads them back, and counts the
 * user's requests. What it asks follows from the request's number, and it knows what each answer must be. */
class CacheClient {
  kept = new Map();
  hits = 0;
  expected = null;
  constructor(id) { this.id = id; }

  /** A session as a program would keep one: a document of JSON, some 500 bytes of it. */
  session(i) {
    const id = this.id;
    const hex = (x) => (x >>> 0).toString(16);
    let text = `{"user":${id},"seq":${i},"token":"${hex(Math.imul(id, 0x9e3779b1) + i | 0)}-` +
      `${hex(Math.imul(i, 0x85ebca6b) + id | 0)}","expires":${1795000000 + i},"cart":[`;
    for (let item = 0; item < 8; item++) {
      if (item > 0) text += ',';
      text += `{"sku":"SKU-${100000 + (id * 131 + i * 17 + item * 7919) % 900000}","qty":${1 + (i + item) % 5},` +
        `"price":${199 + (i * 37 + item * 101) % 9800}}`;
    }
    text += `],"locale":"en-GB","theme":"${i % 2 === 0 ? 'dark' : 'light'}"}`;
    return latin1(text);
  }

  /** Writes request `i` into `request` at `out`: where it ends. One in five keeps a session, one in five counts a
   * request, and the rest read a session back. What its answer must be is `expected`. */
  request(i, request, out) {
    if (i % 5 === 3) {
      this.hits++;
      this.expected = latin1(`:${this.hits}\r\n`);
      const key = latin1(`hits:${this.id}`);
      return putBulk(request, put(request, out, INCR), key, 0, key.length);
    }
    const name = `session:${this.id}:${i * 7 % 50}`;
    const key = latin1(name);
    if (i % 5 === 0) {
      const document = this.session(i);
      this.kept.set(name, document);
      this.expected = OK;
      const value = putBulk(request, put(request, out, SET), key, 0, key.length);
      return putBulk(request, value, document, 0, document.length);
    }
    const document = this.kept.get(name);
    if (document === undefined) this.expected = NOTHING;
    else {
      const whole = Buffer.allocUnsafe(document.length + 16);
      this.expected = whole.subarray(0, putBulk(whole, 0, document, 0, document.length));
    }
    return putBulk(request, put(request, out, GET), key, 0, key.length);
  }

  /** Fails unless the bytes of `response` from `at` to `end` are `right`, the answer to request `i`. */
  check(i, right, response, at, end) {
    if (right.length !== end - at || right.compare(response, at, end) !== 0)
      throw new Error(`request ${i} of client ${this.id} was answered wrongly`);
  }
}

/** Calls `onValue(buffer, at, end)` for each whole RESP value `socket` delivers, however many reads it comes in. */
function onValues(socket, onValue) {
  let pending = null;
  socket.on('data', (data) => {
    const buffer = pending === null ? data : Buffer.concat([pending, data]);
    let at = 0;
    for (;;) {
      const end = respEnd(buffer, at, buffer.length);
      if (end < 0) break;
      onValue(buffer, at, end);
      at = end;
    }
    pending = at === buffer.length ? null : buffer.subarray(at);
  });
}

/** Writes `length` bytes of `value` to `socket`, in a buffer Node keeps. */
function sendValue(socket, value, length) {
  socket.write(Buffer.from(value.subarray(0, length)));
}

async function tcpRealistic(name, connections, rounds) {
  const server = await listen();
  const pairs = [];
  for (let i = 0; i < connections; i++) pairs.push(await connectPair(server));

  const start = now();
  await new Promise((resolve, reject) => {
    let running = connections;
    pairs.forEach(([client, accepted], id) => {
      client.on('error', reject);
      accepted.on('error', reject);
      const cache = new CacheServer();
      const response = Buffer.alloc(LARGEST);
      onValues(accepted, (buffer, at) => {
        sendValue(accepted, response, cache.handle(buffer, at, response, 0));
      });
      const user = new CacheClient(id);
      const request = Buffer.alloc(LARGEST);
      let i = 0;
      onValues(client, (buffer, at, end) => {
        try {
          user.check(i, user.expected, buffer, at, end);
        } catch (failure) {
          return reject(failure);
        }
        if (++i === rounds) {
          if (--running === 0) resolve();
        } else sendValue(client, request, user.request(i, request, 0));
      });
      sendValue(client, request, user.request(0, request, 0));
    });
  });
  const end = now();
  report(name, rounds * connections, end - start);

  await Promise.all(pairs.flatMap(([client, accepted]) => [closeSocket(client), closeSocket(accepted)]));
  await closeServer(server);
}

/** Calls `onValue(buffer, at, end)` for each whole RESP value in what `socket` delivers at once, and then `after()`. */
function onValuesTogether(socket, onValue, after) {
  let pending = null;
  socket.on('data', (data) => {
    const buffer = pending === null ? data : Buffer.concat([pending, data]);
    let at = 0;
    for (;;) {
      const end = respEnd(buffer, at, buffer.length);
      if (end < 0) break;
      onValue(buffer, at, end);
      at = end;
    }
    pending = at === buffer.length ? null : buffer.subarray(at);
    after();
  });
}

/** As `tcpRealistic`, the requests sent `BATCH` at once, as a client that pipelines sends them: the server answers
 * every request that came together in one write, and the client checks every answer that came together. */
async function tcpRealisticBulk(name, connections, rounds, gathering) {
  const server = await listen();
  const pairs = [];
  for (let i = 0; i < connections; i++) pairs.push(await connectPair(server));

  const start = now();
  await new Promise((resolve, reject) => {
    let running = connections;
    pairs.forEach(([client, accepted], id) => {
      client.on('error', reject);
      accepted.on('error', reject);
      if (gathering) {
        // What is written between `cork` and `uncork` Node hands to the system together, as a gathering write.
        const cache = new GatheringCache();
        let corked = false;
        onValuesTogether(
          accepted,
          (buffer, at) => {
            if (!corked) {
              corked = true;
              accepted.cork();
            }
            cache.answer(buffer, at, accepted);
          },
          () => {
            if (corked) {
              corked = false;
              accepted.uncork();
            }
          },
        );
      } else {
        const cache = new CacheServer();
        const responses = Buffer.alloc(LARGEST_BATCH);
        let written = 0;
        onValuesTogether(
          accepted,
          (buffer, at) => { written = cache.handle(buffer, at, responses, written); },
          () => {
            if (written > 0) sendValue(accepted, responses, written);
            written = 0;
          },
        );
      }
      const user = new CacheClient(id);
      const requests = Buffer.alloc(LARGEST_BATCH);
      const answers = new Array(BATCH);
      let i = 0, batch = 0, checked = 0;
      const sendBatch = () => {
        batch = Math.min(BATCH, rounds - i);
        checked = 0;
        let length = 0;
        for (let k = 0; k < batch; k++) {
          length = user.request(i + k, requests, length);
          answers[k] = user.expected;
        }
        sendValue(client, requests, length);
      };
      let failed = false;
      onValuesTogether(
        client,
        (buffer, at, end) => {
          if (failed) return;
          try {
            user.check(i + checked, answers[checked], buffer, at, end);
          } catch (failure) {
            failed = true;
            return reject(failure);
          }
          checked++;
        },
        () => {
          if (failed || checked < batch) return;
          i += batch;
          if (i === rounds) {
            if (--running === 0) resolve();
          } else sendBatch();
        },
      );
      sendBatch();
    });
  });
  const end = now();
  report(name, rounds * connections, end - start);

  await Promise.all(pairs.flatMap(([client, accepted]) => [closeSocket(client), closeSocket(accepted)]));
  await closeServer(server);
}

/** The work of `tcp-realistic` with nothing between the two sides: what the scenario costs beside its I/O. */
function workRealistic(name, connections, rounds) {
  const request = Buffer.alloc(LARGEST);
  const response = Buffer.alloc(LARGEST);
  const start = now();
  for (let id = 0; id < connections; id++) {
    const cache = new CacheServer();
    const user = new CacheClient(id);
    for (let i = 0; i < rounds; i++) {
      user.request(i, request, 0);
      user.check(i, user.expected, response, 0, cache.handle(request, 0, response, 0));
    }
  }
  const end = now();
  report(name, rounds * connections, end - start);
}

// ---- UDP

function bindUdp() {
  return new Promise((resolve, reject) => {
    const socket = dgram.createSocket('udp4');
    socket.once('error', reject);
    socket.bind(0, HOST, () => resolve(socket));
  });
}

const connectUdp = (socket, port) => new Promise((resolve) => socket.connect(port, HOST, resolve));

const closeUdp = (socket) => new Promise((resolve) => socket.close(resolve));

/** Starts `rounds` rounds over a pair of unconnected sockets; `done` is called after the client's last receive. */
function udpEcho(client, server, rounds, done) {
  const serverPort = server.address().port;
  const clientPort = client.address().port;
  const out = Buffer.alloc(MESSAGE, 1);
  server.on('message', (message) => {
    server.send(message, clientPort, HOST);
  });
  let remaining = rounds;
  client.on('message', () => {
    if (--remaining === 0) done();
    else client.send(out, serverPort, HOST);
  });
  client.send(out, serverPort, HOST);
}

async function udpPairs(name, pairCount, rounds) {
  const pairs = [];
  for (let i = 0; i < pairCount; i++) pairs.push([await bindUdp(), await bindUdp()]);

  const start = now();
  await new Promise((resolve, reject) => {
    let running = pairCount;
    for (const [client, server] of pairs) {
      client.on('error', reject);
      server.on('error', reject);
      udpEcho(client, server, rounds, () => {
        if (--running === 0) resolve();
      });
    }
  });
  const end = now();
  report(name, rounds * pairCount, end - start);

  await Promise.all(pairs.flatMap(([client, server]) => [closeUdp(client), closeUdp(server)]));
}

async function udpConnected(name, rounds) {
  const client = await bindUdp();
  const server = await bindUdp();
  await connectUdp(client, server.address().port);
  await connectUdp(server, client.address().port);
  const out = Buffer.alloc(MESSAGE, 1);

  const start = now();
  await new Promise((resolve, reject) => {
    client.on('error', reject);
    server.on('error', reject);
    server.on('message', (message) => {
      server.send(message);
    });
    let remaining = rounds;
    client.on('message', () => {
      if (--remaining === 0) resolve();
      else client.send(out);
    });
    client.send(out);
  });
  const end = now();
  report(name, rounds, end - start);

  await Promise.all([closeUdp(client), closeUdp(server)]);
}

// ---- main

async function once(name) {
  switch (name) {
    case 'tcp-pingpong': return tcpEcho(name, 1, n(100_000));
    case 'tcp-concurrent': return tcpEcho(name, options.connections, n(5_000));
    case 'tcp-stream': return tcpStream(name, n(20_000));
    case 'tcp-connect': return tcpConnect(name, n(1_000));
    case 'tcp-realistic': return tcpRealistic(name, options.connections, n(2_000));
    case 'tcp-realistic-bulk': return tcpRealisticBulk(name, options.connections, n(2_000), false);
    case 'tcp-realistic-gather': return tcpRealisticBulk(name, options.connections, n(2_000), true);
    case 'work-realistic': return workRealistic(name, options.connections, n(2_000));
    case 'udp-pingpong': return udpPairs(name, 1, n(100_000));
    case 'udp-concurrent': return udpPairs(name, options.connections, n(5_000));
    case 'udp-connected': return udpConnected(name, n(100_000));
    default: return undefined; // a scenario this stack cannot do prints nothing
  }
}

/** The cache as a server of its own, for a client that is not this program's, `redis-benchmark` say: it listens on
 * `port` and serves every connection that comes, answering every request that came together in one write. Its table
 * is one for every connection, as Redis's is. */
function serveCache(port) {
  const table = new Map();
  const server = net.createServer((socket) => {
    socket.setNoDelay(true);
    socket.on('error', () => {});
    const cache = new CacheServer(table);
    let responses = Buffer.alloc(LARGEST_BATCH);
    let written = 0;
    onValuesTogether(
      socket,
      (buffer, at) => {
        if (written + LARGEST > responses.length) { // room for one more answer, however many came together
          const larger = Buffer.alloc(responses.length * 2);
          responses.copy(larger, 0, 0, written);
          responses = larger;
        }
        written = cache.handle(buffer, at, responses, written);
      },
      () => {
        if (written > 0) sendValue(socket, responses, written);
        written = 0;
      },
    );
  });
  server.listen(port, HOST, () => console.log(`serving on ${HOST}:${port}`));
}

if (options.serve > 0) {
  serveCache(options.serve);
  await new Promise(() => {}); // until the process is stopped
}

for (let i = 0; i < options.repeat; i++) {
  for (const name of options.scenarios) await once(name);
}
