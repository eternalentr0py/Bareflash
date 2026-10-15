#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Android entry point for Chaquopy.

Called from ConsoleViewModel via Chaquopy. Sets sys.argv from the command
line and runs the real mtk.py main(), so every mtkclient subcommand keeps
working without a duplicated CLI.

Also provides cooperative cancellation and friendly + detailed error
reporting back to the Kotlin UI (UiBridge / UsbBridge).
"""

import ctypes
import importlib
import logging
import os
import re
import shlex
import sys
import threading
import traceback
import warnings

from com.bareflash.app import UiBridge, UsbBridge

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

_ANSI_RE = re.compile(r"\x1b\[[0-9;]*[a-zA-Z]")

# Result codes returned to the Kotlin app (ConsoleViewModel).
OK = "ok"
ERROR = "error"
CANCELLED = "cancelled"


class CommandCancelled(BaseException):
    """Raised inside the worker thread when the user cancels.

    Derives from BaseException so the many ``except Exception: pass``
    blocks in the protocol code cannot swallow it.
    """


# ---------------------------------------------------------------------------
# Worker / terminal state (process-wide; one command at a time)
# ---------------------------------------------------------------------------

_worker_ident = None
# Guards _worker_ident and _cancel_sent. cancel() and the worker's disarm step
# both take it, so a cancel can never be injected after the worker has finished
# (which would surface in Kotlin as a raw PyException).
_cancel_lock = threading.Lock()
# One async exception per run, however many times Stop is pressed. A second
# one could land inside an except/finally block and escape run_command.
_cancel_sent = False
# Which run is armed right now, the highest run id ever armed, and a Stop that
# arrived for a run that had not armed yet (see cancel()). Kotlin numbers runs.
_run_id = None
_last_armed_id = 0
_pending_cancel_id = None
_tail = []          # most recent output lines, used for error detail
_TAIL_MAX = 60

# Lines that are pure reconnect-wait noise: mtkclient reprints this whole
# block on every retry while it waits for a device (every few hundred ms,
# for as long as nothing is plugged in or the handshake keeps failing).
# Collapsed to a single "Waiting for device..." line that stays in place,
# instead of flooding the terminal with an identical block over and over.
_WAIT_NOISE = re.compile(
    r"""^(
        \[UsbBridge\]\ No\ MTK\ USB\ device\ attached |
        Preloader\ -\ Status:\ Waiting\ for\ PreLoader\ VCOM |
        Preloader\ -\ \[LIB\]:\ Status:\ Handshake\ failed,\ retrying |
        Port$ |
        Port\ -\ \[LIB\]:\ USB\ open\ failed |
        Port\ -\ Hint: |
        Power\ off\ the\ phone\ before\ connecting\. |
        For\ (brom|preloader)\ mode, |
        If\ it\ is\ already\ connected\ and\ on, |
        \.+$ |
        fuse\ library\ not\ installed$
    )""",
    re.VERBOSE,
)

_waiting_shown = False


class _UiStream:
    """File-like object so print()/logging reach the terminal.

    ANSI is stripped. Reconnect-wait noise (see _WAIT_NOISE) is collapsed
    to one status line instead of repeating the whole block on every
    retry. Anything containing '\\r' is a progress-bar redraw, never a
    noise line printed by anything this filters — it is forwarded exactly
    as received so the terminal can redraw that one line in place instead
    of appending a new line on every update.
    """

    def write(self, s):
        global _waiting_shown
        if not s:
            return
        clean = _ANSI_RE.sub("", s)
        # Split on real newlines only, so a '\r'-based progress redraw
        # embedded inside a segment stays intact and is never mistaken
        # for a complete, matchable noise line.
        segments = clean.split("\n")
        out = []
        for seg in segments:
            if "\r" in seg:
                # Progress redraws belong on the island / notification.
                # Forwarding them into Compose Text relayouts the whole
                # log on every DA frame -- that is the rotation-speed bug.
                _waiting_shown = False
                continue
            if _WAIT_NOISE.match(seg.strip()):
                if not _waiting_shown:
                    out.append("Waiting for device...")
                    _waiting_shown = True
                continue
            if seg.strip():
                _waiting_shown = False
                _tail.append(seg)
                del _tail[:-_TAIL_MAX]
            out.append(seg)
        if out:
            UiBridge.appendLog("\n".join(out))

    def flush(self):
        pass


# ---------------------------------------------------------------------------
# App log bridge: exceptions that never reach run_command's own handlers
# (worker threads, callbacks, anything after the command returned) still end
# up in Settings -> Logging with their full traceback.
# ---------------------------------------------------------------------------

def _applog(level, tag, message, stack=None):
    try:
        from com.bareflash.app.log import AppLog
        AppLog.fromPython(level, tag, message, stack)
    except Exception:
        pass


def _install_excepthooks():
    import threading

    def _sys_hook(exc_type, exc, tb):
        _applog("E", "python", f"Unhandled {exc_type.__name__}: {exc}",
                "".join(traceback.format_exception(exc_type, exc, tb)))

    def _thread_hook(args):
        if args.exc_type is SystemExit:
            return
        name = args.thread.name if args.thread else "?"
        _applog("E", "python-thread",
                f"Unhandled {args.exc_type.__name__} in thread {name}: {args.exc_value}",
                "".join(traceback.format_exception(args.exc_type, args.exc_value, args.exc_traceback)))

    sys.excepthook = _sys_hook
    threading.excepthook = _thread_hook


_install_excepthooks()


# ---------------------------------------------------------------------------
# Error mapping
# ---------------------------------------------------------------------------

# (substring or exception type name) -> friendly message
_FRIENDLY = [
    ("No USB permission", "USB permission has not been granted. Detect the device and allow access, then try again."),
    ("No MTK USB device", "No MediaTek device was found. Check the OTG connection and the device's power state."),
    ("did not reappear", "The device did not reconnect after the reset. Reconnect it and run the command again."),
    ("openDevice() failed", "The USB device could not be opened. It may have been disconnected."),
    ("claimInterface", "The USB interface is in use by another process. Reconnect the device and try again."),
    ("DAA_Security_Error", "The device rejected the Download Agent. Try the bypass commands or select a compatible DA file in Settings."),
    ("DA_SIG_VERIFY_FAILED", "Download Agent signature verification failed. Select a compatible DA file in Settings."),
    ("Timeout", "The device stopped responding. Reconnect it and try again."),
    ("timed out", "The device stopped responding. Reconnect it and try again."),
    ("FileNotFoundError", "A required file could not be found."),
    ("PermissionError", "The app does not have permission to access a required file."),
    ("Could not open the dump destination", "Could not open the dump folder."),
    ("No space left", "There is not enough free storage to complete the operation."),
    ("MemoryError", "The operation ran out of memory."),
    ("Couldn't detect partition", "The requested partition could not be found on this device's GPT. Check the partition name (see the partition table) and try again."),
    ("Couldn't get gpt", "The device's partition table could not be read. Reconnect the device and try again."),
]


def friendly_message(exc_text: str, exc_name: str = "") -> str:
    hay = f"{exc_name}: {exc_text}"
    for needle, msg in _FRIENDLY:
        if needle in hay:
            return msg
    return "The command could not be completed."


def _report_error(friendly: str, detail: str):
    UiBridge.appendLog(f"\n{friendly}\n")
    UiBridge.reportError(friendly, detail)


# ---------------------------------------------------------------------------
# Error-flag tracking: catching failures mtkclient never raises for
# ---------------------------------------------------------------------------
#
# Most real mtkclient command failures -- "Couldn't detect partition",
# "Couldn't get gpt", DA control-channel errors, etc. -- are reported by
# calling self.error(...) on a LogBase-derived object and then just falling
# through: the surrounding da_* helper returns None/False/nothing and
# handle_da_cmds()'s return value is itself discarded by mtk_main.py's
# run(). Nothing raises and nothing calls sys.exit() with a non-zero code,
# so run_command()'s SystemExit/Exception handling below never sees these
# at all -- the command reports RESULT_OK even though it visibly failed,
# same as every other case this session found (`_lastError` never set,
# so "Copy error details" / Settings -> Logging / crash reports are empty).
#
# self.error is bound as `logger.error` for every one of these classes
# (see mtkclient/Library/gui_utils.py's logsetup()), so rather than trying
# to plumb a success/failure return value through the hundreds of da_*
# call sites across the vendored mtkclient tree (invasive, and easy to
# miss one), this patches logging.Logger.error itself, once, at import
# time. Every self.error() call anywhere in mtkclient -- present or future
# vendored code -- is caught here regardless of which handler is attached
# to which logger at the time, which matters because mtkclient's own
# per-class dictConfig() calls (see the sys.stdout re-pointing comment in
# run_command() below) periodically reset the root logger's handler list;
# a handler attached there would risk being silently dropped mid-run. A
# monkeypatched method on the Logger class itself has no such gap.
#
# self.warning()/self.info() are deliberately left alone: mtkclient uses
# those for advisory/non-fatal messages ("We couldn't enter preloader.",
# per-partition "Failed to dump some partitions" inside an otherwise-OK
# batch), and folding those into "the command failed" would produce false
# positives. self.error() across this codebase is reserved for genuine
# failures.
_error_seen = False
_error_records = []
_orig_logger_error = logging.Logger.error


# self.error() across this codebase is reserved for genuine
# failures. A few messages are post-success housekeeping (optional
# download-act after a write that already committed, a trailing
# status() that timed out because the last send_param already
# consumed it). Those must not flip the command to RESULT_ERROR.
_NONFATAL_ERROR_NEEDLES = (
    "Optional download act",
    "Error on sending dev ctrl 0x800005",
    "CC_OPTIONAL_DOWNLOAD_ACT",
    "Status header error",
    "Status error: Wrong magic",
    "Status length error",
    "Error on getting packet length",
    "get_packet_length extra status",
)

_SUCCESS_MARKERS = (
    "Wrote ",
    "All Dumped partitions success",
)
_FAILURE_MARKERS = (
    "Failed to write ",
    "Failed to dump ",
    "Couldn't detect partition",
    "Couldn't get gpt",
    "Incomplete flash read",
    "Incomplete flash write",
)


def _tracking_logger_error(self, msg, *args, **kwargs):
    global _error_seen
    try:
        rendered = str(msg) % args if args else str(msg)
    except Exception:
        rendered = str(msg)
    if not any(n in rendered for n in _NONFATAL_ERROR_NEEDLES):
        _error_seen = True
        _error_records.append(rendered)
    return _orig_logger_error(self, msg, *args, **kwargs)


logging.Logger.error = _tracking_logger_error


# ---------------------------------------------------------------------------
# Public API used from Kotlin
# ---------------------------------------------------------------------------

def cancel(run_id=None):
    """Request cancellation of the running command (thread-safe, idempotent).

    run_id is the id Kotlin passed to run_command() for the run the person
    pressed Stop on. It keeps a Stop from landing on the wrong run:

    - the run already ended: this Stop is stale and is dropped, so it can
      never cancel the NEXT command's USB handshake;
    - the run was handed to the worker but has not armed yet: the Stop is
      remembered and honored the moment the run arms, instead of being lost.

    Without a run_id this behaves as before (cancels whatever is armed).
    """
    global _cancel_sent, _pending_cancel_id
    with _cancel_lock:
        ident = _worker_ident
        if run_id is not None and run_id != _run_id:
            if run_id > _last_armed_id:
                _pending_cancel_id = run_id
            return
        if ident is None or _cancel_sent:
            return  # nothing running, or already asked: do not stack exceptions
        _cancel_sent = True
        # Order matters. Queue the exception BEFORE failing USB I/O: once I/O
        # starts returning empty reads the worker may parse them and raise an
        # ordinary error (struct.error, IndexError...) before the cancel lands
        # and be reported as a failed command. Everything that
        # goes wrong after this point is treated as part of the cancel.
        ctypes.pythonapi.PyThreadState_SetAsyncExc(
            ctypes.c_ulong(ident), ctypes.py_object(CommandCancelled))
        UsbBridge.requestCancel(True)


def _arm_cancel(run_id=None):
    """From here on cancel() may inject CommandCancelled into this thread.

    Returns True when a Stop for this run arrived before it armed; the caller
    then skips the command instead of starting something already cancelled.
    """
    global _worker_ident, _cancel_sent, _run_id, _last_armed_id, _pending_cancel_id
    with _cancel_lock:
        _run_id = run_id
        if run_id is not None and run_id > _last_armed_id:
            _last_armed_id = run_id
        _cancel_sent = run_id is not None and _pending_cancel_id == run_id
        _pending_cancel_id = None
        _worker_ident = threading.get_ident()
        return _cancel_sent


def _disarm_cancel():
    """After this returns, no CommandCancelled can be delivered to this thread.

    Clears a pending-but-undelivered async exception under the same lock
    cancel() uses. Safe to call twice.
    """
    global _worker_ident, _run_id
    with _cancel_lock:
        ident, _worker_ident = _worker_ident, None
        _run_id = None
        if ident is not None and _cancel_sent:
            ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(ident), None)


def _run_command_impl(cmdline: str, workdir: str) -> str:
    """The body of run_command; see there for the cancel handshake."""
    global _error_seen
    del _tail[:]
    del _error_records[:]
    _error_seen = False
    UsbBridge.requestCancel(False)

    global _waiting_shown
    _waiting_shown = False
    warnings.simplefilter("ignore", ResourceWarning)
    os.environ["MTK_USE_ANDROID_USB"] = "1"
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

    sys.stdout = _UiStream()
    sys.stderr = _UiStream()

    # mtkclient's classes (Preloader, Mtk, DAXFlash, ...) configure Python's
    # logging module exactly once -- the first time each class is defined
    # -- via dictConfig(..., stream="ext://sys.stdout"), which resolves to
    # whatever sys.stdout *is at that moment* and keeps that same object
    # forever after. Only the very first command run in this process (the
    # only one where those classes are freshly imported) actually gets
    # this right; every command after that has its self.info()/self.debug()
    # output -- most of mtkclient's own diagnostic detail, including every
    # "Preloader - ..." line -- silently written into the *previous*
    # command's now-abandoned stream object, where nothing is reading it
    # anymore. Re-pointing every StreamHandler at the stream we just
    # created fixes that for this run.
    for _handler in logging.root.handlers:
        if isinstance(_handler, logging.StreamHandler):
            _handler.setStream(sys.stdout)

    result = OK
    try:
        try:
            os.makedirs(workdir, exist_ok=True)
            os.chdir(workdir)
        except Exception as e:
            _report_error("The working folder could not be opened.",
                          f"chdir({workdir!r}) failed: {e}")
            return ERROR

        try:
            import saf_fs
            saf_fs.install()
            from com.bareflash.app.dump import DumpBridge
        except Exception:
            pass

        # mtkclient writes a ".state" file after a successful DA connection
        # so a *later* command can skip the full crash/kamakiri/DA-upload
        # exploit chain and just "reinit" the same live session -- a real
        # desktop feature for running several commands against one session
        # that's still actually connected. Here, every command fully tears
        # down and reopens the USB connection first (see UsbBridge.connect,
        # which always calls close() before opening fresh) -- so by the
        # time the next command runs, whatever "reinit"-eligible session
        # mtkclient thinks it remembers is no longer the one actually on
        # the wire. Attempting reinit against it doesn't fail cleanly: it
        # reads a short/malformed status reply and crashes with a raw
        # struct.error instead of just reconnecting properly. Removing the
        # stale file before every command forces the same full reconnect
        # path that already works reliably as the very first command.
        try:
            os.remove(os.path.join(workdir, ".state"))
        except FileNotFoundError:
            pass
        except Exception:
            pass  # never let stale-state cleanup itself block a command

        # hwparam.json (settings.py's HwParam) is a second, separate
        # persistence file in the same directory, keyed by MEID -- unlike
        # .state it isn't gated behind an explicit "does this file exist"
        # check before being trusted; whatever's in it gets loaded back in
        # unconditionally whenever a matching device is seen again. Given
        # .state alone didn't fully resolve every 2nd-command symptom,
        # this is included as a reasonable additional attempt, not a
        # confirmed fix the way .state was -- but removing it is exactly
        # as safe (a file this app can regenerate any time it needs to).
        try:
            os.remove(os.path.join(workdir, "hwparam.json"))
        except FileNotFoundError:
            pass
        except Exception:
            pass

        try:
            argv = shlex.split(cmdline)
        except ValueError as e:
            _report_error("The command is malformed. Check for unmatched quotation marks.",
                          f"Could not parse {cmdline!r}: {e}")
            return ERROR
        if not argv:
            return OK

        sys.argv = ["mtk.py"] + argv
        try:
            # Chaquopy serves .py files through a virtual importer, so
            # run_path()/exec() cannot load mtk.py; re-import it fresh
            # each time and call main() so argparse starts clean.
            sys.modules.pop("mtk", None)
            importlib.import_module("mtk").main()
        except SystemExit as e:
            code = e.code
            if _cancel_sent:
                result = CANCELLED
            elif code not in (None, 0):
                detail = "\n".join(_tail) or f"exit code {code}"
                if code == 2:   # argparse usage error
                    _report_error("The command arguments were not accepted. "
                                  "Check the command and its options.", detail)
                else:
                    _report_error("The command finished with an error.", detail)
                result = ERROR
        except CommandCancelled:
            result = CANCELLED
        except Exception as e:
            if _cancel_sent:
                # Stop made the USB reads fail fast; whatever the protocol code
                # raised while parsing them is fallout, not the command's result.
                result = CANCELLED
            else:
                tb = traceback.format_exc()
                _report_error(friendly_message(str(e), type(e).__name__), tb)
                result = ERROR

        # See "Error-flag tracking" above: mtkclient reported a real
        # failure via self.error()/logger.error() but the call returned
        # normally (no SystemExit, no exception) -- e.g. detect_partition
        # not finding the requested partition on the GPT. Treat that the
        # same as any other failure so it actually reaches the UI/crash
        # report instead of silently finishing as "ok".
        if result == OK and _error_seen and not _cancel_sent:
            tail = "\n".join(_tail)
            error_text = "\n".join(_error_records)
            hay = tail + "\n" + error_text
            failed = any(m in hay for m in _FAILURE_MARKERS)
            succeeded = (
                any(m in tail for m in _SUCCESS_MARKERS)
                or "Dumped sector" in tail
                or "Dumped partition" in tail
            )
            if failed or not succeeded:
                detail = tail or error_text
                _report_error(friendly_message(error_text), detail)
                result = ERROR
    except CommandCancelled:
        result = CANCELLED
    finally:
        if result == CANCELLED:
            UiBridge.appendLog("\nCommand cancelled.\n")
        else:
            UiBridge.appendLog("\n[done]\n")
    return result


def run_command(cmdline: str, workdir: str, run_id=None) -> str:
    """Runs one command. Returns "ok", "error" or "cancelled".

    Cancel handshake: cancel() may inject CommandCancelled into this thread
    at any bytecode boundary while _worker_ident is set. It does so at most
    once per run. Whatever it hits is caught here, and _disarm_cancel() then
    makes sure nothing can arrive after we return, so Kotlin never sees a
    stray exception from a Stop press.
    """
    UsbBridge.requestCancel(False)  # before arming: a Stop cannot exist yet
    result = ERROR
    try:
        try:
            # Armed INSIDE the try, so a Stop pressed the instant the command
            # starts is still caught below instead of escaping.
            if _arm_cancel(run_id):
                # Stop was pressed before this run armed: nothing to start.
                result = CANCELLED
            else:
                result = _run_command_impl(cmdline, workdir)
        except CommandCancelled:
            result = CANCELLED
        _disarm_cancel()
    except CommandCancelled:
        # The one injected exception landed while disarming. It is spent now,
        # so a second disarm cannot be interrupted.
        result = CANCELLED
        _disarm_cancel()
    UsbBridge.requestCancel(False)
    return result
