// The IP socket scenarios of io-bench/stacks/README.md, as goroutines over package net.
package main

import (
	"bytes"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	messageSize = 64
	chunkSize   = 64 * 1024
)

var loopback = &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)}

func check(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "bench:", err)
		os.Exit(1)
	}
}

// timed runs body, which starts the scenario's goroutines on the group it is given, and waits for them all.
func timed(name string, ops int64, body func(wg *sync.WaitGroup)) {
	var wg sync.WaitGroup
	start := time.Now()
	body(&wg)
	wg.Wait()
	elapsed := time.Since(start)
	fmt.Printf("bench: %-20s %9d ops %8.3f s %9.3f us/op\n",
		name, ops, elapsed.Seconds(), float64(elapsed.Nanoseconds())/1e3/float64(ops))
}

// ---- TCP

type pair struct{ client, server *net.TCPConn }

func listen() *net.TCPListener {
	listener, err := net.ListenTCP("tcp4", loopback)
	check(err)
	return listener
}

// connected makes count connections through one listener, one after another, with TCP_NODELAY set on both ends.
func connected(count int) (*net.TCPListener, []pair) {
	listener := listen()
	address := listener.Addr().(*net.TCPAddr)
	pairs := make([]pair, count)
	for i := range pairs {
		dialed := make(chan *net.TCPConn, 1)
		go func() {
			client, err := net.DialTCP("tcp4", nil, address)
			check(err)
			dialed <- client
		}()
		server, err := listener.AcceptTCP()
		check(err)
		client := <-dialed
		check(client.SetNoDelay(true))
		check(server.SetNoDelay(true))
		pairs[i] = pair{client, server}
	}
	return listener, pairs
}

func closePairs(listener *net.TCPListener, pairs []pair) {
	for _, p := range pairs {
		check(p.client.Close())
		check(p.server.Close())
	}
	check(listener.Close())
}

// echo reads a whole message and writes it back, rounds times over.
func echo(conn *net.TCPConn, rounds int) {
	buf := make([]byte, messageSize)
	for range rounds {
		_, err := io.ReadFull(conn, buf)
		check(err)
		_, err = conn.Write(buf) // Write returns only once all is written, or with an error
		check(err)
	}
}

// ask writes a message and reads a whole one back, rounds times over.
func ask(conn *net.TCPConn, rounds int) {
	out := make([]byte, messageSize)
	in := make([]byte, messageSize)
	for range rounds {
		_, err := conn.Write(out)
		check(err)
		_, err = io.ReadFull(conn, in)
		check(err)
	}
}

func tcpEcho(name string, connections, rounds int) {
	listener, pairs := connected(connections)
	timed(name, int64(rounds)*int64(connections), func(wg *sync.WaitGroup) {
		for _, p := range pairs {
			wg.Go(func() { echo(p.server, rounds) })
			wg.Go(func() { ask(p.client, rounds) })
		}
	})
	closePairs(listener, pairs)
}

func tcpStream(chunks int) {
	listener, pairs := connected(1)
	client, server := pairs[0].client, pairs[0].server
	timed("tcp-stream", int64(chunks), func(wg *sync.WaitGroup) {
		wg.Go(func() {
			buf := make([]byte, chunkSize)
			for range chunks {
				_, err := client.Write(buf)
				check(err)
			}
			check(client.CloseWrite())
		})
		wg.Go(func() {
			buf := make([]byte, chunkSize)
			for {
				_, err := server.Read(buf)
				if err == io.EOF {
					return
				}
				check(err)
			}
		})
	})
	closePairs(listener, pairs)
}

