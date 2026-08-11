#include "sctp_abi.h"
#include "sctp_ring.h"
#include "shm_ring.cpp"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <memory>
#include <poll.h>
#include <sstream>
#include <string>
#include <unistd.h>
#include <vector>
#include <sys/socket.h>
#include <sys/un.h>

using sctp::fstack::ShmRing;

struct Client {
    int fd = -1;
    int id = 0;
    std::unique_ptr<ShmRing> to_native;
    std::unique_ptr<ShmRing> to_java;
};

struct Assoc {
    int client_id = -1;
    int64_t assoc_id = 0;
    int64_t generation = 0;
    std::string host;
    int host_port = 0;
    std::string peer;
    int peer_port = 0;
    bool open = false;
};

static int read_fully(int fd, void* buf, size_t n) {
    auto* p = static_cast<unsigned char*>(buf);
    size_t got = 0;
    while (got < n) {
        ssize_t r = ::read(fd, p + got, n - got);
        if (r <= 0) {
            return -1;
        }
        got += static_cast<size_t>(r);
    }
    return 0;
}

static int write_fully(int fd, const void* buf, size_t n) {
    auto* p = static_cast<const unsigned char*>(buf);
    size_t put = 0;
    while (put < n) {
        ssize_t w = ::write(fd, p + put, n - put);
        if (w <= 0) {
            return -1;
        }
        put += static_cast<size_t>(w);
    }
    return 0;
}

static void reply(int fd, int kind, int64_t assoc, int64_t generation, const std::string& payload) {
    sctp_fs_frame_t frame {};
    frame.magic = SCTP_FS_MAGIC;
    frame.version = SCTP_FS_ABI_VERSION;
    frame.kind = kind;
    frame.association_id = assoc;
    frame.generation = generation;
    frame.payload_len = static_cast<int32_t>(payload.size());
    write_fully(fd, &frame, sizeof(frame));
    if (!payload.empty()) {
        write_fully(fd, payload.data(), payload.size());
    }
}

static bool tuples_pair(const Assoc& a, const Assoc& b) {
    return a.client_id != b.client_id
            && a.host == b.peer && a.peer == b.host
            && a.host_port == b.peer_port && a.peer_port == b.host_port;
}

static Assoc* find_assoc(std::vector<Assoc>& assocs, int client_id, int64_t assoc_id) {
    for (auto& assoc : assocs) {
        if (assoc.client_id == client_id && assoc.assoc_id == assoc_id) {
            return &assoc;
        }
    }
    return nullptr;
}

static Assoc* find_peer(std::vector<Assoc>& assocs, const Assoc& src) {
    for (auto& assoc : assocs) {
        if (assoc.open && tuples_pair(src, assoc)) {
            return &assoc;
        }
    }
    return nullptr;
}

static Client* find_client(std::vector<Client>& clients, int id) {
    for (auto& client : clients) {
        if (client.id == id) {
            return &client;
        }
    }
    return nullptr;
}

static void bring_up(std::vector<Client>& clients, Assoc& a, Assoc& b) {
    a.generation = a.generation == 0 ? 1 : a.generation + 1;
    b.generation = b.generation == 0 ? 1 : b.generation + 1;
    Client* ca = find_client(clients, a.client_id);
    Client* cb = find_client(clients, b.client_id);
    if (ca) {
        reply(ca->fd, SCTP_FS_EVT_UP, a.assoc_id, a.generation, "");
    }
    if (cb) {
        reply(cb->fd, SCTP_FS_EVT_UP, b.assoc_id, b.generation, "");
    }
}

static bool forward_payload(std::vector<Client>& clients, std::vector<Assoc>& assocs,
        int src_client, const sctp_fs_ring_slot_t& in) {
    Assoc* src = find_assoc(assocs, src_client, in.association_id);
    if (src == nullptr || !src->open) {
        return false;
    }
    Assoc* peer = find_peer(assocs, *src);
    if (peer == nullptr) {
        return false;
    }
    Client* dest = find_client(clients, peer->client_id);
    if (dest == nullptr || dest->to_java == nullptr) {
        return false;
    }
    sctp_fs_ring_slot_t out = in;
    out.association_id = peer->assoc_id;
    out.generation = peer->generation;
    return dest->to_java->offer(out);
}

