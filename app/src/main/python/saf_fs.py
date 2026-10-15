#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Redirect open/mkdir under the command working directory's dumps/ folder
to DumpBridge, which hands Python a native fd pointing at the user-chosen
SAF folder (SD card, USB stick, Downloads, ...).

Bytes go straight to that fd. There is no app-private temp copy.
"""

import builtins
import errno
import hashlib
import json
import os
import queue
import threading

from com.bareflash.app.dump import DumpBridge

_installed = False
_real_open = builtins.open
_real_mkdir = os.mkdir
_real_makedirs = os.makedirs
_real_exists = os.path.exists
_real_isdir = os.path.isdir
_real_isfile = os.path.isfile
_real_stat = os.stat


# ---- streamed SHA-256, per-region hashes and blank-block counts ----------------
# Binary dump files opened for writing are wrapped so their SHA-256 is computed
# on a side thread (hashlib drops the GIL for large buffers) while the data is
# written. Only plain sequential writes count; a seek, truncate or short write
# marks the digest invalid and Kotlin falls back to hashing the file afterwards.
#
# The same pass also records:
#   * a SHA-256 for every CHUNK bytes (so a mismatch can be pinned to a region)
#   * how many 4 KiB blocks are entirely 0x00 or entirely 0xFF (per chunk and
#     overall), which exposes reads that returned nothing but erased/locked data.

CHUNK = 64 * 1024 * 1024
BLK = 4096
_ZERO = bytes(BLK)
_FF = b"\xff" * BLK

_digests = {}
_dig_lock = threading.Lock()


def _hash_on():
    try:
        return bool(DumpBridge.hashEnabled())
    except Exception:
        return False


class _Scanner(object):
    """Feeds bytes in order; keeps whole-file and per-chunk SHA-256 plus blank-block counts."""

    def __init__(self, chunk=CHUNK):
        self.chunk = chunk
        self.whole = hashlib.sha256()
        self._ch = hashlib.sha256()
        self._ch_len = 0
        self.chunks = []
        self.chunk_zero = []
        self.chunk_ff = []
        self._cz = 0
        self._cf = 0
        self.blocks = 0
        self.zero = 0
        self.ff = 0
        self._carry = b""
        self.total = 0

    def _count(self, blk):
        self.blocks += 1
        n = len(blk)
        if n == BLK:
            if blk == _ZERO:
                self.zero += 1
                self._cz += 1
            elif blk == _FF:
                self.ff += 1
                self._cf += 1
        elif blk == _ZERO[:n]:
            self.zero += 1
            self._cz += 1
        elif blk == _FF[:n]:
            self.ff += 1
            self._cf += 1

    def _blank(self, part, last=False):
        # part is a bytes object that starts on a block boundary of the stream
        if self._carry:
            need = BLK - len(self._carry)
            if len(part) < need:
                self._carry += part
                return
            self._count(self._carry + part[:need])
            part = part[need:]
            self._carry = b""
        n = len(part) - (len(part) % BLK)
        for i in range(0, n, BLK):
            self._count(part[i:i + BLK])
        if n < len(part):
            self._carry = part[n:]

    def _close_chunk(self):
        self.chunks.append(self._ch.hexdigest())
        self.chunk_zero.append(self._cz)
        self.chunk_ff.append(self._cf)
        self._ch = hashlib.sha256()
        self._ch_len = 0
        self._cz = 0
        self._cf = 0

    def feed(self, b):
        self.total += len(b)
        self.whole.update(b)
        off = 0
        n = len(b)
        while off < n:
            take = min(n - off, self.chunk - self._ch_len)
            part = b[off:off + take]
            self._ch.update(part)
            self._blank(part)
            self._ch_len += take
            off += take
            if self._ch_len == self.chunk:
                self._close_chunk()

    def finish(self):
        if self._carry:
            self._count(self._carry)
            self._carry = b""
        if self._ch_len > 0:
            self._close_chunk()
        return {
            "sha256": self.whole.hexdigest(),
            "bytes": self.total,
            "chunk": self.chunk,
            "chunks": self.chunks,
            "chunkZero": self.chunk_zero,
            "chunkFf": self.chunk_ff,
            "blocks": self.blocks,
            "zero": self.zero,
            "ff": self.ff,
        }


class _HashingWriter(object):
    def __init__(self, f, rel):
        self._f = f
        self._rel = rel
        self._sc = _Scanner()
        self._pos = 0
        self._valid = True
        self._closed = False
        self._q = queue.Queue(maxsize=64)
        self._t = threading.Thread(target=self._run, name="bf-hash", daemon=True)
        self._t.start()

    def _run(self):
        sc = self._sc
        q = self._q
        while True:
            b = q.get()
            if b is None:
                break
            try:
                sc.feed(b)
            except Exception:
                self._valid = False

    def write(self, data):
        n = self._f.write(data)
        if self._valid:
            try:
                b = data if isinstance(data, bytes) else bytes(data)
                if n is not None and n != len(b):
                    b = b[:n]
                self._pos += len(b)
                self._q.put(b)
            except Exception:
                self._valid = False
        return n

    def seek(self, offset, whence=0):
        r = self._f.seek(offset, whence)
        if r != self._pos:
            self._valid = False
        return r

    def truncate(self, *args):
        self._valid = False
        return self._f.truncate(*args)

    def close(self):
        if self._closed:
            return
        self._closed = True
        try:
            self._f.close()
        finally:
            self._q.put(None)
            self._t.join()
            d = self._sc.finish()
            d["valid"] = self._valid
            with _dig_lock:
                _digests[self._rel] = d

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()
        return False

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass

    def __iter__(self):
        return iter(self._f)

    def __getattr__(self, name):
        return getattr(self._f, name)


def take_digests():
    """JSON {relative path: {sha256, bytes, valid, chunk, chunks, ...}} for files closed since the last call."""
    with _dig_lock:
        d = dict(_digests)
        _digests.clear()
    return json.dumps(d)


# ---- compare-only reads (verify a dump against the device, nothing written) --------
# Kotlin arms a destination path with the expected hashes; a read whose output
# file is that path is hashed and compared instead of written. No bytes reach
# storage, so a 25 GB image can be re-checked on a phone with almost no free space.

_verify_specs = {}
_verify_results = {}
_verify_lock = threading.Lock()


def arm_verify(rel, spec_json):
    """Expect the next write to dumps/<rel> to be compared against spec instead of stored."""
    spec = json.loads(spec_json)
    with _verify_lock:
        _verify_specs[rel] = spec
        _verify_results.pop(rel, None)
    return True


def disarm_verify(rel):
    with _verify_lock:
        _verify_specs.pop(rel, None)
    return True


def take_verify(rel):
    """JSON result of the compare-only read of rel, or '' when none finished."""
    with _verify_lock:
        r = _verify_results.pop(rel, None)
    return json.dumps(r) if r is not None else ""


class _VerifySink(object):
    """File-like object that hashes what it is given and compares with the expected values."""

    def __init__(self, rel, spec):
        self._rel = rel
        self._spec = spec
        chunk = int(spec.get("chunk") or 0) or CHUNK
        self._sc = _Scanner(chunk)
        self._pos = 0
        self._valid = True
        self._closed = False
        self._q = queue.Queue(maxsize=64)
        self._t = threading.Thread(target=self._run, name="bf-verify", daemon=True)
        self._t.start()

    def _run(self):
        sc = self._sc
        q = self._q
        while True:
            b = q.get()
            if b is None:
                break
            try:
                sc.feed(b)
            except Exception:
                self._valid = False

    def writable(self):
        return True

    def readable(self):
        return False

    def seekable(self):
        return True

    def write(self, data):
        b = data if isinstance(data, bytes) else bytes(data)
        self._pos += len(b)
        if self._valid:
            self._q.put(b)
        return len(b)

    def flush(self):
        return None

    def tell(self):
        return self._pos

    def seek(self, offset, whence=0):
        if whence == 0 and offset == self._pos:
            return self._pos
        self._valid = False
        if whence == 0:
            self._pos = offset
        return self._pos

    def truncate(self, *args):
        self._valid = False
        return self._pos

    def fileno(self):
        raise OSError(errno.EBADF, "compare-only sink has no file descriptor")

    def close(self):
        if self._closed:
            return
        self._closed = True
        self._q.put(None)
        self._t.join()
        d = self._sc.finish()
        spec = self._spec
        want_chunks = spec.get("chunks") or []
        got_chunks = d["chunks"]
        bad = []
        if want_chunks:
            for i in range(max(len(want_chunks), len(got_chunks))):
                w = want_chunks[i] if i < len(want_chunks) else None
                g = got_chunks[i] if i < len(got_chunks) else None
                if w is None or g is None or w.lower() != g.lower():
                    bad.append(i)
        want_bytes = int(spec.get("bytes") or 0)
        want_sha = (spec.get("sha256") or "").lower()
        result = {
            "valid": self._valid,
            "bytes": d["bytes"],
            "wantBytes": want_bytes,
            "sha256": d["sha256"],
            "match": bool(self._valid and d["bytes"] == want_bytes and d["sha256"].lower() == want_sha),
            "chunk": d["chunk"],
            "chunkCount": len(got_chunks),
            "badChunks": bad,
            "blocks": d["blocks"],
            "zero": d["zero"],
            "ff": d["ff"],
        }
        with _verify_lock:
            _verify_results[self._rel] = result

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()
        return False

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass


def _dumps_rel(path, force=False):
    """Relative path inside dumps/, or None if this path must not redirect."""
    # Active when a dump folder is chosen, or when the user attached a file from
    # elsewhere: those are read in place through DumpBridge, never copied.
    # force skips that check (used to recognise an armed compare-only read).
    if not force and not (DumpBridge.isEnabled() or DumpBridge.hasPicked()):
        return None
    if path is None:
        return None
    try:
        path = os.fspath(path)
    except TypeError:
        return None
    if not isinstance(path, str):
        return None
    norm = os.path.normpath(path).replace("\\", "/")
    if norm == "dumps":
        return ""
    if norm.startswith("dumps/"):
        return norm[6:]
    cwd = os.getcwd().replace("\\", "/").rstrip("/")
    dumps_abs = cwd + "/dumps"
    abspath = norm if os.path.isabs(norm) else os.path.normpath(cwd + "/" + norm).replace("\\", "/")
    if abspath == dumps_abs:
        return ""
    prefix = dumps_abs + "/"
    if abspath.startswith(prefix):
        return abspath[len(prefix):]
    return None


def _open(file, mode="r", buffering=-1, encoding=None, errors=None,
          newline=None, closefd=True, opener=None):
    if opener is None and closefd:
        try:
            path = os.fspath(file)
        except TypeError:
            path = None
        if isinstance(path, str):
            if _verify_specs and any(c in mode for c in "wx") and "b" in mode:
                vrel = _dumps_rel(path, force=True)
                if vrel is not None:
                    with _verify_lock:
                        vspec = _verify_specs.get(vrel)
                    if vspec is not None:
                        return _VerifySink(vrel, vspec)
            rel = _dumps_rel(path)
            if rel is not None:
                if rel == "":
                    raise OSError(errno.EISDIR, "Is a directory", path)
                writing = any(c in mode for c in "wax")
                resumed = False
                if writing:
                    try:
                        resumed = DumpBridge.resumeOffset(rel) > 0
                    except Exception:
                        resumed = False
                fd = DumpBridge.openFd(rel, "w" if writing else "r")
                if fd < 0:
                    raise OSError(
                        errno.EIO,
                        "Could not open the dump destination. Pick a folder in Settings → General.",
                        path,
                    )
                m = mode.replace("t", "")
                if "b" not in m and "b" not in mode:
                    return os.fdopen(fd, m, buffering=buffering,
                                     encoding=encoding, errors=errors, newline=newline)
                buf = buffering if buffering != -1 else 8 * 1024 * 1024
                f = os.fdopen(fd, m if "b" in m else m + "b", buffering=buf)
                if (writing and "+" not in mode and "a" not in mode
                        and not resumed and _hash_on()):
                    return _HashingWriter(f, rel)
                return f
    return _real_open(file, mode, buffering, encoding, errors, newline, closefd, opener)


def _mkdir(path, mode=0o777, **kwargs):
    rel = _dumps_rel(path)
    if rel is not None:
        if not DumpBridge.mkdir(rel):
            raise OSError(errno.EACCES, "The dump folder could not be created", path)
        return
    return _real_mkdir(path, mode, **kwargs)


def _makedirs(name, mode=0o777, exist_ok=False):
    rel = _dumps_rel(name)
    if rel is not None:
        if not DumpBridge.mkdir(rel):
            raise OSError(errno.EACCES, "The dump folder could not be created", name)
        return
    return _real_makedirs(name, mode, exist_ok=exist_ok)


def _exists(path):
    rel = _dumps_rel(path)
    if rel is not None:
        return DumpBridge.exists(rel)
    return _real_exists(path)


def _isdir(path):
    rel = _dumps_rel(path)
    if rel is not None:
        return DumpBridge.isDir(rel)
    return _real_isdir(path)


def _isfile(path):
    rel = _dumps_rel(path)
    if rel is not None:
        return DumpBridge.exists(rel) and not DumpBridge.isDir(rel)
    return _real_isfile(path)


def _stat(path, *args, **kwargs):
    rel = _dumps_rel(path) if not args and not kwargs.get("dir_fd") else None
    if rel is not None:
        if rel == "" or DumpBridge.isDir(rel):
            return os.stat_result((0o040755, 0, 0, 2, 0, 0, 0, 0, 0, 0))
        size = DumpBridge.size(rel)
        if size >= 0:
            return os.stat_result((0o100644, 0, 0, 1, 0, 0, int(size), 0, 0, 0))
        raise FileNotFoundError(errno.ENOENT, "No such file or directory", os.fspath(path))
    return _real_stat(path, *args, **kwargs)


def install():
    """Idempotent. Safe to call at the start of every command."""
    global _installed
    if _installed:
        return
    builtins.open = _open
    os.mkdir = _mkdir
    os.makedirs = _makedirs
    os.path.exists = _exists
    os.path.isdir = _isdir
    os.path.isfile = _isfile
    os.stat = _stat
    _installed = True
