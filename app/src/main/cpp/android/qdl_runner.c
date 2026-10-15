#include "qdl_runner.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

extern int qdl_main(int argc, char **argv);
extern volatile int qdl_android_usb_fd;

static atomic_int g_cancel;
static atomic_int g_child = 0;

void qdl_runner_cancel(void)
{
    atomic_store(&g_cancel, 1);
}

static long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/*
 * qdl terminates progress-bar updates with '\r' and everything else with
 * '\n'. Split on both and drop empty / blank lines (the progress bar erases
 * itself with a run of spaces).
 */
struct splitter {
    char buf[4096];
    size_t len;
    qdl_line_cb cb;
    void *user;
};

static void flush_line(struct splitter *sp)
{
    sp->buf[sp->len] = '\0';
    size_t i = 0;
    while (sp->buf[i] == ' ' || sp->buf[i] == '\t') i++;
    if (sp->buf[i] != '\0')
        sp->cb(sp->buf + i, sp->user);
    sp->len = 0;
}

static void feed(struct splitter *sp, const char *data, size_t n)
{
    for (size_t i = 0; i < n; i++) {
        char c = data[i];
        if (c == '\n' || c == '\r') {
            flush_line(sp);
        } else {
            if (sp->len + 1 >= sizeof(sp->buf))
                flush_line(sp);
            sp->buf[sp->len++] = c;
        }
    }
}

int qdl_runner_run(int usb_fd, int argc, char **argv, qdl_line_cb cb, void *user)
{
    int p[2];
    if (pipe(p) != 0)
        return QDL_RUN_PIPE_FAILED;

    atomic_store(&g_cancel, 0);

    pid_t pid = fork();
    if (pid < 0) {
        close(p[0]);
        close(p[1]);
        return QDL_RUN_FORK_FAILED;
    }

    if (pid == 0) {
        /* ---- child ---- */
        close(p[0]);
        dup2(p[1], STDOUT_FILENO);
        dup2(p[1], STDERR_FILENO);
        if (p[1] > STDERR_FILENO)
            close(p[1]);
        setvbuf(stdout, NULL, _IONBF, 0);
        setvbuf(stderr, NULL, _IONBF, 0);
        /*
         * A forked child inherits the parent's signal dispositions and mask.
         * The JVM/ART installs its own SIGTERM handler and blocks some
         * signals on its threads; none of that machinery exists in the child,
         * so a cancel (SIGTERM) would be swallowed. Restore defaults.
         */
        signal(SIGPIPE, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, NULL);
        /*
         * The child inherits the caller's CPU affinity (PerfBoost pins the
         * calling thread to the fast cores before this runs). Also ask for a
         * higher scheduling priority; best effort, ignored if refused.
         */
        setpriority(PRIO_PROCESS, 0, -10);
        qdl_android_usb_fd = usb_fd;
        int rc = qdl_main(argc, argv);
        fflush(NULL);
        _exit(rc & 0xff);
    }

    /* ---- parent ---- */
    atomic_store(&g_child, (int) pid);
    close(p[1]);
    fcntl(p[0], F_SETFL, fcntl(p[0], F_GETFL) | O_NONBLOCK);

    struct splitter sp = { .len = 0, .cb = cb, .user = user };
    int cancelled = 0;
    long kill_deadline = 0;
    int status = 0;
    int reaped = 0;
    int eof = 0;

    while (!reaped || !eof) {
        if (!eof) {
            struct pollfd pfd = { .fd = p[0], .events = POLLIN };
            int pr = poll(&pfd, 1, 100);
            if (pr > 0) {
                char tmp[2048];
                for (;;) {
                    ssize_t n = read(p[0], tmp, sizeof(tmp));
                    if (n > 0) {
                        feed(&sp, tmp, (size_t) n);
                        continue;
                    }
                    if (n == 0) eof = 1;
                    break;   /* EAGAIN or EOF */
                }
            } else if (pr < 0 && errno != EINTR) {
                eof = 1;
            }
        } else {
            usleep(20000);
        }

        if (!reaped) {
            pid_t w = waitpid(pid, &status, WNOHANG);
            if (w == pid) {
                reaped = 1;
                /* Drain whatever the child wrote right before exiting. */
                char tmp[2048];
                ssize_t n;
                while ((n = read(p[0], tmp, sizeof(tmp))) > 0)
                    feed(&sp, tmp, (size_t) n);
                eof = 1;
            }
        }

        if (!reaped && atomic_load(&g_cancel)) {
            if (!cancelled) {
                cancelled = 1;
                kill(pid, SIGTERM);
                kill_deadline = now_ms() + 1500;
            } else if (now_ms() > kill_deadline) {
                kill(pid, SIGKILL);
                kill_deadline = now_ms() + 1500;
            }
        }
    }

    flush_line(&sp);
    close(p[0]);
    atomic_store(&g_child, 0);

    if (cancelled)
        return QDL_RUN_CANCELLED;
    if (WIFSIGNALED(status))
        return QDL_RUN_SIGNALLED;
    return WIFEXITED(status) ? WEXITSTATUS(status) : QDL_RUN_SIGNALLED;
}
