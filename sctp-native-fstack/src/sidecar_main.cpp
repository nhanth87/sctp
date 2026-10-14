#include "sctp_abi.h"
#include "sctp_ring.h"
#include "shm_ring.cpp"

#include <algorithm>
#include <arpa/inet.h>
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
#include <netdb.h>
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
    bool server = false;
    bool open = false;
    bool up = false;
    int listen_fd = -1;
    int network_fd = -1;
};

enum class Dataplane { LOOPBACK, DPDK };

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

static void bring_up(std::vector<Client>& clients, Assoc& assoc) {
    if (assoc.up) {
        return;
    }
    assoc.generation = assoc.generation == 0 ? 1 : assoc.generation + 1;
    assoc.up = true;
    Client* client = find_client(clients, assoc.client_id);
    if (client != nullptr) {
        reply(client->fd, SCTP_FS_EVT_UP, assoc.assoc_id, assoc.generation, "");
    }
}

static int endpoint(const std::string& host, int port, sockaddr_storage& storage, socklen_t& len) {
    addrinfo hints {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_SCTP;
    addrinfo* result = nullptr;
    std::string service = std::to_string(port);
    int rc = ::getaddrinfo(host.c_str(), service.c_str(), &hints, &result);
    if (rc != 0 || result == nullptr || result->ai_addrlen > sizeof(storage)) {
        return -1;
    }
    std::memcpy(&storage, result->ai_addr, result->ai_addrlen);
    len = static_cast<socklen_t>(result->ai_addrlen);
    ::freeaddrinfo(result);
    return 0;
}

static bool open_network(Assoc& assoc) {
    sockaddr_storage local {};
    sockaddr_storage peer {};
    socklen_t local_len = 0;
    socklen_t peer_len = 0;
    if (endpoint(assoc.host, assoc.host_port, local, local_len) != 0
            || endpoint(assoc.peer, assoc.peer_port, peer, peer_len) != 0) {
        std::cerr << "invalid SCTP endpoint assoc=" << assoc.assoc_id << '\n';
        return false;
    }
    int fd = sctp_fs_socket(reinterpret_cast<sockaddr*>(&local)->sa_family);
    if (fd < 0 || sctp_fs_set_nonblocking(fd) != 0
            || sctp_fs_bindx(fd, &local, 1, 0) != 0) {
        std::cerr << "F-Stack SCTP bind failed assoc=" << assoc.assoc_id
                  << " error=" << sctp_fs_last_error() << " errno=" << errno << '\n';
        if (fd >= 0) sctp_fs_close_fd(fd);
        return false;
    }
    if (assoc.server) {
        if (sctp_fs_listen(fd, 16) != 0) {
            sctp_fs_close_fd(fd);
            return false;
        }
        assoc.listen_fd = fd;
        return true;
    }
    assoc.network_fd = fd;
    int rc = sctp_fs_connectx(fd, &peer, 1, nullptr);
    return rc == 0 || errno == EINPROGRESS || errno == EAGAIN;
}

static bool send_network(const Assoc& assoc, const sctp_fs_ring_slot_t& slot) {
    return assoc.network_fd >= 0
            && sctp_fs_sendv(assoc.network_fd, slot.payload, static_cast<size_t>(slot.payload_len),
                    slot.stream_id, slot.ppid, (slot.flags & SCTP_FS_FLAG_UNORDERED) != 0) >= 0;
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
    std::string dataplane_name = "LOOPBACK";
    std::string fstack_config;
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--socket" && i + 1 < argc) {
            path = argv[++i];
        } else if (arg == "--ring-dir" && i + 1 < argc) {
            ring_dir = argv[++i];
        } else if (arg == "--dataplane" && i + 1 < argc) {
            dataplane_name = argv[++i];
        } else if (arg == "--fstack-config" && i + 1 < argc) {
            fstack_config = argv[++i];
        } else {
            std::cerr << "unknown or incomplete argument: " << arg << '\n';
            return 2;
        }
    }
    Dataplane dataplane;
    if (dataplane_name == "LOOPBACK") {
        dataplane = Dataplane::LOOPBACK;
    } else if (dataplane_name == "DPDK") {
        dataplane = Dataplane::DPDK;
        if (fstack_config.empty()) {
            std::cerr << "DPDK requires --fstack-config <path>\n";
            return 2;
        }
        std::string conf_arg = "--conf=" + fstack_config;
        char app[] = "sctp-fstack";
        std::vector<char> conf(conf_arg.begin(), conf_arg.end());
        conf.push_back('\0');
        char proc[] = "--proc-type=primary";
        char proc_id[] = "--proc-id=0";
        char* ff_argv[] = {app, conf.data(), proc, proc_id};
        if (sctp_fs_dpdk_init(4, ff_argv) != 0 || sctp_fs_dpdk_ready() == 0) {
            std::cerr << "DPDK/F-Stack SCTP initialization failed: " << sctp_fs_last_error() << '\n';
            return 3;
        }
    } else {
        std::cerr << "invalid --dataplane; expected LOOPBACK or DPDK\n";
        return 2;
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
              << " dataplane=" << dataplane_name << '\n';

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
                    std::string type;
                    std::string host;
                    std::string peer;
                    std::istringstream in(payload);
                    std::getline(in, name);
                    std::getline(in, type);
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
                    existing->server = type == "SERVER";
                    existing->open = true;
                    if (dataplane == Dataplane::LOOPBACK) {
                        Assoc* peer_assoc = find_peer(assocs, *existing);
                        if (peer_assoc != nullptr) {
                            bring_up(clients, *existing, *peer_assoc);
                        }
                    } else if (!open_network(*existing)) {
                        reply(fd, SCTP_FS_EVT_LOST, frame.association_id, 0,
                                "F-Stack SCTP open failed");
                    }
                    break;
                }
                case SCTP_FS_CMD_CLOSE: {
                    sctp_fs_close(frame.association_id, frame.generation);
                    Assoc* existing = find_assoc(assocs, client->id, frame.association_id);
                    if (existing != nullptr) {
                        existing->open = false;
                        existing->up = false;
                        if (existing->network_fd >= 0) sctp_fs_close_fd(existing->network_fd);
                        if (existing->listen_fd >= 0) sctp_fs_close_fd(existing->listen_fd);
                        existing->network_fd = -1;
                        existing->listen_fd = -1;
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
                    slot.flags = frame.flags;
                    slot.payload_len = std::min(frame.payload_len, SCTP_FS_RING_SLOT_PAYLOAD);
                    if (slot.payload_len > 0) {
                        std::memcpy(slot.payload, payload.data(), static_cast<size_t>(slot.payload_len));
                    }
                    Assoc* network_assoc = find_assoc(assocs, client->id, frame.association_id);
                    bool sent = dataplane == Dataplane::DPDK
                            ? network_assoc != nullptr && send_network(*network_assoc, slot)
                            : forward_payload(clients, assocs, client->id, slot);
                    if (!sent && client->to_native) {
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
                Assoc* network_assoc = find_assoc(assocs, client.id, slot.association_id);
                bool sent = dataplane == Dataplane::DPDK
                        ? network_assoc != nullptr && send_network(*network_assoc, slot)
                        : forward_payload(clients, assocs, client.id, slot);
                if (!sent) {
                    std::cerr << "no peer for assoc=" << slot.association_id
                              << " client=" << client.id << '\n';
                }
            }
        }
        if (dataplane == Dataplane::DPDK) {
            for (auto& assoc : assocs) {
                if (!assoc.open) continue;
                if (assoc.server && assoc.network_fd < 0 && assoc.listen_fd >= 0) {
                    int accepted = sctp_fs_accept(assoc.listen_fd);
                    if (accepted >= 0) {
                        sctp_fs_set_nonblocking(accepted);
                        assoc.network_fd = accepted;
                        bring_up(clients, assoc);
                    }
                } else if (!assoc.server && assoc.network_fd >= 0 && !assoc.up) {
                    int socket_error = sctp_fs_socket_error(assoc.network_fd);
                    if (socket_error == 0) {
                        bring_up(clients, assoc);
                    } else if (socket_error != EINPROGRESS && socket_error != EALREADY
                            && socket_error != EAGAIN) {
                        Client* owner = find_client(clients, assoc.client_id);
                        if (owner != nullptr) reply(owner->fd, SCTP_FS_EVT_LOST,
                                assoc.assoc_id, assoc.generation, std::strerror(socket_error));
                        sctp_fs_close_fd(assoc.network_fd);
                        assoc.network_fd = -1;
                        assoc.open = false;
                    }
                }
                if (assoc.network_fd < 0 || !assoc.up) continue;
                sctp_fs_ring_slot_t slot {};
                slot.association_id = assoc.assoc_id;
                slot.generation = assoc.generation;
                slot.kind = SCTP_FS_EVT_PAYLOAD;
                int notification = 0;
                int n = sctp_fs_recvv(assoc.network_fd, slot.payload, SCTP_FS_RING_SLOT_PAYLOAD,
                        &slot.stream_id, &slot.ppid, &notification);
                if (n > 0 && notification == 0) {
                    slot.payload_len = n;
                    Client* dest = find_client(clients, assoc.client_id);
                    if (dest == nullptr || dest->to_java == nullptr || !dest->to_java->offer(slot)) {
                        Client* owner = find_client(clients, assoc.client_id);
                        if (owner != nullptr) reply(owner->fd, SCTP_FS_EVT_CONGESTION,
                                assoc.assoc_id, assoc.generation, "RX ring full");
                    }
                } else if (n == 0) {
                    Client* owner = find_client(clients, assoc.client_id);
                    if (owner != nullptr) reply(owner->fd, SCTP_FS_EVT_LOST,
                            assoc.assoc_id, assoc.generation, "peer closed");
                    sctp_fs_close_fd(assoc.network_fd);
                    assoc.network_fd = -1;
                    assoc.open = false;
                    assoc.up = false;
                }
            }
        }
    }
    ::close(listen_fd);
    ::unlink(path.c_str());
    return 0;
}
