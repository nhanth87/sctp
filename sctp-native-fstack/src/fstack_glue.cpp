#include "sctp_abi.h"

#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

#ifndef IPPROTO_SCTP
#define IPPROTO_SCTP 132
#endif
#ifndef SOCK_SEQPACKET
#define SOCK_SEQPACKET 5
#endif

#if defined(SCTP_HAVE_LKSCTP) || defined(SCTP_HAVE_FSTACK_DPDK)
#include <arpa/inet.h>
#include <netinet/sctp.h>
#endif

#ifdef SCTP_HAVE_FSTACK_DPDK
#include "ff_api.h"
#endif

namespace {
std::atomic<bool> g_dpdk_ready {false};
char g_last_error[256] = "DPDK/F-Stack not initialized";

void set_error(const char* operation) {
    std::snprintf(g_last_error, sizeof(g_last_error), "%s: %s", operation, std::strerror(errno));
}
}

extern "C" int sctp_fs_dpdk_init(int argc, char** argv) {
#ifdef SCTP_HAVE_FSTACK_DPDK
    if (g_dpdk_ready.load()) {
        return 0;
    }
    if (argc < 2 || argv == nullptr) {
        std::snprintf(g_last_error, sizeof(g_last_error), "F-Stack requires --conf=<path>");
        errno = EINVAL;
        return -1;
    }
    if (ff_init(argc, argv) != 0) {
        set_error("ff_init");
        return -1;
    }
    int probe = ff_socket(AF_INET, SOCK_STREAM, IPPROTO_SCTP);
    if (probe < 0) {
        set_error("ff_socket SCTP probe");
        return -1;
    }
    ff_close(probe);
    g_dpdk_ready.store(true);
    std::snprintf(g_last_error, sizeof(g_last_error), "ready");
    return 0;
#else
    (void)argc;
    (void)argv;
    errno = ENOTSUP;
    std::snprintf(g_last_error, sizeof(g_last_error),
            "binary is not linked with validated F-Stack SCTP + DPDK");
    return -1;
#endif
}

extern "C" int sctp_fs_dpdk_ready(void) {
    return g_dpdk_ready.load() ? 1 : 0;
}

extern "C" const char* sctp_fs_last_error(void) {
    return g_last_error;
}

extern "C" int sctp_fs_socket(int domain) {
#ifdef SCTP_HAVE_FSTACK_DPDK
    if (!g_dpdk_ready.load()) {
        errno = ENETDOWN;
        return -1;
    }
    return ff_socket(domain, SOCK_STREAM, IPPROTO_SCTP);
#elif defined(SCTP_HAVE_LKSCTP)
    return ::socket(domain, SOCK_SEQPACKET, IPPROTO_SCTP);
#else
    (void)domain;
    errno = ENOTSUP;
    return -1;
#endif
}

