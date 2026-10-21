# .bbz file format (version 1)

A `.bbz` file is the original audio file, unchanged, with the bass envelope that drives the vibration
motor stored in front of it. All integers are big-endian.

| Field         | Size            | Notes                                                      |
|---------------|-----------------|------------------------------------------------------------|
| magic         | 4 bytes         | ASCII `BBHZ`                                               |
| version       | u16             | `1`. Readers must refuse versions they don't know.         |
| hopMs         | u16             | Milliseconds per haptic frame. Currently always `20`.      |
| cutoffHz      | u16             | Low-pass cutoff used for the analysis (informational).     |
| reserved      | u16             | `0`                                                        |
| frameCount    | u32             | Number of haptic frames.                                   |
| audioLength   | u32             | Number of audio bytes at the end of the file.              |
| titleLen      | u16             | Followed by `titleLen` UTF-8 bytes.                        |
| extLen        | u8              | Followed by `extLen` ASCII bytes: the audio extension.     |
| levels        | frameCount bytes| Each `u8 = round(level / 1.5 * 255)`; level 1.0 is "loud". |
| crc32         | u32             | CRC-32 of every byte from `magic` to the end of `levels`.  |
| audio         | audioLength     | The original audio file, byte for byte.                    |

Frame `i` covers `i * hopMs` to `(i + 1) * hopMs` milliseconds of the song. A player turns the level
into a motor amplitude and, as a sharpness cue, the rise from the previous frame ("flux").

The reference implementation is `core/src/main/java/com/example/bassbuzz/HapticFile.kt`, with round-trip
and corruption tests in `core/src/test`.