func tcpConnect(count int) {
	listener := listen()
	address := listener.Addr().(*net.TCPAddr)
	timed("tcp-connect", int64(count), func(wg *sync.WaitGroup) {
		wg.Go(func() {
			for range count {
				conn, err := listener.AcceptTCP()
				check(err)
				check(conn.Close())
			}
		})
		wg.Go(func() {
			buf := make([]byte, 8)
			for range count {
				// TCP_NODELAY is set by net on every connection it dials or accepts. It is not set again here: the
				// server may have closed already, and setting an option on such a socket fails on some systems.
				conn, err := net.DialTCP("tcp4", nil, address)
				check(err)
				// Until the server has closed its end, so that the next connect finds the last accepted.
				for {
					_, err := conn.Read(buf)
					if err == io.EOF {
						break
					}
					check(err)
				}
				check(conn.Close())
			}
		})
	})
	check(listener.Close())
}

// ---- a session cache that speaks Redis's protocol, and a client of it (see ../README.md)

const (
	largest      = 2048 // more than any one request or answer
	batch        = 16   // how many requests go at once in tcp-realistic-bulk
	largestBatch = batch * largest
)

var (
	answerOK      = []byte("+OK\r\n")
	answerNothing = []byte("$-1\r\n")
	answerUnknown = []byte("-ERR unknown command\r\n")
	commandSet    = []byte("*3\r\n$3\r\nSET\r\n")
	commandGet    = []byte("*2\r\n$3\r\nGET\r\n")
	commandIncr   = []byte("*2\r\n$4\r\nINCR\r\n")
	crlf          = []byte("\r\n")
)

// RESP, the protocol of Redis: values appended to slices, and found in them.

// appendLine appends number in decimal and the end of a line.
func appendLine(out []byte, number int) []byte {
	return append(strconv.AppendInt(out, int64(number), 10), '\r', '\n')
}

// appendBulk appends a bulk string: '$', its length, a line's end, its bytes, a line's end.
func appendBulk(out, value []byte) []byte {
	out = appendLine(append(out, '$'), len(value))
	return append(append(out, value...), '\r', '\n')
}

// lineEnd is where the line that begins at at ends, past its "\r\n"; -1 if it has not all come.
func lineEnd(b []byte, at int) int {
	for i := at; i+1 < len(b); i++ {
		if b[i] == '\r' && b[i+1] == '\n' {
			return i + 2
		}
	}
	return -1
}

// number is the decimal number from at to the end of its line, or of b.
func number(b []byte, at int) int {
	negative := at < len(b) && b[at] == '-'
	if negative {
		at++
	}
	value := 0
	for ; at < len(b) && b[at] != '\r'; at++ {
		value = value*10 + int(b[at]-'0')
	}
	if negative {
		return -value
	}
	return value
}

// respEnd is where the value that begins at at ends; -1 if it has not all come.
func respEnd(b []byte, at int) int {
	line := lineEnd(b, at)
	if line < 0 {
		return -1
	}
	switch b[at] {
	case '$':
		length := number(b, at+1)
		if length < 0 {
			return line
		}
		if line+length+2 <= len(b) {
			return line + length + 2
		}
		return -1
	case '*':
		next := line
		for left := number(b, at+1); left > 0 && next >= 0; left-- {
			next = respEnd(b, next)
		}
		return next
	default: // a status, an error or an integer: its line
		return line
	}
}

// bulk is the bulk string that begins at at in a value that has all come, and where what follows it begins.
func bulk(b []byte, at int) (value []byte, next int) {
	length := number(b, at+1)
	start := lineEnd(b, at)
	return b[start : start+length], start + length + 2
}

// command takes apart a command, an array of bulk strings: how many they are, its name, its key, and where its value,
// if it has one, begins.
func command(request []byte) (parts int, name, key []byte, valueAt int) {
	parts = number(request, 1)
	name, at := bulk(request, lineEnd(request, 0))
	key, valueAt = bulk(request, at)
	return
}

// respConn finds the RESP values in what a connection delivers, however many reads they come in.
type respConn struct {
	conn       *net.TCPConn
	buf        []byte
	from, have int // what has come and is yet to be taken is buf[from:have]
}

func newRespConn(conn *net.TCPConn, size int) *respConn {
	return &respConn{conn: conn, buf: make([]byte, size)}
}