extern "C" int sctp_fs_bindx(int fd, const void* addrs, int addrcnt, int flags) {
#ifdef SCTP_HAVE_FSTACK_DPDK
    if (!g_dpdk_ready.load() || addrs == nullptr || addrcnt != 1) {
        errno = addrcnt == 1 ? ENETDOWN : EOPNOTSUPP;
        return -1;
    }
    (void)flags;
    const auto* addr = static_cast<const struct sockaddr*>(addrs);
    socklen_t len = addr->sa_family == AF_INET6 ? sizeof(struct sockaddr_in6) : sizeof(struct sockaddr_in);
    return ff_bind(fd, reinterpret_cast<const struct linux_sockaddr*>(addr), len);
#elif defined(SCTP_HAVE_LKSCTP)
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
#ifdef SCTP_HAVE_FSTACK_DPDK
    if (!g_dpdk_ready.load() || addrs == nullptr || addrcnt != 1) {
        errno = addrcnt == 1 ? ENETDOWN : EOPNOTSUPP;
        return -1;
    }
    (void)assoc_id;
    const auto* addr = static_cast<const struct sockaddr*>(addrs);
    socklen_t len = addr->sa_family == AF_INET6 ? sizeof(struct sockaddr_in6) : sizeof(struct sockaddr_in);
    return ff_connect(fd, reinterpret_cast<const struct linux_sockaddr*>(addr), len);
#elif defined(SCTP_HAVE_LKSCTP)
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
#ifdef SCTP_HAVE_FSTACK_DPDK
    struct iovec iov {const_cast<void*>(data), len};
    alignas(struct cmsghdr) unsigned char control[CMSG_SPACE(sizeof(struct sctp_sndrcvinfo))] {};
    struct msghdr msg {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = IPPROTO_SCTP;
    cmsg->cmsg_type = SCTP_SNDRCV;
    cmsg->cmsg_len = CMSG_LEN(sizeof(struct sctp_sndrcvinfo));
    auto* info = reinterpret_cast<struct sctp_sndrcvinfo*>(CMSG_DATA(cmsg));
    info->sinfo_stream = static_cast<uint16_t>(stream);
    info->sinfo_ppid = htonl(static_cast<uint32_t>(ppid));
    info->sinfo_flags = unordered != 0 ? SCTP_UNORDERED : 0;
    return static_cast<int>(ff_sendmsg(fd, &msg, 0));
#elif defined(SCTP_HAVE_LKSCTP)
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
#ifdef SCTP_HAVE_FSTACK_DPDK
    struct iovec iov {data, len};
    alignas(struct cmsghdr) unsigned char control[CMSG_SPACE(sizeof(struct sctp_sndrcvinfo))] {};
    struct msghdr msg {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    int n = static_cast<int>(ff_recvmsg(fd, &msg, MSG_DONTWAIT));
    if (n < 0) {
        return -1;
    }
    auto* cmsg = CMSG_FIRSTHDR(&msg);
    if (cmsg != nullptr && cmsg->cmsg_level == IPPROTO_SCTP) {
        auto* info = reinterpret_cast<struct sctp_sndrcvinfo*>(CMSG_DATA(cmsg));
        if (stream != nullptr) *stream = info->sinfo_stream;
        if (ppid != nullptr) *ppid = static_cast<int>(ntohl(info->sinfo_ppid));
    }
    if (notification != nullptr) *notification = (msg.msg_flags & MSG_NOTIFICATION) != 0 ? 1 : 0;
    return n;
#elif defined(SCTP_HAVE_LKSCTP)
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

extern "C" int sctp_fs_listen(int fd, int backlog) {
#ifdef SCTP_HAVE_FSTACK_DPDK
    return ff_listen(fd, backlog);
#else
    return ::listen(fd, backlog);
#endif
}

extern "C" int sctp_fs_accept(int fd) {
#ifdef SCTP_HAVE_FSTACK_DPDK
    return ff_accept(fd, nullptr, nullptr);
#else
    return ::accept(fd, nullptr, nullptr);
#endif
}

extern "C" int sctp_fs_close_fd(int fd) {
#ifdef SCTP_HAVE_FSTACK_DPDK
    return ff_close(fd);
#else
    return ::close(fd);
#endif
}

extern "C" int sctp_fs_set_nonblocking(int fd) {
#ifdef SCTP_HAVE_FSTACK_DPDK
    int on = 1;
    return ff_ioctl(fd, FIONBIO, &on);
#else
    int flags = ::fcntl(fd, F_GETFL, 0);
    return flags < 0 ? -1 : ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);
#endif
}

extern "C" int sctp_fs_socket_error(int fd) {
    int error = 0;
    socklen_t len = sizeof(error);
#ifdef SCTP_HAVE_FSTACK_DPDK
    if (ff_getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &len) != 0) return errno;
#else
    if (::getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &len) != 0) return errno;
#endif
    return error;
}

extern "C" int sctp_fs_capabilities(void) {
    int caps = SCTP_FS_CAP_LOOPBACK | SCTP_FS_CAP_MULTISTREAM;
#ifdef SCTP_HAVE_LKSCTP
    caps |= SCTP_FS_CAP_MULTIHOMING;
#endif
#ifdef SCTP_HAVE_FSTACK_DPDK
    if (g_dpdk_ready.load()) {
        caps |= SCTP_FS_CAP_DPDK | SCTP_FS_CAP_FSTACK_SCTP;
    }
#endif
    return caps;
}
