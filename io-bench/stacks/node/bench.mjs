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
  'udp-pingpong',
  'udp-concurrent',
  'udp-connected',
];

// ---- command line

const options = { workers: 1, scenarios: ALL, scale: 1.0, connections: 64, repeat: 1 };
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
    case 'udp-pingpong': return udpPairs(name, 1, n(100_000));
    case 'udp-concurrent': return udpPairs(name, options.connections, n(5_000));
    case 'udp-connected': return udpConnected(name, n(100_000));
    default: return undefined; // a scenario this stack cannot do prints nothing
  }
}

for (let i = 0; i < options.repeat; i++) {
  for (const name of options.scenarios) await once(name);
}
