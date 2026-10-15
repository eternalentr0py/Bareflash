# ---------------------------------------------------------------------------
# QDL engine: linux-msm/qdl v2.8 (vendored in qdl/, BSD-3-Clause) with the
# Bareflash Android patches, plus the fork-based runner.
#
# Included by cpp/CMakeLists.txt (Android build) and cpp/host/CMakeLists.txt
# (desktop test build). The includer must define, before including this file:
#   QDL_USB_LIBRARY  - target or library the USB code links against
#
# Third-party code needed by qdl is built from pinned upstream tags as static
# libraries, so no prebuilt binaries are committed:
#   libxml2 v2.14.6, libzip v1.11.4 (zlib comes from the platform).
# ---------------------------------------------------------------------------
include(FetchContent)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(FETCHCONTENT_QUIET OFF)

# ---- libxml2 (tree API only) ----
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_PYTHON    OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_PROGRAMS  OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_TESTS     OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_ICONV     OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_LZMA      OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_ZLIB      OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_HTTP      OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_MODULES   OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_THREADS   ON  CACHE BOOL "" FORCE)
FetchContent_Declare(libxml2
    GIT_REPOSITORY https://github.com/GNOME/libxml2.git
    GIT_TAG        v2.14.6
    GIT_SHALLOW    TRUE)

# ---- libzip (deflate/store only) ----
set(ENABLE_COMMONCRYPTO OFF CACHE BOOL "" FORCE)
set(ENABLE_GNUTLS       OFF CACHE BOOL "" FORCE)
set(ENABLE_MBEDTLS      OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENSSL      OFF CACHE BOOL "" FORCE)
set(ENABLE_WINDOWS_CRYPTO OFF CACHE BOOL "" FORCE)
set(ENABLE_BZIP2        OFF CACHE BOOL "" FORCE)
set(ENABLE_LZMA         OFF CACHE BOOL "" FORCE)
set(ENABLE_ZSTD         OFF CACHE BOOL "" FORCE)
set(BUILD_TOOLS         OFF CACHE BOOL "" FORCE)
set(BUILD_REGRESS       OFF CACHE BOOL "" FORCE)
set(BUILD_OSSFUZZ       OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES      OFF CACHE BOOL "" FORCE)
set(BUILD_DOC           OFF CACHE BOOL "" FORCE)
FetchContent_Declare(libzip
    GIT_REPOSITORY https://github.com/nih-at/libzip.git
    GIT_TAG        v1.11.4
    GIT_SHALLOW    TRUE)

FetchContent_MakeAvailable(libxml2 libzip)

# ---- qdl + glue ----
set(QDL_DIR "${CMAKE_CURRENT_LIST_DIR}/qdl")
set(QDL_ANDROID_DIR "${CMAKE_CURRENT_LIST_DIR}/android")
file(GLOB QDL_VENDOR_SRC "${QDL_DIR}/src/*.c")

add_library(qdl_core STATIC
    ${QDL_VENDOR_SRC}
    "${QDL_ANDROID_DIR}/qdl_android.c"
    "${QDL_ANDROID_DIR}/qdl_runner.c")

# Vendored qdl sources get the compat header (exit()/err() -> terminate the
# forked child) and qdl's main() becomes qdl_main().
set_source_files_properties(${QDL_VENDOR_SRC} PROPERTIES
    COMPILE_OPTIONS "-include;${QDL_ANDROID_DIR}/qdl_android_compat.h")
set_source_files_properties("${QDL_DIR}/src/qdl.c" PROPERTIES
    COMPILE_DEFINITIONS "main=qdl_main")

target_compile_definitions(qdl_core PRIVATE QDL_ANDROID=1 _GNU_SOURCE=1)
target_include_directories(qdl_core PUBLIC
    "${QDL_ANDROID_DIR}"                       # version.h, qdl_runner.h
    PRIVATE
    "${QDL_DIR}/src" "${QDL_DIR}/include"
    "${CMAKE_CURRENT_LIST_DIR}/third_party/libusb")
target_compile_options(qdl_core PRIVATE -Wno-unused-parameter)

find_package(ZLIB REQUIRED)
target_link_libraries(qdl_core PUBLIC
    ${QDL_USB_LIBRARY} bfusb LibXml2::LibXml2 libzip::zip ZLIB::ZLIB m)
