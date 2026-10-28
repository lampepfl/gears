// setjmp and longjmp in this binary skip the signal mask and alternate stack, as _setjmp and _longjmp do; references
// within the binary bind to these before libc's. On macOS, libc's setjmp saves the mask and alternate stack, and its
// longjmp sets the mask with sigprocmask, which takes the process lock and rewrites every thread's mask, so threads
// making these calls slow each other down. Scala Native's continuations call them on every resume, and its GC's
// register capture around every @blocking call. Nothing here relies on longjmp restoring the mask. A workaround until
// Scala Native uses _setjmp itself; see "Workaround: the setjmp override" in docs/io/scala-native.md. It comes with the
// kqueue backend, so it applies to every program that links it: code there that relies on longjmp restoring the signal
// mask would break. It changes nothing for correctness: tasks moved between the pool's threads checked out before it
// (MigrateCheck), and it only saves the system calls, 5 to 20% on the bench.
#if defined(__APPLE__) && defined(__aarch64__)
    .text
    .globl _setjmp
    .p2align 2
_setjmp:
    b __setjmp
    .globl _longjmp
    .p2align 2
_longjmp:
    b __longjmp
#endif
