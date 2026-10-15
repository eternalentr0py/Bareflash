import com.bareflash.app.LibusbNative;

public class MtkCancelTest {
    static int fail = 0;
    static void check(boolean ok, String msg) { if (!ok) { fail++; System.out.println("  FAIL: " + msg); } }

    public static void main(String[] a) throws Exception {
        System.loadLibrary("mtkusb_test");
        LibusbNative n = new LibusbNative();
        check(n.nativeInit(), "init");
        long h = n.nativeWrapFd(5);
        check(h != 0, "wrap");
        check(n.nativeClaimInterface(h, 1), "claim");
        n.nativeSetMaxPacket(512);
        byte[] mib = new byte[1 << 20];

        System.out.println("link names");
        check(n.nativeLinkName(512).startsWith("High-Speed") && !n.nativeLinkIsSlow(512), "512");
        check(n.nativeLinkName(64).startsWith("Full-Speed") && n.nativeLinkIsSlow(64), "64");

        System.out.println("normal 1 MiB write at 50 MB/s completes");
        n.nativeStatsReset(); MockCtl.set(50e6, 0); n.nativeSetCancelled(false);
        long t = System.nanoTime();
        int r = n.nativeBulkTransferExact(h, 0x01, mib, 0, mib.length, 5000);
        long ms = (System.nanoTime() - t) / 1_000_000;
        check(r == mib.length, "returned " + r);
        check(ms >= 15 && ms < 150, "took " + ms + " ms");
        String s = n.nativeStatsSummary();
        System.out.println("  " + s);
        check(s.startsWith("USB: out 1.0 MiB"), "summary: " + s);

        System.out.println("STALLED write, 30 s budget, Stop pressed at 100 ms -> returns promptly");
        MockCtl.set(50e6, 1); n.nativeSetCancelled(false);
        Thread stopper = new Thread(() -> { try { Thread.sleep(100); } catch (Exception e) {} n.nativeSetCancelled(true); });
        stopper.start();
        t = System.nanoTime();
        r = n.nativeBulkTransferExact(h, 0x01, mib, 0, mib.length, 30000);
        ms = (System.nanoTime() - t) / 1_000_000;
        stopper.join();
        System.out.println("  returned " + r + " after " + ms + " ms");
        check(r == -10, "expected LIBUSB_ERROR_INTERRUPTED(-10), got " + r);
        check(ms >= 95 && ms < 250, "took " + ms + " ms; before bfusb this blocked for the full 30000 ms");

        System.out.println("STALLED read with a 150 ms budget -> timeout, on time");
        n.nativeSetCancelled(false);
        t = System.nanoTime();
        r = n.nativeBulkTransferExact(h, 0x81, new byte[4096], 0, 4096, 150);
        ms = (System.nanoTime() - t) / 1_000_000;
        System.out.println("  returned " + r + " after " + ms + " ms");
        check(r == -7, "expected LIBUSB_ERROR_TIMEOUT(-7), got " + r);
        check(ms >= 140 && ms < 300, "took " + ms + " ms");

        System.out.println("cancelled flag set before the call -> refused immediately");
        n.nativeSetCancelled(true);
        r = n.nativeBulkTransferExact(h, 0x01, mib, 0, 4096, 1000);
        check(r == -10, "got " + r);
        n.nativeSetCancelled(false);

        System.out.println(fail == 0 ? "ALL PASSED" : "FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
