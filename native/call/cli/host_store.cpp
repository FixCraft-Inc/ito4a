/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "host_store.hpp"

#include <ito/call.h>

#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <stdexcept>

namespace ito::cli {
namespace {

constexpr const char* kSnapshot = "snapshot";
constexpr const char* kTemporary = "snapshot.tmp";
constexpr const char* kLock = "lock";
constexpr std::size_t kMaxSnapshot = 1024U * 1024U;

class Descriptor final {
public:
    explicit Descriptor(int value) noexcept : value_(value) {}
    Descriptor(const Descriptor&) = delete;
    Descriptor& operator=(const Descriptor&) = delete;
    ~Descriptor() {
        if (value_ >= 0) (void)::close(value_);
    }
    [[nodiscard]] int get() const noexcept { return value_; }

private:
    int value_;
};

[[nodiscard]] bool write_all(int descriptor, const std::uint8_t* bytes,
                             std::size_t size) {
    while (size != 0U) {
        const ssize_t written = ::write(descriptor, bytes, size);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return false;
        bytes += written;
        size -= static_cast<std::size_t>(written);
    }
    return true;
}

}  // namespace

HostStore::HostStore(std::string directory) : directory_(std::move(directory)) {
    if (::mkdir(directory_.c_str(), 0700) != 0 && errno != EEXIST) {
        throw std::runtime_error("cannot create the store directory");
    }
    struct stat info{};
    if (::lstat(directory_.c_str(), &info) != 0 || !S_ISDIR(info.st_mode) ||
        info.st_uid != ::geteuid() || (info.st_mode & 0077) != 0) {
        throw std::runtime_error(
            "the store directory must be a private 0700 directory");
    }
    const std::string lock = directory_ + "/" + kLock;
    lock_ =
        ::open(lock.c_str(), O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (lock_ < 0) throw std::runtime_error("cannot open the store lock");
    if (::flock(lock_, LOCK_EX | LOCK_NB) != 0) {
        ::close(lock_);
        throw std::runtime_error("another ito-call is using this store");
    }
}

HostStore::~HostStore() {
    ::close(lock_);
}

std::vector<std::uint8_t> HostStore::load() const {
    const std::string path = directory_ + "/" + kSnapshot;
    const int raw =
        ::open(path.c_str(), O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (raw < 0) {
        if (errno == ENOENT) return {};
        throw std::runtime_error("the snapshot is unreadable");
    }
    const Descriptor descriptor(raw);
    struct stat info{};
    if (::fstat(raw, &info) != 0 || !S_ISREG(info.st_mode) ||
        info.st_uid != ::geteuid() || (info.st_mode & 0077) != 0 ||
        static_cast<std::size_t>(info.st_size) > kMaxSnapshot) {
        throw std::runtime_error("the snapshot is not a private regular file");
    }
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(info.st_size));
    std::size_t offset = 0U;
    while (offset < bytes.size()) {
        const ssize_t got =
            ::read(raw, bytes.data() + offset, bytes.size() - offset);
        if (got < 0 && errno == EINTR) continue;
        if (got <= 0) {
            if (!bytes.empty()) explicit_bzero(bytes.data(), bytes.size());
            throw std::runtime_error("the snapshot is unreadable");
        }
        offset += static_cast<std::size_t>(got);
    }
    return bytes;
}

int HostStore::persist(const std::uint8_t* bytes, std::size_t size) const {
    const std::string temporary = directory_ + "/" + kTemporary;
    const std::string target = directory_ + "/" + kSnapshot;
    (void)::unlink(temporary.c_str());
    {
        const Descriptor file(
            ::open(temporary.c_str(),
                   O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
        if (file.get() < 0) return ITO_PERSIST_NOT_COMMITTED;
        if (!write_all(file.get(), bytes, size) || ::fsync(file.get()) != 0) {
            (void)::unlink(temporary.c_str());
            return ITO_PERSIST_NOT_COMMITTED;
        }
    }
    if (::rename(temporary.c_str(), target.c_str()) != 0) {
        (void)::unlink(temporary.c_str());
        return ITO_PERSIST_NOT_COMMITTED;
    }
    // After the rename the new snapshot may be what storage holds even if
    // the directory sync fails, so that failure is ambiguous.
    const Descriptor directory(
        ::open(directory_.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (directory.get() < 0 || ::fsync(directory.get()) != 0) {
        return ITO_PERSIST_AMBIGUOUS;
    }
    return ITO_PERSIST_COMMITTED;
}

int host_store_persist(void* user_data, const std::uint8_t* snapshot,
                       std::size_t size) {
    try {
        return static_cast<const HostStore*>(user_data)->persist(snapshot,
                                                                 size);
    } catch (...) {
        return ITO_PERSIST_AMBIGUOUS;
    }
}

}  // namespace ito::cli
