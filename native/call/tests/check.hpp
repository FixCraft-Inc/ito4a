/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// Checks that stay active in optimized builds, and small helpers the core's
// tests share.

#pragma once

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

namespace ito::call::testing {

inline void check(bool passed, const char* condition, const char* file,
                  int line) {
    if (passed) return;
    std::fprintf(stderr, "%s:%d: check failed: %s\n", file, line, condition);
    std::exit(1);
}

inline std::vector<unsigned char> from_hex(const std::string& text) {
    std::vector<unsigned char> bytes;
    for (std::size_t index = 0; index + 1 < text.size(); index += 2) {
        bytes.push_back(static_cast<unsigned char>(
            std::stoul(text.substr(index, 2), nullptr, 16)));
    }
    return bytes;
}

// tests/vectors.txt: one `name hex` per line.
inline std::map<std::string, std::vector<unsigned char>> load_vectors(
    const char* path) {
    std::ifstream input(path);
    check(input.good(), "vectors file opens", __FILE__, __LINE__);
    std::map<std::string, std::vector<unsigned char>> result;
    std::string line;
    while (std::getline(input, line)) {
        std::istringstream fields(line);
        std::string name;
        std::string hex;
        fields >> name >> hex;
        if (!name.empty()) result[name] = from_hex(hex);
    }
    return result;
}

}  // namespace ito::call::testing

#define CHECK(condition) \
    ::ito::call::testing::check((condition), #condition, __FILE__, __LINE__)
