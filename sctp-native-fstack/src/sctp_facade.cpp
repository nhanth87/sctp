#include "sctp_abi.h"
#include "sctp_ring.h"

#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>

namespace {

struct AssocState {
    std::string name;
    int64_t generation = 0;
    bool open = false;
    int inbound = 16;
    int outbound = 16;
};

std::mutex g_mu;
std::unordered_map<int64_t, AssocState> g_assocs;

} // namespace

extern "C" int sctp_fs_hello(int abi_version) {
    return abi_version == SCTP_FS_ABI_VERSION ? SCTP_FS_ABI_VERSION : -1;
}

extern "C" int sctp_fs_open(int64_t association_id, const char* name) {
    std::lock_guard<std::mutex> lock(g_mu);
    AssocState& assoc = g_assocs[association_id];
    assoc.name = name ? name : "";
    assoc.open = true;
    assoc.generation += 1;
    return 0;
}

extern "C" int sctp_fs_close(int64_t association_id, int64_t generation) {
    std::lock_guard<std::mutex> lock(g_mu);
    auto it = g_assocs.find(association_id);
    if (it == g_assocs.end()) {
        return 0;
    }
    if (generation != 0 && generation != it->second.generation) {
        return -2;
    }
    it->second.open = false;
    return 0;
}

extern "C" int sctp_fs_send(int64_t association_id, int64_t generation, int stream_id, int ppid,
        const void* payload, int payload_len, int flags) {
    std::lock_guard<std::mutex> lock(g_mu);
    auto it = g_assocs.find(association_id);
    if (it == g_assocs.end() || !it->second.open) {
        return -1;
    }
    if (generation != it->second.generation) {
        return -2;
    }
    if (stream_id < 0 || stream_id >= it->second.outbound) {
        return -3;
    }
    if (payload_len < 0 || payload_len > SCTP_FS_RING_SLOT_PAYLOAD) {
        return -4;
    }
    if (payload_len > 0 && payload == nullptr) {
        return -5;
    }
    /* RFC 4666: M3UA user messages are complete. DATA (stream != 0) is ordered.
     * Stream 0 ASPSM/MGMT/RKM MAY be unordered (jSS7 ASPUP). */
    if (ppid == SCTP_FS_PPID_M3UA) {
        if ((flags & SCTP_FS_FLAG_COMPLETE) == 0) {
            return -7;
        }
        if ((flags & SCTP_FS_FLAG_UNORDERED) != 0 && stream_id != SCTP_FS_STREAM_M3UA_MGMT) {
            return -6;
        }
    }
    /* SHARED: payload is a borrowed FFM/SHM view. Do not copy; do not free. */
    (void)payload;
    return 0;
}

extern "C" int sctp_fs_generation(int64_t association_id, int64_t* out) {
    std::lock_guard<std::mutex> lock(g_mu);
    auto it = g_assocs.find(association_id);
    if (it == g_assocs.end() || out == nullptr) {
        return -1;
    }
    *out = it->second.generation;
    return 0;
}
