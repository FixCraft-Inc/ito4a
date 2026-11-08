/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The Linux endpoint's host test store: the core's plaintext snapshot in one
// owner-only file, replaced atomically. It is a test and development store.
// Unlike Android's Keystore-sealed store it does not encrypt the snapshot,
// so the directory's permissions are its only protection.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace ito::cli {

class HostStore final {
public:
    // Creates the directory 0700 when absent. Refuses links, other owners
    // and group or world access. Holds an exclusive lock on the store for
    // this object's life, so a second process cannot interleave snapshots
    // or overwrite a spent record with an older state.
    explicit HostStore(std::string directory);
    HostStore(const HostStore&) = delete;
    HostStore& operator=(const HostStore&) = delete;
    ~HostStore();

    // Reads the snapshot. An absent file is an empty snapshot. Throws
    // std::runtime_error with a fixed message for unreadable state.
    [[nodiscard]] std::vector<std::uint8_t> load() const;
    // Writes a private temporary file, syncs it and renames it over the
    // snapshot. Returns an ITO_PERSIST_* result.
    [[nodiscard]] int persist(const std::uint8_t* bytes,
                              std::size_t size) const;

private:
    std::string directory_;
    int lock_{-1};
};

// The C persistence callback for a HostStore.
int host_store_persist(void* user_data, const std::uint8_t* snapshot,
                       std::size_t size);

}  // namespace ito::cli
