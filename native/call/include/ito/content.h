/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */
#ifndef ITO_CONTENT_H
#define ITO_CONTENT_H

#include "call.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Application DATA envelope 1, independent of call profile/TLS/YUME versions.
 * See docs/CONTENT_CHANNEL.md. These codecs do no I/O or cryptography. The
 * caller must use an authenticated, consented call and enforce sequence order.
 * Decoded payload borrows input until the caller releases that input. */
#define ITO_CONTENT_HELLO 1u
#define ITO_CONTENT_AUDIO 2u
#define ITO_CONTENT_CHAT 3u
#define ITO_CONTENT_ACK 4u
#define ITO_CONTENT_HEADER_BYTES 16u
#define ITO_CONTENT_MAX_PAYLOAD 4112u

typedef struct ito_content_frame {
    uint32_t kind;
    uint32_t sequence;
    const uint8_t* payload;
    size_t payload_size;
} ito_content_frame;

ITO_CALL_API ito_status ito_content_encode(uint32_t kind, uint32_t sequence,
                                           const uint8_t* payload,
                                           size_t payload_size, uint8_t* out,
                                           size_t capacity,
                                           size_t* written) ITO_CALL_NOEXCEPT;
ITO_CALL_API ito_status ito_content_decode(const uint8_t* input, size_t size,
                                           ito_content_frame* out)
    ITO_CALL_NOEXCEPT;

#ifdef __cplusplus
}
#endif
#endif
