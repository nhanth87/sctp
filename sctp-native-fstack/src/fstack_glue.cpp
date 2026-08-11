#include "sctp_abi.h"

#include <cerrno>
#include <cstdlib>
#include <netinet/in.h>
#include <sys/socket.h>

#ifndef IPPROTO_SCTP
#define IPPROTO_SCTP 132
#endif
#ifndef SOCK_SEQPACKET
#define SOCK_SEQPACKET 5
#endif

/*
 * FreeBSD SCTP syscall/glue. Stock F-Stack only builds alias_sctp.c.
 * These wrappers are the C ABI surface Java/FFM may call. They never
 * export struct sctp_inpcb. When SCTP_HAVE_FSTACK is unset they fail
 * closed so LOOPBACK/sidecar unit tests stay host-kernel independent.
 */

extern "C" int sctp_fs_socket(int domain) {
#ifdef SCTP_HAVE_FSTACK
    return ff_socket(domain, SOCK_SEQPACKET, IPPROTO_SCTP);
#else
    (void)domain;
    errno = ENOTSUP;
    return -1;
#endif
}

extern "C" int sctp_fs_bindx(int fd, const void* addrs, int addrcnt, int flags) {
#ifdef SCTP_HAVE_FSTACK
    return ff_sctp_bindx(fd, addrs, addrcnt, flags);
#else
    (void)fd;
    (void)addrs;
    (void)addrcnt;
    (void)flags;
    errno = ENOTSUP;
    return -1;
#endif
}

extern "C" int sctp_fs_connectx(int fd, const void* addrs, int addrcnt, void* assoc_id) {
#ifdef SCTP_HAVE_FSTACK
    return ff_sctp_connectx(fd, addrs, addrcnt, assoc_id);
#else
    (void)fd;
    (void)addrs;
    (void)addrcnt;
    (void)assoc_id;
    errno = ENOTSUP;
    return -1;
#endif
}

extern "C" int sctp_fs_sendv(int fd, const void* data, size_t len, int stream, int ppid,
        int unordered) {
    if (ppid == SCTP_FS_PPID_M3UA && unordered != 0 && stream != SCTP_FS_STREAM_M3UA_MGMT) {
        errno = EINVAL;
        return -1;
    }
#ifdef SCTP_HAVE_FSTACK
    return ff_sctp_sendv(fd, data, len, stream, ppid, unordered);
#else
    (void)fd;
    (void)data;
    (void)len;
    (void)stream;
    (void)ppid;
    (void)unordered;
    errno = ENOTSUP;
    return -1;
#endif
}

extern "C" int sctp_fs_recvv(int fd, void* data, size_t len, int* stream, int* ppid,
        int* notification) {
#ifdef SCTP_HAVE_FSTACK
    return ff_sctp_recvv(fd, data, len, stream, ppid, notification);
#else
    (void)fd;
    (void)data;
    (void)len;
    (void)stream;
    (void)ppid;
    (void)notification;
    errno = ENOTSUP;
    return -1;
#endif
}

extern "C" int sctp_fs_capabilities(void) {
    int caps = SCTP_FS_CAP_LOOPBACK | SCTP_FS_CAP_MULTISTREAM;
#ifdef SCTP_HAVE_FSTACK
    caps |= SCTP_FS_CAP_DPDK | SCTP_FS_CAP_MULTIHOMING;
#else
    /* CI / app-test emulation: advertise the DPDK capability so the app data
     * plane exercises the DPDK-selected path over the software ring. This is
     * NOT a real NIC — the on-NIC gate stays behind sctp.fstack.dpdk.port-up. */
    const char* emulate = ::getenv("SCTP_FS_EMULATE_DPDK");
    if (emulate != nullptr && emulate[0] != '\0' && emulate[0] != '0') {
        caps |= SCTP_FS_CAP_DPDK;
    }
#endif
    return caps;
}
