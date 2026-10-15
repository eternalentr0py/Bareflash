import com.bareflash.app.engine.QdlNative;
import java.util.ArrayList;
import java.util.List;

public class JniTest {
    static int failures = 0;
    static void check(String name, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) failures++;
    }
    public static void main(String[] a) throws Exception {
        System.loadLibrary("qdl_jni");
        check("embedded", QdlNative.nativeEmbedded());

        List<String> lines = new ArrayList<>();
        int rc = QdlNative.nativeRun(-1, new String[]{"--version"}, lines::add);
        check("--version exit 0", rc == 0);
        check("--version line", lines.stream().anyMatch(l -> l.startsWith("qdl version v2.8")));

        lines.clear();
        rc = QdlNative.nativeRun(-1, new String[]{"-s", "bogus", "x", "y"}, lines::add);
        check("errx -> exit code 1, JVM survives", rc == 1);
        check("errx message forwarded", lines.stream().anyMatch(l -> l.contains("unknown storage type")));

        // Non-UTF-8 byte in a path must not crash NewStringUTF on the way back.
        lines.clear();
        rc = QdlNative.nativeRun(-1, new String[]{"--version", "caf\u00e9"}, lines::add);
        check("non-ASCII argument ok", rc == 0);

        // Cancel from another thread while qdl waits for a device.
        lines.clear();
        Thread t = new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException e) { }
            QdlNative.nativeCancel();
        });
        t.start();
        long t0 = System.currentTimeMillis();
        rc = QdlNative.nativeRun(-1, new String[]{"-s", "ufs", "prog.elf", "rawprogram0.xml"}, lines::add);
        // prog.elf does not exist, so qdl exits on its own before it ever waits; that is fine,
        // the point is that cancel racing a finished child is harmless.
        check("cancel race harmless", rc == 1 || rc == -3);
        t.join();

        // Real cancel: needs a loadable programmer, created by the shell wrapper.
        lines.clear();
        Thread t2 = new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException e) { }
            QdlNative.nativeCancel();
        });
        t2.start();
        t0 = System.currentTimeMillis();
        rc = QdlNative.nativeRun(-1, new String[]{"-s", "ufs", "fw/prog.elf", "fw/rawprogram0.xml"}, lines::add);
        long dt = System.currentTimeMillis() - t0;
        System.out.println("   rc=" + rc + " lines=" + lines);
        check("cancel while waiting for device -> -3", rc == -3);
        check("cancel prompt (" + dt + " ms)", dt < 4000);
        t2.join();

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
