/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "bytes.hpp"

#include <openssl/crypto.h>

namespace ito::call {

void wipe(void* data, std::size_t size) noexcept {
    if (data != nullptr && size != 0U) OPENSSL_cleanse(data, size);
}

void SecretBytes::reserve(std::size_t capacity) {
    if (capacity <= bytes_.capacity()) return;
    std::vector<Byte> larger;
    larger.reserve(capacity);
    larger.assign(bytes_.begin(), bytes_.end());
    clear();
    bytes_ = std::move(larger);
}

void SecretBytes::append(ByteView source) {
    if (bytes_.size() + source.size() > bytes_.capacity()) {
        reserve(std::max(bytes_.size() + source.size(), 2U * bytes_.size()));
    }
    bytes_.insert(bytes_.end(), source.begin(), source.end());
}

void SecretBytes::clear() noexcept {
    // Wipe the whole allocation, not only the live bytes, because erased
    // tails of earlier contents stay in capacity.
    bytes_.resize(bytes_.capacity());
    wipe(bytes_.data(), bytes_.size());
    bytes_.clear();
}

bool constant_time_equal(ByteView a, ByteView b) noexcept {
    return a.size() == b.size() &&
           CRYPTO_memcmp(a.data(), b.data(), a.size()) == 0;
}

void Writer::u16(std::uint16_t value) {
    u8(static_cast<std::uint8_t>(value >> 8U));
    u8(static_cast<std::uint8_t>(value));
}

void Writer::u32(std::uint32_t value) {
    u16(static_cast<std::uint16_t>(value >> 16U));
    u16(static_cast<std::uint16_t>(value));
}

void Writer::u64(std::uint64_t value) {
    u32(static_cast<std::uint32_t>(value >> 32U));
    u32(static_cast<std::uint32_t>(value));
}

bool Reader::take(std::size_t size, ByteView& value) noexcept {
    if (failed_ || size > input_.size() - offset_) {
        failed_ = true;
        return false;
    }
    value = input_.subspan(offset_, size);
    offset_ += size;
    return true;
}

bool Reader::u8(std::uint8_t& value) noexcept {
    ByteView view;
    if (!take(1U, view)) return false;
    value = view[0];
    return true;
}

bool Reader::u16(std::uint16_t& value) noexcept {
    ByteView view;
    if (!take(2U, view)) return false;
    value = static_cast<std::uint16_t>((view[0] << 8U) | view[1]);
    return true;
}

bool Reader::u32(std::uint32_t& value) noexcept {
    ByteView view;
    if (!take(4U, view)) return false;
    value = 0U;
    for (const Byte byte : view) value = (value << 8U) | byte;
    return true;
}

bool Reader::u64(std::uint64_t& value) noexcept {
    ByteView view;
    if (!take(8U, view)) return false;
    value = 0U;
    for (const Byte byte : view) value = (value << 8U) | byte;
    return true;
}

bool Reader::literal(ByteView expected) noexcept {
    ByteView view;
    if (!take(expected.size(), view)) return false;
    if (!std::equal(view.begin(), view.end(), expected.begin())) {
        failed_ = true;
        return false;
    }
    return true;
}

}  // namespace ito::call
