// The IP socket scenarios of io-bench/stacks/README.md, as goroutines over package net.
package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"runtime"
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
		"tcp-pingpong,tcp-concurrent,tcp-stream,tcp-connect,udp-pingpong,udp-concurrent,udp-connected",
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
			default:
				// A scenario this stack cannot do prints nothing.
			}
		}
	}
}
