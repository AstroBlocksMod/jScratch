/*
 * Sound decoders for the runtime, compiled to WebAssembly and then to JVM
 * bytecode with Chicory (see runtime/build.gradle.kts). The JDK only plays
 * PCM WAV, so MP3 and every WAV encoding go through dr_mp3 and dr_wav here.
 *
 * Each format has the same three functions. open takes the file bytes, which
 * must stay allocated until close, and an info array of three uint32 values
 * that it fills with the channel count, the sample rate and the frame count.
 * It returns a decoder, or 0 when the file cannot be decoded. read decodes up
 * to the given number of frames as interleaved 16-bit samples and returns how
 * many it decoded, 0 at the end. close frees the decoder. The caller allocates
 * and frees the file bytes, the info array and the sample buffer with
 * jscratch_alloc and jscratch_free.
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

EXPORT("jscratch_mp3_open")
drmp3* jscratch_mp3_open(const void* data, uint32_t size, uint32_t* info) {
    drmp3* mp3 = malloc(sizeof(drmp3));
    if (mp3 == NULL) return 0;
    if (!drmp3_init_memory(mp3, data, size, NULL)) {
        free(mp3);
        return 0;
    }
    /* Counting uses the Info tag when there is one and otherwise walks the frame headers without
       decoding them. Either way it ends back at the start. */
    drmp3_uint64 frames = drmp3_get_pcm_frame_count(mp3);
    if (frames == 0 || frames > UINT32_MAX) {
        drmp3_uninit(mp3);
        free(mp3);
        return 0;
    }
    info[0] = mp3->channels;
    info[1] = mp3->sampleRate;
    info[2] = (uint32_t)frames;
    return mp3;
}

EXPORT("jscratch_mp3_read")
uint32_t jscratch_mp3_read(drmp3* mp3, int16_t* out, uint32_t frames) {
    return (uint32_t)drmp3_read_pcm_frames_s16(mp3, frames, out);
}

EXPORT("jscratch_mp3_close")
void jscratch_mp3_close(drmp3* mp3) {
    drmp3_uninit(mp3);
    free(mp3);
}

EXPORT("jscratch_wav_open")
drwav* jscratch_wav_open(const void* data, uint32_t size, uint32_t* info) {
    drwav* wav = malloc(sizeof(drwav));
    if (wav == NULL) return 0;
    if (!drwav_init_memory(wav, data, size, NULL)) {
        free(wav);
        return 0;
    }
    if (wav->totalPCMFrameCount == 0 || wav->totalPCMFrameCount > UINT32_MAX) {
        drwav_uninit(wav);
        free(wav);
        return 0;
    }
    info[0] = wav->channels;
    info[1] = wav->sampleRate;
    info[2] = (uint32_t)wav->totalPCMFrameCount;
    return wav;
}

EXPORT("jscratch_wav_read")
uint32_t jscratch_wav_read(drwav* wav, int16_t* out, uint32_t frames) {
    return (uint32_t)drwav_read_pcm_frames_s16(wav, frames, out);
}

EXPORT("jscratch_wav_close")
void jscratch_wav_close(drwav* wav) {
    drwav_uninit(wav);
    free(wav);
}