// next is the next whole value of what has come, or nil if there is none. It is good until more is called.
func (r *respConn) next() []byte {
	end := respEnd(r.buf[:r.have], r.from)
	if end < 0 {
		return nil
	}
	value := r.buf[r.from:end]
	r.from = end
	return value
}

// more reads once, keeping the part of a value that came before, at the front.
func (r *respConn) more() {
	if r.from > 0 {
		r.have = copy(r.buf, r.buf[r.from:r.have])
		r.from = 0
	}
	if r.have == len(r.buf) {
		r.buf = append(r.buf, make([]byte, len(r.buf))...)
	}
	n, err := r.conn.Read(r.buf[r.have:])
	check(err) // the end of the stream too: it would be within a value
	r.have += n
}

// value is the next whole value, read for as often as it takes.
func (r *respConn) value() []byte {
	for {
		if value := r.next(); value != nil {
			return value
		}
		r.more()
	}
}

// cacheServer is the cache: what it keeps for one connection, and how it answers SET, GET and INCR.
type cacheServer struct{ values map[string][]byte }

// handle answers request, one whole command, appending the answer to response.
func (c *cacheServer) handle(request, response []byte) []byte {
	parts, name, key, valueAt := command(request)
	switch {
	case len(name) == 3 && name[0] == 'S' && parts == 3:
		value, _ := bulk(request, valueAt)
		c.values[string(key)] = bytes.Clone(value) // a copy: the cache's own
		return append(response, answerOK...)
	case len(name) == 3 && name[0] == 'G':
		value, held := c.values[string(key)]
		if !held {
			return append(response, answerNothing...)
		}
		return appendBulk(response, value)
	case len(name) == 4 && name[0] == 'I':
		count := number(c.values[string(key)], 0) + 1
		c.values[string(key)] = strconv.AppendInt(nil, int64(count), 10) // kept as Redis keeps a count, its digits
		return appendLine(append(response, ':'), count)
	default:
		return append(response, answerUnknown...)
	}
}

// gatheringCache is the cache as tcp-realistic-gather keeps it: an answer is its parts, a line for its length, the
// value's own slice and a line's end, for a gathering write to send with no copy of the value.
type gatheringCache struct {
	values map[string][]byte
	counts map[string]int
}

// answer answers request, one whole command, appending the answer's parts to parts. The lines it has to make are
// appended to heads, which is returned as it now is.
func (c *gatheringCache) answer(request []byte, parts net.Buffers, heads []byte) (net.Buffers, []byte) {
	count, name, key, valueAt := command(request)
	start := len(heads)
	switch {
	case len(name) == 3 && name[0] == 'S' && count == 3:
		value, _ := bulk(request, valueAt)
		c.values[string(key)] = bytes.Clone(value) // a copy: the cache's own
		return append(parts, answerOK), heads
	case len(name) == 3 && name[0] == 'G':
		value, held := c.values[string(key)]
		if !held {
			return append(parts, answerNothing), heads
		}
		heads = appendLine(append(heads, '$'), len(value))
		return append(parts, heads[start:], value, crlf), heads
	case len(name) == 4 && name[0] == 'I':
		hits := c.counts[string(key)] + 1
		c.counts[string(key)] = hits
		heads = appendLine(append(heads, ':'), hits)
		return append(parts, heads[start:]), heads
	default:
		return append(parts, answerUnknown), heads
	}
}

// cacheClient is a client of the cache, for the user id: it keeps the user's sessions there, reads them back, and
// counts the user's requests. What it asks follows from the request's number, and it knows what each answer must be.
type cacheClient struct {
	id       int
	kept     map[string][]byte
	hits     int
	expected []byte // what the answer to the last request made must be
}

func newCacheClient(id int) *cacheClient {
	return &cacheClient{id: id, kept: make(map[string][]byte)}
}

