# Android foreground audio

The Android audio adapter uses the operating system's maintained Opus encoder
and decoder through `MediaCodec`. It captures mono signed 16-bit PCM at 48 kHz,
960 samples per frame (20 ms), with a requested 24 kbit/s codec payload bitrate.
This bitrate excludes application/TLS/YUME/TCP headers, handshakes, feedback and
link overhead. Devices without the required codec or microphone fail the audio
attempt. There is no PCM transmission or cryptographic fallback.

The user first establishes the authenticated call and accepts the contact.
Start audio requests microphone permission, starts a nonsticky microphone
foreground service and negotiates [content envelope 1](https://github.com/FixCraft-Inc/ito/blob/main/docs/CONTENT_CHANNEL.md).
Capture begins only when both HELLOs agree. Permission refusal starts no audio.
Capture/playback setup, route or focus failure cancels the call. Incoming HELLO
alone never starts microphone capture. Microphone mute replaces captured PCM
with silence before encoding. The UI provides default-route/speaker selection
and hangup. The private foreground notification exposes only a generic call
label and an End action, without contact names or content.

The adapter requests transient voice-communication audio focus and uses
`MODE_IN_COMMUNICATION`. Losing focus, including a transient interruption,
ends the attempt rather than silently resuming capture. It uses Android's
communication-device route on API 31+, and speakerphone selection on older
versions. Android remains responsible for wired/Bluetooth device behavior.
The adapter restores the prior mode/speaker setting and relinquishes its
communication-device override and focus on shutdown.

One capture worker owns the encoder and one playout worker owns the decoder.
Each sends only fresh audio. Backpressure drops the current frame rather than
retrying it. Sustained refusal for over 500 ms ends the attempt. The receive
queue holds at most six packets, drops and clears the oldest when full, and
refuses speech over 120 ms late against its capture sample index, anchored to
the first received packet's local monotonic time. Decoder output is bounded
to one mono 20 ms PCM frame. The platform decoder handles codec malformed-input
errors; errors end the call. Devices/codecs close after their workers finish.
One device owner retains partial setup and cleanup across Service destruction.
New attempts remain refused until setup, worker joins and old-device callbacks
have settled; Service destruction does not cancel the sole cleanup operation.
No audio recording or audio history is persisted.

This remains an ordered TCP-based carrier. Local queue deadlines cannot remove
the first packet's unknown delay in TCP, TLS, the relay or Android audio buffers.
Later sample deadlines discard speech delayed relative to that initial anchor.
There is no loss concealment or unreliable carrier claim. YTP ordering and
authentication remain unchanged. Economy/Emergency remain stored preferences
that Settings does not offer, pending the separate I6 adaptation and
total-wire/intelligibility tests. Settings states the fixed audio quality.

An active foreground audio call may continue when the Activity leaves the
screen. Without the opt-in messaging service, an ordinary data/test call stops
on backgrounding. App lock
locks local access on backgrounding while an already authorized media session
retains only its existing native/device borrowers. Ending that foreground call
while the Activity is hidden also stops its retained transport connection
unless the messaging service still owns it.
New calls, messages, imports
and key access remain refused while locked. If history cannot be written while
locked or storage is unavailable, the end view states that it was not saved.
The service is nonsticky: process death stops audio and does not reconnect or
restart recording. Rotation preserves the process call owner.

The [messaging lifecycle](MESSAGING_LIFECYCLE.md) owns incoming ringing and
opt-in background reachability. Answering and starting audio require foreground
user interaction. There is no push registration or automatic call restart.
Device tests must separately establish sleeping-phone reachability,
intelligibility, end-to-end delay, focus/routing, handover, battery and cleanup.
Codec execution, debug APK packaging and emulator callbacks do not qualify
physical audio or voice readiness. ARM64 runtime, cold allocation/resource
qualification, minified JNI and signed release remain independent gates.
