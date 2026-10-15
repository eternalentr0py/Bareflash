cmake_minimum_required(VERSION 3.22.1)
project(bareflash C CXX)

# ---------------------------------------------------------------------------
# Prebuilt libusb-1.0.so (jniLibs/<ABI>/), shared by both engines.
# Imported here purely so symbol references resolve at link time. AGP packages
# the jniLibs copy into the APK; cmake also copies IMPORTED shared libs into
# the merge dir, so app/build.gradle.kts sets packaging.jniLibs.pickFirsts for
# this soname to keep the two copies from colliding at mergeDebugNativeLibs.
# ---------------------------------------------------------------------------
add_library(usb-1.0 SHARED IMPORTED)
set_target_properties(usb-1.0 PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libusb-1.0.so"
)

find_library(log-lib log)

# ---------------------------------------------------------------------------
# MediaTek engine: libmtkusb.so
# usb_native.c caps OUT URBs at 16 KiB (Android USBFS) and IN URBs at one HS
# max-packet (always, bounced + leftover) so an unaligned data read cannot
# USB-overflow and drop a 512-byte DAX frame.
# ---------------------------------------------------------------------------
# bfusb: the cancellable / measured bulk-transfer helper shared by every engine.
add_library(bfusb STATIC bfusb/bfusb.c)
set_target_properties(bfusb PROPERTIES POSITION_INDEPENDENT_CODE ON C_STANDARD 11)
target_include_directories(bfusb PUBLIC "${CMAKE_CURRENT_LIST_DIR}")
target_include_directories(bfusb PRIVATE "${CMAKE_CURRENT_LIST_DIR}/third_party/libusb")
target_link_libraries(bfusb PUBLIC usb-1.0)

add_library(mtkusb SHARED usb_native.c)
target_link_libraries(mtkusb bfusb usb-1.0 ${log-lib})

# ---------------------------------------------------------------------------
# Qualcomm engine: libqdl_jni.so
# Vendored linux-msm/qdl v2.8 + Android patches + fork runner; libxml2 and
# libzip are fetched at configure time from pinned upstream tags and linked
# statically (see qdl_engine.cmake). First configure needs network access.
# ---------------------------------------------------------------------------
set(QDL_USB_LIBRARY usb-1.0)
include("${CMAKE_CURRENT_LIST_DIR}/qdl_engine.cmake")

add_library(qdl_jni SHARED qdl_jni.c)
target_link_libraries(qdl_jni qdl_core ${log-lib})
# Keep libxml2/libzip/qdl symbols private to this library.
target_link_options(qdl_jni PRIVATE "-Wl,--exclude-libs,ALL")

# ---------------------------------------------------------------------------
# Samsung engine: libbrokkr.so (Brokkr Flash / Odin protocol).
# ---------------------------------------------------------------------------
include("${CMAKE_CURRENT_LIST_DIR}/brokkr_engine.cmake")
