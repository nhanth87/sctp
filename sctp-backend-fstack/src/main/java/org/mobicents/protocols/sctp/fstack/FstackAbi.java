package org.mobicents.protocols.sctp.fstack;

/**
 * Versioned C ABI shared by the sidecar process and the GraalVM 25 FFM in-process library.
 * Keep this file bitwise-compatible with {@code sctp-native-fstack/include/sctp_abi.h}.
 */
public final class FstackAbi {
    public static final int ABI_VERSION = 1;
    public static final int CAP_LOOPBACK = 1 << 0;
    public static final int CAP_DPDK = 1 << 1;
    public static final int CAP_MULTIHOMING = 1 << 2;
    public static final int CAP_MULTISTREAM = 1 << 3;
    public static final int CAP_FSTACK_SCTP = 1 << 4;
    public static final int MAGIC = 0x53465431; // 'SFT1'
    public static final int FRAME_SIZE = 48;
    public static final int M3UA_MANAGEMENT_STREAM = 0;
    public static final int FLAG_UNORDERED = 1;
    public static final int FLAG_COMPLETE = 1 << 1;
    public static final int FLAG_SHARED = 1 << 2;

    public static final int CMD_HELLO = 1;
    public static final int CMD_OPEN = 2;
    public static final int CMD_CLOSE = 3;
    public static final int CMD_SEND = 4;
    public static final int CMD_STATUS = 5;

    public static final int EVT_UP = 10;
    public static final int EVT_SHUTDOWN = 11;
    public static final int EVT_LOST = 12;
    public static final int EVT_RESTART = 13;
    public static final int EVT_PAYLOAD = 14;
    public static final int EVT_CONGESTION = 15;

    private FstackAbi() {}
}
