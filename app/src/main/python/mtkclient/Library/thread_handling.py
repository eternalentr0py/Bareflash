from queue import Queue

# Bytes handed to write() per call. Small enough that storage is busy the
# whole time the USB loop is running, large enough to keep syscall/FUSE
# overhead negligible.
WRITE_COALESCE = 2 * 1024 * 1024


def _write_all(fh, data):
    view = memoryview(data)
    while len(view):
        n = fh.write(view)
        if n is None:
            continue
        view = view[n:]


def writedata(filename, rq: Queue, errors=None):
    """
    Writer thread for dumps.

    Blocks from the USB loop are coalesced into ~2 MiB unbuffered writes so
    storage is written continuously. The old 8 MiB BufferedWriter only hit
    storage in one big blocking burst every 8 MiB; the 2 MiB queue in front
    of it filled during each burst, and the USB loop (and therefore the DA)
    sat idle waiting for it.

    If the destination fails, the thread keeps draining the queue so the USB
    loop can never block forever on a dead writer, and records the error in
    `errors` (if given) so the caller can report the dump as failed.
    """
    failed = False
    try:
        with open(filename, "wb", buffering=0) as wf:
            pending = []
            pending_len = 0
            while True:
                block = rq.get(timeout=300)
                if block is None:
                    break
                if failed:
                    continue
                pending.append(block)
                pending_len += len(block)
                if pending_len >= WRITE_COALESCE:
                    try:
                        _write_all(wf, pending[0] if len(pending) == 1 else b"".join(pending))
                    except Exception as e:
                        print(f"Write error: {e}")
                        if errors is not None:
                            errors.append(str(e))
                        failed = True
                    pending = []
                    pending_len = 0
            if pending and not failed:
                try:
                    _write_all(wf, pending[0] if len(pending) == 1 else b"".join(pending))
                except Exception as e:
                    print(f"Write error: {e}")
                    if errors is not None:
                        errors.append(str(e))
    except Exception as e:
        print(f"Writer thread exception: {e}")
        if errors is not None:
            errors.append(str(e))
        # Could not open (or lost) the destination: keep draining so the
        # producer never blocks on a full queue.
        try:
            while rq.get(timeout=300) is not None:
                pass
        except Exception:
            pass
    finally:
        rq.task_done()  # ensure queue doesn't hang
