# Desktop build of the QDL engine, used to test the patched qdl and the fork
# runner without an Android toolchain:
#   cmake -S app/src/main/cpp/host -B build/qdl-host && cmake --build build/qdl-host
#   app/src/main/cpp/host/test.sh build/qdl-host/qdl_host
cmake_minimum_required(VERSION 3.22.1)
project(qdl_host C)

find_package(PkgConfig REQUIRED)
pkg_check_modules(LIBUSB REQUIRED IMPORTED_TARGET libusb-1.0)
set(QDL_USB_LIBRARY PkgConfig::LIBUSB)

# bfusb: the shared bulk-transfer helper qdl's usb.c now calls (see ../bfusb).
add_library(bfusb STATIC ../bfusb/bfusb.c)
set_target_properties(bfusb PROPERTIES POSITION_INDEPENDENT_CODE ON C_STANDARD 11)
target_include_directories(bfusb PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/..")
target_link_libraries(bfusb PUBLIC ${QDL_USB_LIBRARY})

include("${CMAKE_CURRENT_SOURCE_DIR}/../qdl_engine.cmake")

add_executable(qdl_host qdl_host.c)
target_link_libraries(qdl_host qdl_core pthread)

# The real JNI glue, loaded by a plain JVM (jni_test/) -- exercises argument
# conversion, the Kotlin-facing callback and fork() from a multithreaded JVM.
find_package(JNI COMPONENTS JVM)
if(JNI_FOUND)
    add_library(qdl_jni SHARED ../qdl_jni.c)
    target_include_directories(qdl_jni PRIVATE stub ${JNI_INCLUDE_DIRS})
    target_link_libraries(qdl_jni qdl_core)
endif()
