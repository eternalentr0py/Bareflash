`libusb.h` is the public header from libusb v1.0.27
(https://github.com/libusb/libusb/blob/v1.0.27/libusb/libusb.h, LGPL-2.1+),
SHA-256 e55b50eeedab44f0aa62ce6c2126d47ba80e8d9be1a67a62c27d2349bbf00c02.

It is only used to compile against; the code links the prebuilt
`jniLibs/<abi>/libusb-1.0.so` that the MediaTek engine already ships. The
engine only calls API that exists since libusb 1.0.23 plus
`libusb_wrap_sys_device()`/`LIBUSB_OPTION_NO_DEVICE_DISCOVERY`.
