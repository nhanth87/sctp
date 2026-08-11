#include "sctp_abi.h"
#include "../src/shm_ring.cpp"

#include <cstdio>
#include <cstring>
#include <string>
#include <sys/socket.h>
#include <unistd.h>

namespace {

int g_fails = 0;

#define CHECK(cond)                                                                                                    \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);                                       \
            g_fails++;                                                                                                 \
        }                                                                                                              \
    } while (0)

void test_abi_layout() {
    CHECK(sizeof(sctp_fs_frame_t) == SCTP_FS_FRAME_SIZE);
    CHECK(sizeof(sctp_fs_frame_t) == 48);
    CHECK(sizeof(sctp_fs_ring_header_t) == 64);
    CHECK(sizeof(sctp_fs_ring_slot_t) == SCTP_FS_RING_SLOT_HDR + SCTP_FS_RING_SLOT_PAYLOAD);
    CHECK(sctp_fs_hello(SCTP_FS_ABI_VERSION) == SCTP_FS_ABI_VERSION);
    CHECK(sctp_fs_hello(99) < 0);
    int caps = sctp_fs_capabilities();
    CHECK((caps & SCTP_FS_CAP_LOOPBACK) != 0);
    CHECK((caps & SCTP_FS_CAP_MULTISTREAM) != 0);
#ifndef SCTP_HAVE_FSTACK
    CHECK((caps & SCTP_FS_CAP_DPDK) == 0);
    CHECK(sctp_fs_socket(AF_INET) < 0);
#endif
}

void test_rfc4666_and_generation() {
    CHECK(sctp_fs_open(7, "assoc-a") == 0);
    int64_t gen = 0;
    CHECK(sctp_fs_generation(7, &gen) == 0);
    CHECK(gen == 1);

    char buf[4] = {1, 2, 3, 4};
    CHECK(sctp_fs_send(7, gen, 1, SCTP_FS_PPID_M3UA, buf, 4, SCTP_FS_FLAG_COMPLETE) == 0);
    CHECK(sctp_fs_send(7, gen, 0, SCTP_FS_PPID_M3UA, buf, 4,
            SCTP_FS_FLAG_COMPLETE | SCTP_FS_FLAG_UNORDERED) == 0);
    CHECK(sctp_fs_send(7, gen, 1, SCTP_FS_PPID_M3UA, buf, 4,
            SCTP_FS_FLAG_COMPLETE | SCTP_FS_FLAG_UNORDERED) == -6);
    CHECK(sctp_fs_send(7, gen, 1, SCTP_FS_PPID_M3UA, buf, 4, 0) == -7);
    CHECK(sctp_fs_send(7, gen + 1, 1, SCTP_FS_PPID_M3UA, buf, 4, SCTP_FS_FLAG_COMPLETE) == -2);
    CHECK(sctp_fs_send(99, 1, 1, SCTP_FS_PPID_M3UA, buf, 4, SCTP_FS_FLAG_COMPLETE) == -1);
    CHECK(sctp_fs_send(7, gen, 99, SCTP_FS_PPID_M3UA, buf, 4, SCTP_FS_FLAG_COMPLETE) == -3);

    CHECK(sctp_fs_close(7, gen) == 0);
    CHECK(sctp_fs_send(7, gen, 1, SCTP_FS_PPID_M3UA, buf, 4, SCTP_FS_FLAG_COMPLETE) == -1);

    CHECK(sctp_fs_open(7, "assoc-a") == 0);
    CHECK(sctp_fs_generation(7, &gen) == 0);
    CHECK(gen == 2);
    CHECK(sctp_fs_close(7, 1) == -2);
    CHECK(sctp_fs_close(7, gen) == 0);
}

void test_shm_ring() {
    std::string path = std::string("/tmp/sctp-fs-ring-") + std::to_string(::getpid());
    {
        sctp::fstack::ShmRing writer(path, true);
        sctp::fstack::ShmRing reader(path, false);
        sctp_fs_ring_slot_t slot {};
        slot.association_id = 7;
        slot.generation = 1;
        slot.kind = SCTP_FS_EVT_PAYLOAD;
        slot.ppid = SCTP_FS_PPID_M3UA;
        slot.payload_len = 3;
        slot.payload[0] = 9;
        CHECK(writer.offer(slot));
        sctp_fs_ring_slot_t out {};
        CHECK(reader.poll(&out));
        CHECK(out.association_id == 7);
        CHECK(out.generation == 1);
        CHECK(out.ppid == SCTP_FS_PPID_M3UA);
        CHECK(out.payload[0] == 9);
        CHECK(!reader.poll(&out));
        CHECK(!writer.congested());
    }
    ::unlink(path.c_str());
}

} // namespace

int main() {
    test_abi_layout();
    test_rfc4666_and_generation();
    test_shm_ring();
    if (g_fails != 0) {
        std::fprintf(stderr, "%d native sctp-fs check(s) failed\n", g_fails);
        return 1;
    }
    std::fprintf(stdout, "sctp_fs_tests ok\n");
    return 0;
}
