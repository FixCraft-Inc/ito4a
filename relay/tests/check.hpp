/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// A check that stays active in optimized builds, where NDEBUG removes assert.

#pragma once

#include <cstdio>
#include <cstdlib>

namespace ito::relay::testing {

inline void check(bool passed, const char* condition, const char* file,
                  int line) {
    if (passed) return;
    std::fprintf(stderr, "%s:%d: check failed: %s\n", file, line, condition);
    std::exit(1);
}

}  // namespace ito::relay::testing

#define CHECK(condition) \
    ::ito::relay::testing::check((condition), #condition, __FILE__, __LINE__)