// session is a session as a program would keep one: a document of JSON, some 500 bytes of it.
func (c *cacheClient) session(i int) []byte {
	id := c.id
	text := make([]byte, 0, 640)
	text = strconv.AppendInt(append(text, `{"user":`...), int64(id), 10)
	text = strconv.AppendInt(append(text, `,"seq":`...), int64(i), 10)
	text = strconv.AppendUint(append(text, `,"token":"`...), uint64(uint32(id)*0x9e3779b1+uint32(i)), 16)
	text = strconv.AppendUint(append(text, '-'), uint64(uint32(i)*0x85ebca6b+uint32(id)), 16)
	text = strconv.AppendInt(append(text, `","expires":`...), int64(1795000000+i), 10)
	text = append(text, `,"cart":[`...)
	for item := range 8 {
		if item > 0 {
			text = append(text, ',')
		}
		text = strconv.AppendInt(append(text, `{"sku":"SKU-`...), int64(100000+(id*131+i*17+item*7919)%900000), 10)
		text = strconv.AppendInt(append(text, `","qty":`...), int64(1+(i+item)%5), 10)
		text = strconv.AppendInt(append(text, `,"price":`...), int64(199+(i*37+item*101)%9800), 10)
		text = append(text, '}')
	}
	text = append(text, `],"locale":"en-GB","theme":"`...)
	if i%2 == 0 {
		text = append(text, "dark"...)
	} else {
		text = append(text, "light"...)
	}
	return append(text, `"}`...)
}

// request appends request i to out. One in five keeps a session, one in five counts a request, and the rest read a
// session back. What its answer must be is expected.
func (c *cacheClient) request(i int, out []byte) []byte {
	if i%5 == 3 {
		c.hits++
		c.expected = appendLine([]byte{':'}, c.hits)
		key := strconv.AppendInt([]byte("hits:"), int64(c.id), 10)
		return appendBulk(append(out, commandIncr...), key)
	}
	name := "session:" + strconv.Itoa(c.id) + ":" + strconv.Itoa(i*7%50)
	if i%5 == 0 {
		document := c.session(i)
		c.kept[name] = document
		c.expected = answerOK
		return appendBulk(appendBulk(append(out, commandSet...), []byte(name)), document)
	}
	if document, held := c.kept[name]; held {
		c.expected = appendBulk(make([]byte, 0, len(document)+16), document)
	} else {
		c.expected = answerNothing
	}
	return appendBulk(append(out, commandGet...), []byte(name))
}

// verify fails the run unless answer is right, the answer to request i.
func (c *cacheClient) verify(i int, right, answer []byte) {
	if !bytes.Equal(right, answer) {
		check(fmt.Errorf("request %d of client %d was answered wrongly", i, c.id))
	}
}

func tcpRealistic(name string, connections, rounds int) {
	listener, pairs := connected(connections)
	timed(name, int64(rounds)*int64(connections), func(wg *sync.WaitGroup) {
		for id, p := range pairs {
			wg.Go(func() {
				in := newRespConn(p.server, largest)
				cache := &cacheServer{values: make(map[string][]byte)}
				response := make([]byte, 0, largest)
				for range rounds {
					response = cache.handle(in.value(), response[:0])
					_, err := p.server.Write(response)
					check(err)
				}
			})
			wg.Go(func() {
				in := newRespConn(p.client, largest)
				user := newCacheClient(id)
				request := make([]byte, 0, largest)
				for i := range rounds {
					request = user.request(i, request[:0])
					_, err := p.client.Write(request)
					check(err)
					user.verify(i, user.expected, in.value())
				}
			})
		}
	})
	closePairs(listener, pairs)
}

// serveBulk answers every command a read brought, in one write.
func serveBulk(conn *net.TCPConn, rounds int) {
	in := newRespConn(conn, largestBatch)
	cache := &cacheServer{values: make(map[string][]byte)}
	responses := make([]byte, 0, largestBatch)
	for answered := 0; answered < rounds; {
		in.more() // what is left of the last read is the beginning of a command, the rest of which has yet to come
		responses = responses[:0]
		for request := in.next(); request != nil; request = in.next() {
			responses = cache.handle(request, responses)
			answered++
		}
		if len(responses) > 0 {
			_, err := conn.Write(responses)
			check(err)
		}
	}
}

