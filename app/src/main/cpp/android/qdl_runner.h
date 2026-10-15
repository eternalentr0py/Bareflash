/*
 * Runs qdl_main() in a forked child process.
 *
 * Why a child process instead of calling qdl_main() in the app process:
 *  - qdl calls exit() on many error paths; in-process that would kill the app.
 *  - qdl keeps global state (getopt, flags, libusb context); a fresh process
 *    per command removes any chance of one run poisoning the next.
 *  - stdout/stderr can be redirected to a private pipe without touching the
 *    app's own fds 1/2.
 *  - cancelling is kill(), which works even while qdl is blocked inside a
 *    USB transfer.
 *
 * The USB descriptor is inherited by the child across fork().
 *
 * This file has no JNI or Android dependency, so it is also built and tested
 * on a desktop host.
 */
#ifndef QDL_RUNNER_H
#define QDL_RUNNER_H

typedef void (*qdl_line_cb)(const char *line, void *user);

/* Return codes (besides qdl's own exit status, 0..255): */
#define QDL_RUN_FORK_FAILED   (-1)
#define QDL_RUN_PIPE_FAILED   (-2)
#define QDL_RUN_CANCELLED     (-3)
#define QDL_RUN_SIGNALLED     (-4)   /* child died on a signal, e.g. SIGSEGV */

/* argv[0] is the program name and argv is NULL-terminated, as for main(). */
int qdl_runner_run(int usb_fd, int argc, char **argv,
                   qdl_line_cb cb, void *user);

/* Thread-safe; makes a running qdl_runner_run() return QDL_RUN_CANCELLED. */
void qdl_runner_cancel(void);

#endif
