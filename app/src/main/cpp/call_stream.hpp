/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The one seam between Ito's transport adapter (ito_jni.cpp), which owns a
// connection's libyume endpoint and its streams, and the call adapter
// (ito_call_jni.cpp), which runs calls and carries sealed mailbox items over
// those streams. The connection run serializes their I/O and owns closure.

#pragma once

#include <jni.h>
#include <yume/yume.h>

namespace ito::jni {

// The libyume stream behind an ItoNative call-stream handle, or null for a
// zero handle. The transport adapter keeps it open until closeCallStream,
// which the Kotlin owner calls only after the call run that borrows it has
// returned.
yume_stream* call_stream(jlong handle) noexcept;

}  // namespace ito::jni