static void drop_client(std::vector<Client>& clients, std::vector<Assoc>& assocs, int fd) {
    int dropped_id = -1;
    for (auto it = clients.begin(); it != clients.end(); ++it) {
        if (it->fd == fd) {
            dropped_id = it->id;
            ::close(it->fd);
            clients.erase(it);
            break;
        }
    }
    if (dropped_id < 0) {
        return;
    }
    for (auto& assoc : assocs) {
        if (assoc.client_id != dropped_id || !assoc.open) {
            continue;
        }
        assoc.open = false;
        Assoc* peer = find_peer(assocs, assoc);
        if (peer == nullptr) {
            continue;
        }
        Client* dest = find_client(clients, peer->client_id);
        if (dest != nullptr) {
            reply(dest->fd, SCTP_FS_EVT_LOST, peer->assoc_id, peer->generation, "");
        }
    }
}

int main(int argc, char** argv) {
    std::string path = "data/sctp-fstack.sock";
    std::string ring_dir = "data";
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--socket" && i + 1 < argc) {
            path = argv[++i];
        } else if (arg == "--ring-dir" && i + 1 < argc) {
            ring_dir = argv[++i];
        }
    }
    ::unlink(path.c_str());
    int listen_fd = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (listen_fd < 0) {
        std::perror("socket");
        return 1;
    }
    sockaddr_un addr {};
    addr.sun_family = AF_UNIX;
    std::snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", path.c_str());
    if (::bind(listen_fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        std::perror("bind");
        return 1;
    }
    if (::listen(listen_fd, 8) != 0) {
        std::perror("listen");
        return 1;
    }
    std::cerr << "sctp-fstack listening on " << path
              << " abi=" << SCTP_FS_ABI_VERSION
              << " caps=" << sctp_fs_capabilities()
              << " multi-client loopback\n";

    std::vector<Client> clients;
    std::vector<Assoc> assocs;
    int next_client = 1;

    for (;;) {
        std::vector<pollfd> pfds;
        pfds.push_back(pollfd{listen_fd, POLLIN, 0});
        for (const auto& client : clients) {
            pfds.push_back(pollfd{client.fd, POLLIN, 0});
        }
        int pr = ::poll(pfds.data(), pfds.size(), 5);
        if (pr < 0) {
            if (errno == EINTR) {
                continue;
            }
            std::perror("poll");
            break;
        }
        if (pfds[0].revents & POLLIN) {
            int fd = ::accept(listen_fd, nullptr, nullptr);
            if (fd >= 0) {
                Client client;
                client.fd = fd;
                client.id = next_client++;
                clients.push_back(std::move(client));
                std::cerr << "client " << clients.back().id << " attached\n";
            }
        }
        for (size_t i = 1; i < pfds.size(); ++i) {
            if ((pfds[i].revents & (POLLIN | POLLHUP | POLLERR)) == 0) {
                continue;
            }
            int fd = pfds[i].fd;
            Client* client = nullptr;
            for (auto& c : clients) {
                if (c.fd == fd) {
                    client = &c;
                    break;
                }
            }
            if (client == nullptr) {
                continue;
            }
            sctp_fs_frame_t frame {};
            if (read_fully(fd, &frame, sizeof(frame)) != 0) {
                drop_client(clients, assocs, fd);
                continue;
            }
            if (frame.magic != SCTP_FS_MAGIC || frame.version != SCTP_FS_ABI_VERSION) {
                std::cerr << "ABI mismatch from client " << client->id << '\n';
                drop_client(clients, assocs, fd);
                continue;
            }
            std::string payload(static_cast<size_t>(std::max(0, frame.payload_len)), '\0');
            if (frame.payload_len > 0 &&
                    read_fully(fd, payload.data(), static_cast<size_t>(frame.payload_len)) != 0) {
                drop_client(clients, assocs, fd);
                continue;
            }
            switch (frame.kind) {
                case SCTP_FS_CMD_HELLO: {
                    std::string tx = ring_dir + "/sctp-fstack-" + std::to_string(client->id) + "-tx.ring";
                    std::string rx = ring_dir + "/sctp-fstack-" + std::to_string(client->id) + "-rx.ring";
                    client->to_native = std::make_unique<ShmRing>(tx, true);
                    client->to_java = std::make_unique<ShmRing>(rx, true);
                    std::string names = client->to_native->path() + "\n" + client->to_java->path();
                    reply(fd, SCTP_FS_CMD_HELLO, 0, sctp_fs_capabilities(), names);
                    break;
                }
                case SCTP_FS_CMD_OPEN: {
                    sctp_fs_open(frame.association_id, payload.c_str());
                    std::string name;
                    std::string host;
                    std::string peer;
                    std::istringstream in(payload);
                    std::getline(in, name);
                    std::getline(in, host);
                    std::getline(in, peer);
                    Assoc* existing = find_assoc(assocs, client->id, frame.association_id);
                    if (existing == nullptr) {
                        assocs.push_back(Assoc{});
                        existing = &assocs.back();
                    }
                    existing->client_id = client->id;
                    existing->assoc_id = frame.association_id;
                    existing->host = host;
                    existing->host_port = frame.stream_id;
                    existing->peer = peer;
                    existing->peer_port = frame.ppid;
                    existing->open = true;
                    Assoc* peer_assoc = find_peer(assocs, *existing);
                    if (peer_assoc != nullptr) {
                        bring_up(clients, *existing, *peer_assoc);
                    }
                    break;
                }
                case SCTP_FS_CMD_CLOSE: {
                    sctp_fs_close(frame.association_id, frame.generation);
                    Assoc* existing = find_assoc(assocs, client->id, frame.association_id);
                    if (existing != nullptr) {
                        existing->open = false;
                        Assoc* peer_assoc = find_peer(assocs, *existing);
                        if (peer_assoc != nullptr) {
                            Client* dest = find_client(clients, peer_assoc->client_id);
                            if (dest != nullptr) {
                                reply(dest->fd, SCTP_FS_EVT_SHUTDOWN, peer_assoc->assoc_id,
                                        peer_assoc->generation, "");
                            }
                        }
                    }
                    reply(fd, SCTP_FS_EVT_SHUTDOWN, frame.association_id, frame.generation, "");
                    break;
                }
                case SCTP_FS_CMD_SEND: {
                    int rc = sctp_fs_send(frame.association_id, frame.generation, frame.stream_id,
                            frame.ppid, payload.data(), frame.payload_len,
                            frame.flags | SCTP_FS_FLAG_COMPLETE);
                    if (rc == -2) {
                        std::cerr << "drop stale generation assoc=" << frame.association_id << '\n';
                        break;
                    }
                    sctp_fs_ring_slot_t slot {};
                    slot.association_id = frame.association_id;
                    slot.generation = frame.generation;
                    slot.kind = SCTP_FS_EVT_PAYLOAD;
                    slot.stream_id = frame.stream_id;
                    slot.ppid = frame.ppid;
                    slot.payload_len = std::min(frame.payload_len, SCTP_FS_RING_SLOT_PAYLOAD);
                    if (slot.payload_len > 0) {
                        std::memcpy(slot.payload, payload.data(), static_cast<size_t>(slot.payload_len));
                    }
                    if (!forward_payload(clients, assocs, client->id, slot) && client->to_native) {
                        if (!client->to_native->offer(slot)) {
                            reply(fd, SCTP_FS_EVT_CONGESTION, frame.association_id, frame.generation, "");
                        }
                    }
                    break;
                }
                case SCTP_FS_CMD_STATUS:
                    reply(fd, SCTP_FS_CMD_STATUS, frame.association_id, sctp_fs_capabilities(), "");
                    break;
                default:
                    break;
            }
        }
        for (auto& client : clients) {
            if (client.to_native == nullptr) {
                continue;
            }
            sctp_fs_ring_slot_t slot {};
            while (client.to_native->poll(&slot)) {
                if (!forward_payload(clients, assocs, client.id, slot)) {
                    std::cerr << "no peer for assoc=" << slot.association_id
                              << " client=" << client.id << '\n';
                }
            }
        }
    }
    ::close(listen_fd);
    ::unlink(path.c_str());
    return 0;
}
