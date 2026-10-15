/*
 * Force-included (-include) into every vendored qdl source file.
 *
 * qdl is a command-line tool and ends the process on any fatal error through
 * exit()/err()/errx(). Bareflash runs it in a forked child (see qdl_runner.c),
 * so those paths must terminate *the child* immediately and quietly, without
 * running atexit handlers or static destructors inherited from the JVM
 * process. qdl_android_exit() does that: flush stdio, then _exit().
 *
 * __progname is a glibc/bionic internal; give qdl its own so it neither
 * depends on libc exporting it nor prints the app's process name.
 */
#ifndef QDL_ANDROID_COMPAT_H
#define QDL_ANDROID_COMPAT_H

#include <err.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

void qdl_android_exit(int status) __attribute__((noreturn));
extern const char *qdl_progname_;

#undef exit
#define exit(c) qdl_android_exit(c)

/* libc's warn*()/err*() print the *process* name (the app's package on
 * Android); route them to our own so messages read "qdl: ...". */
void qdl_warn(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void qdl_warnx(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

#undef warn
#define warn(...) qdl_warn(__VA_ARGS__)
#undef warnx
#define warnx(...) qdl_warnx(__VA_ARGS__)

#undef errx
#define errx(c, ...) (qdl_warnx(__VA_ARGS__), qdl_android_exit(c))

#undef err
#define err(c, ...) (qdl_warn(__VA_ARGS__), qdl_android_exit(c))

#define __progname qdl_progname_

/* OpenBSD sha2.c requires BSD BYTE_ORDER/LITTLE_ENDIAN/BIG_ENDIAN.
 * Bionic only exports the underscored names; clang always has the
 * __ORDER_*__/__BYTE_ORDER__ builtins. */
#ifndef LITTLE_ENDIAN
#define LITTLE_ENDIAN __ORDER_LITTLE_ENDIAN__
#endif
#ifndef BIG_ENDIAN
#define BIG_ENDIAN __ORDER_BIG_ENDIAN__
#endif
#ifndef BYTE_ORDER
#define BYTE_ORDER __BYTE_ORDER__
#endif

#endif
