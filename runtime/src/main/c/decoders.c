/*
 * Sound decoders for the runtime, compiled to WebAssembly and then to JVM
 * bytecode with Chicory (see runtime/build.gradle.kts). The JDK only plays
 * PCM WAV, so MP3 and ADPCM WAV files are decoded here into 16-bit PCM.
 *
 * Every decode function takes the file bytes and an info array of three
 * uint32 values that it fills with the channel count, the sample rate and the
 * frame count. It returns the interleaved samples, or 0 when the file could
 * not be decoded. The caller frees both buffers with jscratch_free.
 */

#include <stdint.h>
#include <stdlib.h>

#define DR_MP3_IMPLEMENTATION
#define DR_MP3_NO_STDIO
#include "dr_mp3.h"

#define DR_WAV_IMPLEMENTATION
#define DR_WAV_NO_STDIO
#include "dr_wav.h"

#define EXPORT(name) __attribute__((export_name(name)))

EXPORT("jscratch_alloc")
void* jscratch_alloc(uint32_t size) {
    return malloc(size);
}

EXPORT("jscratch_free")
void jscratch_free(void* p) {
    free(p);
}

EXPORT("jscratch_decode_mp3")
int16_t* jscratch_decode_mp3(const void* data, uint32_t size, uint32_t* info) {
    drmp3_config config;
    drmp3_uint64 frames = 0;
    drmp3_int16* samples = drmp3_open_memory_and_read_pcm_frames_s16(data, size, &config, &frames, NULL);
    if (samples == NULL || frames > UINT32_MAX) {
        free(samples);
        return 0;
    }
    info[0] = config.channels;
    info[1] = config.sampleRate;
    info[2] = (uint32_t)frames;
    return samples;
}

EXPORT("jscratch_decode_wav")
int16_t* jscratch_decode_wav(const void* data, uint32_t size, uint32_t* info) {
    unsigned int channels = 0;
    unsigned int sampleRate = 0;
    drwav_uint64 frames = 0;
    drwav_int16* samples =
        drwav_open_memory_and_read_pcm_frames_s16(data, size, &channels, &sampleRate, &frames, NULL);
    if (samples == NULL || frames > UINT32_MAX) {
        free(samples);
        return 0;
    }
    info[0] = channels;
    info[1] = sampleRate;
    info[2] = (uint32_t)frames;
    return samples;
}
