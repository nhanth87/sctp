#ifndef SCTP_FS_ABI_H
#define SCTP_FS_ABI_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SCTP_FS_ABI_VERSION 1
#define SCTP_FS_MAGIC 0x53465431u /* SFT1 */
#define SCTP_FS_CAP_LOOPBACK (1u << 0)
#define SCTP_FS_CAP_DPDK (1u << 1)
#define SCTP_FS_CAP_MULTIHOMING (1u << 2)
#define SCTP_FS_CAP_MULTISTREAM (1u << 3)
#define SCTP_FS_CAP_FSTACK_SCTP (1u << 4)

#define SCTP_FS_CMD_HELLO 1
#define SCTP_FS_CMD_OPEN 2
#define SCTP_FS_CMD_CLOSE 3
#define SCTP_FS_CMD_SEND 4
#define SCTP_FS_CMD_STATUS 5

#define SCTP_FS_EVT_UP 10
#define SCTP_FS_EVT_SHUTDOWN 11
#define SCTP_FS_EVT_LOST 12
#define SCTP_FS_EVT_RESTART 13
#define SCTP_FS_EVT_PAYLOAD 14
#define SCTP_FS_EVT_CONGESTION 15

#define SCTP_FS_PPID_M3UA 3
#define SCTP_FS_STREAM_M3UA_MGMT 0

#define SCTP_FS_FLAG_UNORDERED (1 << 0)
#define SCTP_FS_FLAG_COMPLETE (1 << 1)
#define SCTP_FS_FLAG_SHARED (1 << 2)

#define SCTP_FS_FRAME_SIZE 48

/*
 * Packed POD for GraalVM FFM / Java ByteBuffer. Do not insert padding and do
 * not export FreeBSD sctp_inpcb. Payload pointer is borrowed for the call
 * (zero-copy); the caller keeps the segment alive.
 */
typedef struct sctp_fs_frame {
    uint32_t magic;
    uint32_t version;
    int32_t kind;
    int64_t association_id;
    int64_t generation;
    int32_t stream_id;
    int32_t ppid;
    int32_t payload_len;
    int32_t flags;
    int32_t reserved;
} __attribute__((packed)) sctp_fs_frame_t;

#if defined(__cplusplus)
static_assert(sizeof(sctp_fs_frame_t) == SCTP_FS_FRAME_SIZE, "sctp_fs_frame_t must stay 48 bytes");
#endif

int sctp_fs_hello(int abi_version);
int sctp_fs_open(int64_t association_id, const char* name);
int sctp_fs_close(int64_t association_id, int64_t generation);
int sctp_fs_send(int64_t association_id, int64_t generation, int stream_id, int ppid,
        const void* payload, int payload_len, int flags);
int sctp_fs_generation(int64_t association_id, int64_t* out);
int sctp_fs_capabilities(void);
int sctp_fs_dpdk_init(int argc, char** argv);
int sctp_fs_dpdk_ready(void);
const char* sctp_fs_last_error(void);
int sctp_fs_listen(int fd, int backlog);
int sctp_fs_accept(int fd);
int sctp_fs_close_fd(int fd);
int sctp_fs_set_nonblocking(int fd);
int sctp_fs_socket_error(int fd);
int sctp_fs_socket(int domain);
int sctp_fs_bindx(int fd, const void* addrs, int addrcnt, int flags);
int sctp_fs_connectx(int fd, const void* addrs, int addrcnt, void* assoc_id);
int sctp_fs_sendv(int fd, const void* data, size_t len, int stream, int ppid, int unordered);
int sctp_fs_recvv(int fd, void* data, size_t len, int* stream, int* ppid, int* notification);

#ifdef __cplusplus
}
#endif

#endif
