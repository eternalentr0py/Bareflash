# ---------------------------------------------------------------------------
# Samsung engine: libbrokkr.so
#
# Vendored Brokkr Flash (Odin protocol, GPL-3.0, (c) 2026 Gabriel2392) under
# brokkr/, trimmed to the Android platform layer. Only the JNI entry points
# were renamed (com.oops.eros -> com.bareflash.app.samsung); the protocol,
# transports and flasher are upstream code.
#
# Brokkr needs C++23 (std::expected, std::format, std::byteswap, jthread).
# NDK 27's libc++ 18 still gates std::jthread/std::stop_token behind the
# experimental switch. It is header-only, so a compile definition is enough:
# do NOT pass -fexperimental-library, which would also try to link
# libc++experimental.a, and the NDK does not ship it.
# ---------------------------------------------------------------------------
set(BROKKR_DIR "${CMAKE_CURRENT_LIST_DIR}/brokkr")

add_library(brokkr SHARED
    ${BROKKR_DIR}/src-jni/brokkr_android_jni.cpp
    ${BROKKR_DIR}/src/core/thread_pool.cpp
    ${BROKKR_DIR}/src/io/random_access.cpp
    ${BROKKR_DIR}/src/io/tar.cpp
    ${BROKKR_DIR}/src/io/source.cpp
    ${BROKKR_DIR}/src/io/lz4_frame.cpp
    ${BROKKR_DIR}/src/third_party/md5/md5.c
    ${BROKKR_DIR}/src/third_party/lz4/lz4.c
    ${BROKKR_DIR}/src/protocol/odin/odin_cmd.cpp
    ${BROKKR_DIR}/src/protocol/odin/pit.cpp
    ${BROKKR_DIR}/src/protocol/odin/flash.cpp
    ${BROKKR_DIR}/src/protocol/odin/group_flasher.cpp
    ${BROKKR_DIR}/src/protocol/odin/pit_transfer.cpp
    ${BROKKR_DIR}/src/app/md5_xxh3_cache.cpp
    ${BROKKR_DIR}/src/app/md5_verify.cpp
    ${BROKKR_DIR}/src/platform/android/app_dirs.cpp
    ${BROKKR_DIR}/src/platform/android/java_tcp_transport.cpp
    ${BROKKR_DIR}/src/platform/android/libusb_transport.cpp
)

set_target_properties(brokkr PROPERTIES
    CXX_STANDARD 23
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

target_include_directories(brokkr PRIVATE
    ${BROKKR_DIR}/src
    ${BROKKR_DIR}/src-jni/shim
    ${CMAKE_CURRENT_LIST_DIR}/third_party/libusb
)

target_compile_definitions(brokkr PRIVATE
    BROKKR_PLATFORM_ANDROID
    _FILE_OFFSET_BITS=64
    _LIBCPP_ENABLE_EXPERIMENTAL
)

target_link_libraries(brokkr PRIVATE bfusb usb-1.0 android ${log-lib})

# Keep lz4/md5/libc++ symbols private so they cannot interpose on the other
# engines' libraries.
target_link_options(brokkr PRIVATE "-Wl,--exclude-libs,ALL")
