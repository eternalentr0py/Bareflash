import com.bareflash.app.samsung.BrokkrNativeBridge;
import java.util.*;

public class Smoke {
    public static class Cb {
        public List<String> ev = Collections.synchronizedList(new ArrayList<>());
        public void onLog(int l, String m) { ev.add("log["+l+"] "+m); }
        public void onDevices(String[] ids) { ev.add("devices "+Arrays.toString(ids)); }
        public void onModel(String m) { ev.add("model "+m); }
        public void onStage(String s) { ev.add("stage "+s); }
        public void onPlanItem(int i,int k,int p,int d,String a,String b,String c,long s) { ev.add("plan "+i); }
        public void onPlanReady(int n,long t) { ev.add("planReady "+n+" "+t); }
        public void onItemActive(int i) { ev.add("active "+i); }
        public void onItemDone(int i) { ev.add("done "+i); }
        public void onProgress(long a,long b,long c,long d) { ev.add("progress"); }
        public void onDeviceError(int i,String m) { ev.add("devError "+i+" "+m); }
        public void onError(String m) { ev.add("error "+m); }
        public void onFinished(boolean ok,String m) { ev.add("finished ok="+ok+" "+m); }
    }
    /** Fake TCP transport whose peer never answers: handshake must fail cleanly. */
    public static class FakeTcp {
        int sends = 0;
        public String getId() { return "fake:1"; }
        public boolean isConnected() { return true; }
        public int sendChunk(byte[] b, int len, int t, int r) { sends++; return len; }
        public int receiveChunk(byte[] b, int len, int t, int r) { return -1; }
        public int receiveZlp(int r) { return 0; }
        public void setPacketSizeHint(int n) {}
        public void setTimeoutMs(int ms) {}
        public void close() {}
    }
    public static class SlowTcp extends FakeTcp {
        public int receiveChunk(byte[] b, int len, int t, int r) { try { Thread.sleep(150); } catch (Exception e) {} return -1; }
    }
    /** Fake USB transport: fd is invalid so libusb wrap (stub) fails cleanly. */
    public static class FakeUsb {
        public int nativeFd = -1, interfaceNumber = 1, bulkInAddress = 0x81, bulkOutAddress = 0x01;
        public String getId() { return "001/002"; }
    }
    public static void main(String[] a) throws Exception {
        System.loadLibrary("brokkr");
        BrokkrNativeBridge b = new BrokkrNativeBridge();
        // --- wireless path ---
        Cb cb = new Cb();
        long h = b.nativeCreateSession(cb, "cache");
        if (h == 0) throw new RuntimeException("create failed");
        java.io.File f = new java.io.File("AP_test.tar");
        var raf = new java.io.FileInputStream(f);
        int fd = fdOf(raf);
        FakeTcp ft = new FakeTcp();
        b.nativeRunWirelessSession(h, ft, new int[]{fd}, new String[]{"AP_test.tar"}, -1, null, true);
        System.out.println("fake tcp sends=" + ft.sends);
        b.nativeDestroySession(h);
        System.out.println("--- wireless events ---"); for (String s : cb.ev) System.out.println(s);
        // --- USB path ---
        Cb cb2 = new Cb();
        long h2 = b.nativeCreateSession(cb2, "cache");
        b.nativeRunSession(h2, new Object[]{ new FakeUsb() }, new int[]{fd}, new String[]{"fw.tar"}, -1, null, true);
        b.nativeDestroySession(h2);
        System.out.println("--- usb events ---"); for (String s : cb2.ev) System.out.println(s);
        // --- cancel mid-run ---
        Cb cb3 = new Cb();
        final long h3 = b.nativeCreateSession(cb3, "cache");
        Thread t = new Thread(() -> { try { Thread.sleep(100); } catch (Exception e) {} b.nativeRequestCancel(h3); });
        t.start();
        long t0 = System.nanoTime();
        b.nativeRunWirelessSession(h3, new SlowTcp(), new int[]{fd}, new String[]{"AP_test.tar"}, -1, null, true);
        System.out.println("cancel run returned after ms=" + (System.nanoTime()-t0)/1000000);
        b.nativeDestroySession(h3);
        System.out.println("--- cancel events ---"); for (String x : cb3.ev) System.out.println(x);
        // --- cancel on a destroyed session must be safe ---
        b.nativeRequestCancel(h2);
        System.out.println("cancel-after-destroy ok");
    }
    static int fdOf(java.io.FileInputStream s) throws Exception {
        var fdo = s.getFD(); var fld = java.io.FileDescriptor.class.getDeclaredField("fd"); fld.setAccessible(true); return fld.getInt(fdo);
    }
}
