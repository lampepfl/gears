/* How often does a persistent, edge-triggered (EV_CLEAR) EVFILT_WRITE filter fire when nothing waits to write?
 * Registers one on the client end of a connection, then runs ping-pong (write 64 bytes, peer echoes, read 64) and,
 * after each step, polls the kqueue without blocking, counting write events. Also counts read events from a
 * persistent EV_CLEAR EVFILT_READ on the same socket, for comparison.
 *
 *   cc -O2 -o /tmp/write-events bench/write-events.c && /tmp/write-events unix|tcp [turns] [messages]
 *
 * Each turn the client writes `messages` messages of 64 bytes, the peer reads them all and echoes them, and the client
 * reads the echo. The counts are split by when they appear: after the client's writes, after the peer's read but
 * before its echo, and after the echo. See "Edge-triggered, persistent read filters" in docs/io/kqueue.md.
 */
#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/event.h>
#include <sys/socket.h>
#include <unistd.h>

static long writeEvents, readEvents, polls;
static long phaseWrites[3], phaseReads[3], alone[3];

/* Polls until nothing is reported, counting events, and, per phase, polls that reported a write event and no read. */
static void drain(int kq, int phase) {
    struct kevent ev[16];
    struct timespec zero = {0, 0};
    int n;
    do {
        n = kevent(kq, NULL, 0, ev, 16, &zero);
        polls++;
        int w = 0, r = 0;
        for (int i = 0; i < n; i++) {
            if (ev[i].filter == EVFILT_WRITE) w++;
            if (ev[i].filter == EVFILT_READ) r++;
        }
        writeEvents += w; readEvents += r;
        if (phase >= 0) { phaseWrites[phase] += w; phaseReads[phase] += r; if (w && !r) alone[phase]++; }
    } while (n > 0);
}

static void pair(int tcp, int *client, int *server) {
    if (!tcp) {
        int sv[2];
        socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
        *client = sv[0]; *server = sv[1];
        return;
    }
    int l = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in a; memset(&a, 0, sizeof a);
    a.sin_family = AF_INET; a.sin_addr.s_addr = htonl(INADDR_LOOPBACK); a.sin_port = 0;
    bind(l, (struct sockaddr *)&a, sizeof a); listen(l, 1);
    socklen_t len = sizeof a; getsockname(l, (struct sockaddr *)&a, &len);
    int c = socket(AF_INET, SOCK_STREAM, 0);
    connect(c, (struct sockaddr *)&a, sizeof a);
    int s = accept(l, NULL, NULL);
    int one = 1;
    setsockopt(c, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    close(l);
    *client = c; *server = s;
}

static void readAll(int fd, char *buf, int n) { int got = 0; while (got < n) { int r = read(fd, buf + got, n - got); if (r > 0) got += r; } }

int main(int argc, char **argv) {
    int tcp = argc > 1 && strcmp(argv[1], "tcp") == 0;
    int rounds = argc > 2 ? atoi(argv[2]) : 10000;
    int client, server;
    pair(tcp, &client, &server);
    int kq = kqueue();
    struct kevent ch[2];
    EV_SET(&ch[0], client, EVFILT_WRITE, EV_ADD | EV_CLEAR, 0, 0, NULL);
    EV_SET(&ch[1], client, EVFILT_READ, EV_ADD | EV_CLEAR, 0, 0, NULL);
    kevent(kq, ch, 2, NULL, 0, NULL);
    drain(kq, -1); /* the events registration itself reports */
    int burst = argc > 3 ? atoi(argv[3]) : 1;
    writeEvents = readEvents = polls = 0;
    char msg[64] = {1}, buf[64 * 64];
    for (int i = 0; i < rounds; i++) {
        for (int k = 0; k < burst; k++) write(client, msg, 64); /* the client sends; no write is left waiting */
        drain(kq, 0);
        readAll(server, buf, 64 * burst); /* the peer takes it all, freeing the client's send buffer */
        drain(kq, 1);                      /* before any echo: a write event here wakes the client for nothing */
        write(server, buf, 64 * burst);    /* the echo */
        usleep(0);
        drain(kq, 2);
        readAll(client, buf, 64 * burst);  /* the client reads it */
        drain(kq, -1);
    }
    double r = rounds;
    printf("%s, %d message(s) per turn: per turn, %.2f write events, %.2f read events\n", tcp ? "tcp " : "unix", burst,
           writeEvents / r, readEvents / r);
    const char *names[] = {"after the client's writes", "after the peer's read, before the echo", "after the echo"};
    for (int p = 0; p < 3; p++)
        printf("   %-40s write %.2f, read %.2f, polls with a write event and no read %.2f\n", names[p],
               phaseWrites[p] / r, phaseReads[p] / r, alone[p] / r);
    return 0;
}