// serveGathering answers every command a read brought, in one gathering write of the answers' parts.
func serveGathering(conn *net.TCPConn, rounds int) {
	in := newRespConn(conn, largestBatch)
	cache := &gatheringCache{values: make(map[string][]byte), counts: make(map[string]int)}
	parts := make(net.Buffers, 0, batch*3)
	heads := make([]byte, 0, batch*16)
	for answered := 0; answered < rounds; {
		in.more()
		parts, heads = parts[:0], heads[:0]
		for request := in.next(); request != nil; request = in.next() {
			parts, heads = cache.answer(request, parts, heads)
			answered++
		}
		if len(parts) > 0 {
			writing := parts // WriteTo takes from the slice it is called on what it has written
			_, err := writing.WriteTo(conn)
			check(err)
		}
	}
}

// tcpRealisticBulk is tcpRealistic with the requests sent batch at once, as a client that pipelines sends them.
func tcpRealisticBulk(name string, connections, rounds int, gathering bool) {
	listener, pairs := connected(connections)
	timed(name, int64(rounds)*int64(connections), func(wg *sync.WaitGroup) {
		for id, p := range pairs {
			if gathering {
				wg.Go(func() { serveGathering(p.server, rounds) })
			} else {
				wg.Go(func() { serveBulk(p.server, rounds) })
			}
			wg.Go(func() {
				in := newRespConn(p.client, largestBatch)
				user := newCacheClient(id)
				requests := make([]byte, 0, largestBatch)
				var answers [batch][]byte
				for i := 0; i < rounds; {
					together := min(batch, rounds-i)
					requests = requests[:0]
					for k := range together {
						requests = user.request(i+k, requests)
						answers[k] = user.expected
					}
					_, err := p.client.Write(requests)
					check(err)
					for k := range together {
						user.verify(i+k, answers[k], in.value())
					}
					i += together
				}
			})
		}
	})
	closePairs(listener, pairs)
}

// workRealistic is the work of tcp-realistic with nothing between the two sides: what the scenario costs beside its
// I/O.
func workRealistic(name string, connections, rounds int) {
	timed(name, int64(rounds)*int64(connections), func(wg *sync.WaitGroup) {
		wg.Go(func() {
			request := make([]byte, 0, largest)
			response := make([]byte, 0, largest)
			for id := range connections {
				cache := &cacheServer{values: make(map[string][]byte)}
				user := newCacheClient(id)
				for i := range rounds {
					request = user.request(i, request[:0])
					response = cache.handle(request, response[:0])
					user.verify(i, user.expected, response)
				}
			}
		})
	})
}

// ---- UDP

func bindUDP() (*net.UDPConn, netip.AddrPort) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	check(err)
	return conn, conn.LocalAddr().(*net.UDPAddr).AddrPort()
}

func wholeMessage(n int) {
	if n != messageSize {
		check(errors.New("a datagram of the wrong size"))
	}
}

// udpEcho starts a pair over two sockets that are bound and not connected. The sockets are closed, untimed, by the
// function it returns.
func udpEcho(wg *sync.WaitGroup, rounds int) func() {
	server, serverAddress := bindUDP()
	client, clientAddress := bindUDP()
	wg.Go(func() {
		buf := make([]byte, messageSize)
		for range rounds {
			n, _, err := server.ReadFromUDPAddrPort(buf)
			check(err)
			wholeMessage(n)
			_, err = server.WriteToUDPAddrPort(buf, clientAddress)
			check(err)
		}
	})
	wg.Go(func() {
		out := make([]byte, messageSize)
		in := make([]byte, messageSize)
		for range rounds {
			_, err := client.WriteToUDPAddrPort(out, serverAddress)
			check(err)
			n, _, err := client.ReadFromUDPAddrPort(in)
			check(err)
			wholeMessage(n)
		}
	})
	return func() {
		check(server.Close())
		check(client.Close())
	}
}

