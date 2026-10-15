/* Desktop stand-in for <android/log.h>, used only by the host test build. */
#pragma once
#include <stdio.h>
#define ANDROID_LOG_INFO 4
#define __android_log_print(prio, tag, ...) ((void)(prio), (void)(tag), 0)
