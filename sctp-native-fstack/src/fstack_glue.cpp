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

#ifdef SCTP_HAVE_FSTACK
#include <arpa/inet.h>
#include <netinet/sctp.h>
#endif

/*
 * Default SCTP sockets. freebsd/ is not in this tree; stock F-Stack lib
 * therefore cannot be linked. SCTP_HAVE_FSTACK uses lksctp (or usrsctp
 * later). CAP_DPDK is a separate compile flag — Netty is the JVM fallback
 * when this native path is absent, not the default.
 */

extern "C" int sctp_fs_socket(int domain) {
#ifdef SCTP_HAVE_FSTACK
    return ::socket(domain, SOCK_SEQPACKET, IPPROTO_SCTP);
#else
    (void)domain;
    errno = ENOTSUP;
    return -1;
#endif
}

extern "C" int sctp_fs_bindx(int fd, const void* addrs, int addrcnt, int flags) {
#ifdef SCTP_HAVE_FSTACK
    return ::sctp_bindx(fd, static_cast<struct sockaddr*>(const_cast<void*>(addrs)), addrcnt, flags);
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
    return ::sctp_connectx(fd, static_cast<struct sockaddr*>(const_cast<void*>(addrs)), addrcnt,
            static_cast<sctp_assoc_t*>(assoc_id));
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
    uint32_t flags = unordered != 0 ? SCTP_UNORDERED : 0;
    return ::sctp_sendmsg(fd, data, len, nullptr, 0, htonl(static_cast<uint32_t>(ppid)), flags,
            static_cast<uint16_t>(stream), 0, 0);
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
    struct sctp_sndrcvinfo info {};
    int flags = 0;
    int n = ::sctp_recvmsg(fd, data, len, nullptr, nullptr, &info, &flags);
    if (n < 0) {
        return -1;
    }
    if (stream != nullptr) {
        *stream = info.sinfo_stream;
    }
    if (ppid != nullptr) {
        *ppid = static_cast<int>(ntohl(info.sinfo_ppid));
    }
    if (notification != nullptr) {
        *notification = (flags & MSG_NOTIFICATION) != 0 ? 1 : 0;
    }
    return n;
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
    caps |= SCTP_FS_CAP_MULTIHOMING;
#endif
#ifdef SCTP_HAVE_DPDK
    caps |= SCTP_FS_CAP_DPDK;
#else
    /* CI / app-test emulation: advertise DPDK so the app data plane can
     * exercise the DPDK-selected path over the software ring. Not a NIC. */
    const char* emulate = ::getenv("SCTP_FS_EMULATE_DPDK");
    if (emulate != nullptr && emulate[0] != '\0' && emulate[0] != '0') {
        caps |= SCTP_FS_CAP_DPDK;
    }
#endif
    return caps;
}
