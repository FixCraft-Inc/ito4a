/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// Byte vocabulary shared by the codecs: fixed arrays for keys and digests,
// a wiping buffer for secrets, and bounded big-endian readers and writers.
// The reader never reads past its view and reports failure as a value, so
// parsers stay allocation free and exception free.

#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>
#include <utility>
#include <vector>

#include "limits.hpp"

namespace ito::call {

using Byte = std::uint8_t;
using ByteView = std::span<const Byte>;
using Key = std::array<Byte, kKeyBytes>;
using Hash = std::array<Byte, kHashBytes>;
using Id = std::array<Byte, kIdBytes>;
using Signature = std::array<Byte, kSignatureBytes>;

// Overwrites memory in a way the optimizer may not remove. Best effort: it
// does not reach copies the allocator, kernel or other code made.
void wipe(void* data, std::size_t size) noexcept;

// A fixed-size secret that wipes itself. Copying is explicit through
// assign() so secrets are not duplicated by accident.
template <std::size_t Size>
class SecretArray final {
public:
    SecretArray() = default;
    SecretArray(const SecretArray&) = delete;
    SecretArray& operator=(const SecretArray&) = delete;
    // A move leaves the source wiped, so only one live copy remains.
    SecretArray(SecretArray&& other) noexcept : bytes_(other.bytes_) {
        wipe(other.bytes_.data(), other.bytes_.size());
    }
    SecretArray& operator=(SecretArray&& other) noexcept {
        if (this != &other) {
            bytes_ = other.bytes_;
            wipe(other.bytes_.data(), other.bytes_.size());
        }
        return *this;
    }
    ~SecretArray() { wipe(bytes_.data(), bytes_.size()); }

    void assign(std::span<const Byte, Size> source) noexcept {
        std::copy(source.begin(), source.end(), bytes_.begin());
    }
    [[nodiscard]] Byte* data() noexcept { return bytes_.data(); }
    [[nodiscard]] const Byte* data() const noexcept { return bytes_.data(); }
    [[nodiscard]] std::span<const Byte, Size> view() const noexcept {
        return std::span<const Byte, Size>(bytes_);
    }
    [[nodiscard]] static constexpr std::size_t size() noexcept { return Size; }

private:
    std::array<Byte, Size> bytes_{};
};

using Psk = SecretArray<kPskBytes>;
using Seed = SecretArray<kKeyBytes>;

// A growable secret buffer that wipes its storage on release, on every
// reallocation it causes through reserve(), and on clear().
class SecretBytes final {
public:
    SecretBytes() = default;
    explicit SecretBytes(ByteView source) { append(source); }
    SecretBytes(const SecretBytes&) = delete;
    SecretBytes& operator=(const SecretBytes&) = delete;
    SecretBytes(SecretBytes&& other) noexcept
        : bytes_(std::move(other.bytes_)) {}
    SecretBytes& operator=(SecretBytes&& other) noexcept {
        if (this != &other) {
            clear();
            bytes_ = std::move(other.bytes_);
        }
        return *this;
    }
    ~SecretBytes() { clear(); }

    // Reserves first so that append never reallocates a secret silently.
    void reserve(std::size_t capacity);
    void append(ByteView source);
    void clear() noexcept;
    [[nodiscard]] const Byte* data() const noexcept { return bytes_.data(); }
    [[nodiscard]] Byte* data() noexcept { return bytes_.data(); }
    [[nodiscard]] std::size_t size() const noexcept { return bytes_.size(); }
    [[nodiscard]] bool empty() const noexcept { return bytes_.empty(); }
    [[nodiscard]] ByteView view() const noexcept { return bytes_; }

private:
    std::vector<Byte> bytes_;
};

[[nodiscard]] bool constant_time_equal(ByteView a, ByteView b) noexcept;

// Wipes a plain vector's bytes when it goes out of scope, so a throw while
// they are copied into a secret owner leaves no plaintext in freed memory.
// The vector must not reallocate while guarded: reserve its size first.
class WipeOnExit final {
public:
    explicit WipeOnExit(std::vector<Byte>& bytes) noexcept : bytes_(bytes) {}
    WipeOnExit(const WipeOnExit&) = delete;
    WipeOnExit& operator=(const WipeOnExit&) = delete;
    ~WipeOnExit() { wipe(bytes_.data(), bytes_.size()); }

private:
    std::vector<Byte>& bytes_;
};

// Appends big-endian integers and raw bytes to a vector the caller sized.
class Writer final {
public:
    explicit Writer(std::vector<Byte>& out) noexcept : out_(out) {}
    void u8(std::uint8_t value) { out_.push_back(value); }
    void u16(std::uint16_t value);
    void u32(std::uint32_t value);
    void u64(std::uint64_t value);
    void bytes(ByteView value) {
        out_.insert(out_.end(), value.begin(), value.end());
    }
    void text(std::string_view value) {
        out_.insert(out_.end(), value.begin(), value.end());
    }

private:
    std::vector<Byte>& out_;
};

// Reads big-endian fields from a bounded view. A failed read latches: every
// later read fails too, and ok() reports it once at the end of a parse.
class Reader final {
public:
    explicit Reader(ByteView input) noexcept : input_(input) {}

    [[nodiscard]] bool u8(std::uint8_t& value) noexcept;
    [[nodiscard]] bool u16(std::uint16_t& value) noexcept;
    [[nodiscard]] bool u32(std::uint32_t& value) noexcept;
    [[nodiscard]] bool u64(std::uint64_t& value) noexcept;
    template <std::size_t Size>
    [[nodiscard]] bool fixed(std::array<Byte, Size>& value) noexcept {
        ByteView view;
        if (!take(Size, view)) return false;
        std::copy(view.begin(), view.end(), value.begin());
        return true;
    }
    // A borrowed view of the next size bytes.
    [[nodiscard]] bool take(std::size_t size, ByteView& value) noexcept;
    [[nodiscard]] bool literal(ByteView expected) noexcept;

    [[nodiscard]] std::size_t consumed() const noexcept { return offset_; }
    [[nodiscard]] std::size_t remaining() const noexcept {
        return failed_ ? 0U : input_.size() - offset_;
    }
    [[nodiscard]] bool at_end() const noexcept {
        return !failed_ && offset_ == input_.size();
    }
    [[nodiscard]] bool ok() const noexcept { return !failed_; }

private:
    ByteView input_;
    std::size_t offset_{0U};
    bool failed_{false};
};

[[nodiscard]] inline ByteView as_bytes(std::string_view text) noexcept {
    return {reinterpret_cast<const Byte*>(text.data()), text.size()};
}

}  // namespace ito::call
