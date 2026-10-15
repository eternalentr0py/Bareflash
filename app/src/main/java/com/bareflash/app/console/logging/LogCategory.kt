package com.bareflash.app.console.logging

/**
 * Classification of a terminal line for UI coloring.
 * Pure data — colors are chosen by the theme layer, not here.
 */
enum class LogCategory {
    ERROR,
    CONNECTED,
    DISCONNECTED,
    WARNING,
    COMMAND,
    DEFAULT,
}
