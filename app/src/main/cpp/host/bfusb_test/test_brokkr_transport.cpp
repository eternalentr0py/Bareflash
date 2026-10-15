// Brokkr's real LibusbUsbTransport against the mock device.
#include "platform/android/libusb_transport.hpp"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <thread>
#include <vector>

#include "mock_libusb.h"

using namespace brokkr::android_platform;
using Clock = std::chrono::steady_clock;

static int g_fail = 0;
#define CHECK(c, ...) do { if (!(c)) { std::printf("  FAIL line %d: ", __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); ++g_fail; } } while (0)

extern "C" void mock_reset_for_cpp(double rate, int stall);
extern "C" void mock_set_claim_mode(int mode);

static long ms_since(Clock::time_point t) {
  return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t).count();
}

struct Rig {
  std::unique_ptr<LibusbContext> ctx;
  std::atomic<bool> cancel{false};
  std::unique_ptr<LibusbUsbTransport> t;
  explicit Rig(int timeout_ms) {
    auto c = LibusbContext::create();
    ctx = std::move(*c);
    LibusbUsbTransport::OpenParams p;
    p.context = ctx.get(); p.fd = 7; p.interface_number = 1;
    p.bulk_in_addr = 0x81; p.bulk_out_addr = 0x01; p.id = "001/002"; p.external_cancel = &cancel;
    auto o = LibusbUsbTransport::open(std::move(p));
    t = std::move(*o);
    t->set_timeout_ms(timeout_ms);
  }
};

static std::expected<std::unique_ptr<LibusbUsbTransport>, std::string> open_with_claim_mode(int mode) {
  mock_reset_for_cpp(50e6, 0);
  mock_set_claim_mode(mode);
  static std::unique_ptr<LibusbContext> ctx = std::move(*LibusbContext::create());
  static std::atomic<bool> cancel{false};
  LibusbUsbTransport::OpenParams p;
  p.context = ctx.get(); p.fd = 7; p.interface_number = 1;
  p.bulk_in_addr = 0x81; p.bulk_out_addr = 0x01; p.id = "001/003"; p.external_cancel = &cancel;
  return LibusbUsbTransport::open(std::move(p));
}

int main() {
  std::vector<std::uint8_t> big(1 << 20);

  std::puts("claim: interface already claimed by UsbDeviceConnection (BUSY with auto-detach) -> opens");
  { auto o = open_with_claim_mode(1);
    CHECK(o.has_value(), "open failed: %s", o ? "" : o.error().c_str()); }

  std::puts("claim: BUSY even without auto-detach (Java owns it) -> still opens");
  { auto o = open_with_claim_mode(2);
    CHECK(o.has_value(), "open failed: %s", o ? "" : o.error().c_str()); }

  std::puts("claim: a real failure (ACCESS) is still reported");
  { auto o = open_with_claim_mode(3);
    CHECK(!o.has_value(), "ACCESS must not be swallowed");
    if (!o) CHECK(o.error().find("claim_interface(1)") != std::string::npos, "message: %s", o.error().c_str()); }
  mock_reset_for_cpp(50e6, 0);

  std::puts("send 1 MiB at 50 MB/s (incl. ZLP probe)");
  { mock_reset_for_cpp(50e6, 0); Rig r(2000);
    auto t0 = Clock::now();
    int n = r.t->send(big);
    CHECK(n == (int)big.size(), "n=%d", n);
    CHECK(ms_since(t0) > 10 && ms_since(t0) < 250, "took %ld ms", ms_since(t0)); }

  std::puts("recv 4096");
  { mock_reset_for_cpp(50e6, 0); Rig r(2000);
    std::vector<std::uint8_t> in(4096);
    int n = r.t->recv(in);
    CHECK(n == 4096, "n=%d", n); }

  std::puts("stalled send, 30 s timeout, session cancel at 100 ms -> -1 promptly");
  { mock_reset_for_cpp(50e6, 1); Rig r(30000);
    std::thread th([&] { std::this_thread::sleep_for(std::chrono::milliseconds(100)); r.cancel = true; });
    auto t0 = Clock::now();
    int n = r.t->send(big);
    long ms = ms_since(t0); th.join();
    std::printf("  returned %d after %ld ms\n", n, ms);
    CHECK(n == -1, "n=%d", n);
    CHECK(ms >= 95 && ms < 250, "took %ld ms (previously up to kCancelPollMs=1000)", ms);
    CHECK(!r.t->connected(), "transport should be marked disconnected"); }

  std::puts("stalled recv, close_now() from another thread aborts the in-flight transfer");
  { mock_reset_for_cpp(50e6, 1); Rig r(30000);
    std::thread th([&] { std::this_thread::sleep_for(std::chrono::milliseconds(100)); r.t->close_now(); });
    std::vector<std::uint8_t> in(4096);
    auto t0 = Clock::now();
    int n = r.t->recv(in);
    long ms = ms_since(t0); th.join();
    std::printf("  returned %d after %ld ms\n", n, ms);
    CHECK(n == -1, "n=%d", n);
    CHECK(ms >= 95 && ms < 300, "took %ld ms (previously close_now waited for the 1 s poll slice)", ms); }

  std::puts("stalled send with a short 200 ms timeout and 2 retries -> gives up");
  { mock_reset_for_cpp(50e6, 1); Rig r(200);
    auto t0 = Clock::now();
    int n = r.t->send(std::span<const std::uint8_t>(big.data(), 4096), 2);
    long ms = ms_since(t0);
    std::printf("  returned %d after %ld ms\n", n, ms);
    CHECK(n == -1, "n=%d", n);
    CHECK(ms >= 400 && ms < 1500, "took %ld ms", ms); }

  std::puts(g_fail ? "FAILED" : "ALL PASSED");
  return g_fail ? 1 : 0;
}