// udpConnectedEcho starts a pair over two sockets, each connected to the other.
func udpConnectedEcho(wg *sync.WaitGroup, rounds int) func() {
	// The server's port is found by binding, the client connected to it from a port of its own, and the server made
	// again on its port, connected to the client's.
	bound, serverAddress := bindUDP()
	client, err := net.DialUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)}, net.UDPAddrFromAddrPort(serverAddress))
	check(err)
	clientAddress := client.LocalAddr().(*net.UDPAddr)
	check(bound.Close())
	server, err := net.DialUDP("udp4", net.UDPAddrFromAddrPort(serverAddress), clientAddress)
	check(err)
	wg.Go(func() {
		buf := make([]byte, messageSize)
		for range rounds {
			n, err := server.Read(buf)
			check(err)
			wholeMessage(n)
			_, err = server.Write(buf)
			check(err)
		}
	})
	wg.Go(func() {
		out := make([]byte, messageSize)
		in := make([]byte, messageSize)
		for range rounds {
			_, err := client.Write(out)
			check(err)
			n, err := client.Read(in)
			check(err)
			wholeMessage(n)
		}
	})
	return func() {
		check(server.Close())
		check(client.Close())
	}
}

// ---- the command line

func main() {
	workers := flag.Int("workers", 1, "threads the goroutines run on (GOMAXPROCS)")
	scenarios := flag.String("scenarios",
		"tcp-pingpong,tcp-concurrent,tcp-stream,tcp-connect,udp-pingpong,udp-concurrent,udp-connected,"+
			"tcp-realistic,tcp-realistic-bulk,tcp-realistic-gather,work-realistic",
		"which to run, in the order given")
	scale := flag.Float64("scale", 1.0, "multiplies every count")
	connections := flag.Int("connections", 64, "pairs in the concurrent scenarios")
	repeat := flag.Int("repeat", 1, "runs the scenarios that many times over")
	flag.Parse()
	if flag.NArg() != 0 {
		check(fmt.Errorf("unexpected argument %q", flag.Arg(0)))
	}
	runtime.GOMAXPROCS(*workers)

	n := func(x int) int { return max(1, int(float64(x)**scale)) }
	many := *connections

	for range *repeat {
		for _, scenario := range strings.Split(*scenarios, ",") {
			switch scenario {
			case "tcp-pingpong":
				tcpEcho("tcp-pingpong", 1, n(100_000))
			case "tcp-concurrent":
				tcpEcho("tcp-concurrent", many, n(5_000))
			case "tcp-stream":
				tcpStream(n(20_000))
			case "tcp-connect":
				tcpConnect(n(1_000))
			case "udp-pingpong":
				rounds := n(100_000)
				var closing func()
				timed("udp-pingpong", int64(rounds), func(wg *sync.WaitGroup) { closing = udpEcho(wg, rounds) })
				closing()
			case "udp-concurrent":
				rounds := n(5_000)
				closing := make([]func(), 0, many)
				timed("udp-concurrent", int64(rounds)*int64(many), func(wg *sync.WaitGroup) {
					for range many {
						closing = append(closing, udpEcho(wg, rounds))
					}
				})
				for _, c := range closing {
					c()
				}
			case "udp-connected":
				rounds := n(100_000)
				var closing func()
				timed("udp-connected", int64(rounds), func(wg *sync.WaitGroup) { closing = udpConnectedEcho(wg, rounds) })
				closing()
			case "tcp-realistic":
				tcpRealistic("tcp-realistic", many, n(2_000))
			case "tcp-realistic-bulk":
				tcpRealisticBulk("tcp-realistic-bulk", many, n(2_000), false)
			case "tcp-realistic-gather":
				tcpRealisticBulk("tcp-realistic-gather", many, n(2_000), true)
			case "work-realistic":
				workRealistic("work-realistic", many, n(2_000))
			default:
				// A scenario this stack cannot do prints nothing.
			}
		}
	}
}
