package com.bareflash.app.catalog

/** A titled section of the Commands tab (e.g. "Partition I/O"). */
data class McGroup(
    val title: String,
    val commands: List<McCommand>,
)
