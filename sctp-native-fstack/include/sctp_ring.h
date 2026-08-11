#ifndef SCTP_FS_RING_H
#define SCTP_FS_RING_H

#include "sctp_abi.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SCTP_FS_RING_MAGIC 0x53465231u /* SFR1 */
#define SCTP_FS_RING_VERSION 1
#define SCTP_FS_RING_CAPACITY 1024
#define SCTP_FS_RING_SLOT_PAYLOAD 2048
#define SCTP_FS_RING_SLOT_HDR 40

typedef struct sctp_fs_ring_slot {
    int64_t association_id;
    int64_t generation;
    int32_t kind;
    int32_t stream_id;
    int32_t ppid;
    int32_t flags;
    int32_t payload_len;
    int32_t reserved;
    uint8_t payload[SCTP_FS_RING_SLOT_PAYLOAD];
} sctp_fs_ring_slot_t;

typedef struct sctp_fs_ring_header {
    uint32_t magic;
    uint32_t version;
    uint32_t capacity;
    uint32_t slot_bytes;
    uint64_t head;
    uint64_t tail;
    int32_t eventfd;
    int32_t backpressure;
    uint8_t pad[24];
} sctp_fs_ring_header_t;

#if defined(__cplusplus)
static_assert(sizeof(sctp_fs_ring_slot_t) == SCTP_FS_RING_SLOT_HDR + SCTP_FS_RING_SLOT_PAYLOAD,
        "ring slot layout");
static_assert(sizeof(sctp_fs_ring_header_t) == 64, "ring header must stay 64 bytes");
#endif

#ifdef __cplusplus
}
#endif

#endif
