#include "sctp_ring.h"

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <stdexcept>
#include <string>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace sctp::fstack {

class ShmRing {
public:
    static constexpr size_t bytes() {
        return sizeof(sctp_fs_ring_header_t)
                + (sizeof(sctp_fs_ring_slot_t) * SCTP_FS_RING_CAPACITY);
    }

    ShmRing(const std::string& path, bool create) : path_(path) {
        int flags = O_RDWR | (create ? O_CREAT : 0);
        fd_ = ::open(path.c_str(), flags, 0600);
        if (fd_ < 0) {
            throw std::runtime_error("open ring " + path + ": " + std::strerror(errno));
        }
        if (create && ::ftruncate(fd_, static_cast<off_t>(bytes())) != 0) {
            throw std::runtime_error("ftruncate ring");
        }
        map_ = ::mmap(nullptr, bytes(), PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
        if (map_ == MAP_FAILED) {
            throw std::runtime_error("mmap ring");
        }
        header_ = static_cast<sctp_fs_ring_header_t*>(map_);
        slots_ = reinterpret_cast<sctp_fs_ring_slot_t*>(
                static_cast<unsigned char*>(map_) + sizeof(sctp_fs_ring_header_t));
        if (create) {
            std::memset(map_, 0, bytes());
            header_->magic = SCTP_FS_RING_MAGIC;
            header_->version = SCTP_FS_RING_VERSION;
            header_->capacity = SCTP_FS_RING_CAPACITY;
            header_->slot_bytes = sizeof(sctp_fs_ring_slot_t);
            header_->eventfd = ::eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
        }
        if (header_->magic != SCTP_FS_RING_MAGIC || header_->version != SCTP_FS_RING_VERSION) {
            throw std::runtime_error("ring ABI mismatch");
        }
    }

    ~ShmRing() {
        if (map_ && map_ != MAP_FAILED) {
            ::munmap(map_, bytes());
        }
        if (fd_ >= 0) {
            ::close(fd_);
        }
    }

    ShmRing(const ShmRing&) = delete;
    ShmRing& operator=(const ShmRing&) = delete;

    bool offer(const sctp_fs_ring_slot_t& slot) {
        if (header_->tail - header_->head >= header_->capacity) {
            header_->backpressure = 1;
            return false;
        }
        slots_[header_->tail & (header_->capacity - 1)] = slot;
        header_->tail++;
        header_->backpressure = 0;
        if (header_->eventfd >= 0) {
            uint64_t one = 1;
            ::write(header_->eventfd, &one, sizeof(one));
        }
        return true;
    }

    bool poll(sctp_fs_ring_slot_t* out) {
        if (header_->head == header_->tail) {
            return false;
        }
        *out = slots_[header_->head & (header_->capacity - 1)];
        header_->head++;
        return true;
    }

    bool congested() const {
        return header_->backpressure != 0;
    }

    const std::string& path() const { return path_; }

private:
    std::string path_;
    int fd_ = -1;
    void* map_ = nullptr;
    sctp_fs_ring_header_t* header_ = nullptr;
    sctp_fs_ring_slot_t* slots_ = nullptr;
};

} // namespace sctp::fstack
