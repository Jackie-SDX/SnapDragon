package com.threeseeds.app.state

/** Where the nearby (WiFi/Bluetooth) link stands right now. */
enum class LinkStatus {
    /** No link attempted (also: left the lobby cleanly). */
    NONE,

    /** Hosting: waiting for a guest to appear. */
    HOSTING,

    /** Scanning for nearby hosts. */
    SCANNING,

    /** Dialing a chosen host. */
    CONNECTING,

    /** Handshake done; the match can run. */
    CONNECTED,

    /** Was connected, the link dropped mid-match. */
    LOST,

    /** A host/scan/connect attempt failed; message shown in the lobby. */
    ERROR
}
