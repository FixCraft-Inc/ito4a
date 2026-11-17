# Android foreground audio

The Android audio adapter uses the operating system's maintained Opus encoder
and decoder through `MediaCodec`. It captures mono signed 16-bit PCM at 48 kHz,
960 samples per frame (20 ms), with a requested 24 kbit/s codec payload bitrate.
This bitrate excludes application/TLS/YUME/TCP headers, handshakes, feedback and
link overhead. Devices without the required codec or microphone fail the audio
attempt. There is no PCM transmission or cryptographic fallback.

Calling and answering start audio. Call and Answer first ask Android for the
microphone. A refusal starts nothing, and an unanswered incoming call keeps
ringing until its own deadline. An attempt that starts while Ito is on screen
starts a nonsticky microphone foreground service with it, so the call keeps its
connection and microphone access when the person leaves the app. The service
requests [content envelope 1](https://github.com/FixCraft-Inc/ito/blob/main/docs/CONTENT_CHANNEL.md)
for that attempt while app access is available. Negotiation begins only after
both proofs and media authorization, and capture begins only when both HELLOs
agree. A peer that does not answer HELLO within five seconds ends the call. The
Linux `ito-call` test endpoint exchanges raw DATA and never answers, so its
calls with the app end there. Capture/playback setup or focus failure cancels
the call. Incoming HELLO alone never starts microphone capture. Microphone mute
replaces captured PCM with silence before encoding. If Android revokes the
permission during a call, the call screen asks for it again. The private
foreground notification exposes only a generic call label and an End action,
without contact names or content.

The adapter requests transient voice-communication audio focus and uses
`MODE_IN_COMMUNICATION`. Losing focus, including a transient interruption,
ends the attempt rather than silently resuming capture.

The call screen lists only the devices Android reports for the call: the
phone's earpiece and speaker, wired and USB headsets, Bluetooth and hearing
aids. A headset carries both playback and its microphone. With two choices the
audio button switches between them, and with more it opens the list. The list
follows devices as they connect and disconnect. On API 31+ the adapter selects
Android's communication device. Before that, a plugged-in headset replaces the
earpiece, the speaker uses speakerphone, and Bluetooth uses an SCO link started
only when chosen. A device Android refuses leaves the route unchanged and the
call running. On shutdown the adapter restores the prior mode and speaker
setting, stops its SCO link and releases its communication-device override and
focus.

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

A call keeps running when the Activity leaves the screen, from its first
phase, because its foreground service starts with it. The call screen can also
be minimized inside Ito, leaving chats usable with a bar that returns to the
call. Without that service or the opt-in messaging service, an ordinary
data/test call stops on backgrounding. App lock
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
opt-in background reachability. Answering, and so starting audio, requires
foreground user interaction. There is no push registration or automatic call restart.
Device tests must separately establish sleeping-phone reachability,
intelligibility, end-to-end delay, focus/routing, handover, battery and cleanup.
Codec execution, debug APK packaging and emulator callbacks do not qualify
physical audio or voice readiness. ARM64 runtime, cold allocation/resource
qualification, minified JNI and signed release remain independent gates.
