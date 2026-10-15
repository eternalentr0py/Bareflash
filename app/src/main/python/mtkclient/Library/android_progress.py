# Android-only: forwards transfer progress from mtkclient's progress helper
# to the app UI (UiBridge.reportProgress). Silently inert off-device.
import time

try:
    from com.bareflash.app import UiBridge as _Ui
except Exception:  # not running under Chaquopy
    _Ui = None

_last = 0.0


def report(pos, total, label="", force=False):
    """Throttled to ~10 updates/second; the final update is always sent."""
    global _last
    if _Ui is None or total <= 0:
        return
    now = time.time()
    if not force and pos < total and now - _last < 0.1:
        return
    _last = now
    try:
        _Ui.reportProgress(int(pos), int(total), str(label or ""))
    except Exception:
        pass
