#include "PitchShifter.h"
#include <algorithm>
#include <stdint.h>

using namespace RubberBand;

static constexpr double kMinScale = 1e-6;

PitchShifter::PitchShifter(double sampleRate, int channels)
    : sampleRate(sampleRate), channels(channels)
{
    recreateStretcher();
}

PitchShifter::~PitchShifter()
{
    delete stretcher;
    if (inBufs) {
        for (int c = 0; c < channels; c++) {
            delete[] inBufs[c];
            delete[] outBufs[c];
        }
        delete[] inBufs;
        delete[] outBufs;
    }
}

void PitchShifter::setPitch(double octaves, double semitones, double cents)
{
    double totalSemitones = octaves * 12.0 + semitones + cents / 100.0;
    double newScale = std::pow(2.0, totalSemitones / 12.0);

    if (std::abs(newScale - pitchScale) < kMinScale)
        return;

    pitchScale = newScale;
    if (stretcher)
        stretcher->setPitchScale(pitchScale);
}

void PitchShifter::updateSampleRate(double newRate)
{
    if (std::abs(newRate - sampleRate) < 1.0)
        return;
    sampleRate = newRate;
    recreateStretcher();
}

void PitchShifter::recreateStretcher()
{
    delete stretcher;

    RubberBandStretcher::Options opts =
        RubberBandStretcher::OptionProcessRealTime  |
        RubberBandStretcher::OptionStretchPrecise   |
        RubberBandStretcher::OptionPitchHighSpeed   |  // R2 engine
        RubberBandStretcher::OptionChannelsTogether |
        RubberBandStretcher::OptionThreadingNever;

    stretcher = new RubberBandStretcher(
        static_cast<size_t>(sampleRate),
        static_cast<size_t>(channels),
        opts
    );
    stretcher->setTimeRatio(1.0);
    stretcher->setPitchScale(pitchScale);
    stretcher->setMaxProcessSize(8192);

    // Pre-fill with silence to absorb initial latency
    int latency = static_cast<int>(stretcher->getLatency());
    if (latency > 0 && channels > 0) {
        ensureBuffers(latency);
        for (int c = 0; c < channels; c++)
            std::memset(inBufs[c], 0, latency * sizeof(float));
        stretcher->process(const_cast<const float* const*>(inBufs), latency, false);
    }
}

void PitchShifter::ensureBuffers(int frames)
{
    if (frames <= bufCapacity)
        return;

    if (inBufs) {
        for (int c = 0; c < channels; c++) {
            delete[] inBufs[c];
            delete[] outBufs[c];
        }
        delete[] inBufs;
        delete[] outBufs;
    }

    bufCapacity = frames + 256;
    inBufs  = new float*[channels];
    outBufs = new float*[channels];
    for (int c = 0; c < channels; c++) {
        inBufs[c]  = new float[bufCapacity]();
        outBufs[c] = new float[bufCapacity]();
    }
}

void PitchShifter::processInterleaved(float* buf, int frames)
{
    if (!stretcher || frames <= 0)
        return;

    ensureBuffers(frames);

    // Deinterleave
    for (int i = 0; i < frames; i++)
        for (int c = 0; c < channels; c++)
            inBufs[c][i] = buf[i * channels + c];

    stretcher->process(const_cast<const float* const*>(inBufs), frames, false);

    // Drain as many frames as available; pad with zeros if fewer come back
    int available = stretcher->available();
    int toRetrieve = std::min(available, frames);

    if (toRetrieve > 0)
        stretcher->retrieve(outBufs, toRetrieve);

    // Reinterleave retrieved frames
    for (int i = 0; i < toRetrieve; i++)
        for (int c = 0; c < channels; c++)
            buf[i * channels + c] = outBufs[c][i];

    // Zero-fill any frames that weren't available yet (startup latency)
    for (int i = toRetrieve; i < frames; i++)
        for (int c = 0; c < channels; c++)
            buf[i * channels + c] = 0.0f;
}

void PitchShifter::processInterleaved(short* buf, int frames)
{
    if (!stretcher || frames <= 0)
        return;

    int samples = frames * channels;
    floatScratch.resize(samples);

    for (int i = 0; i < samples; i++)
        floatScratch[i] = buf[i] / 32768.0f;

    processInterleaved(floatScratch.data(), frames);

    for (int i = 0; i < samples; i++) {
        float v = floatScratch[i] * 32768.0f;
        if (v >  32767.0f) v =  32767.0f;
        if (v < -32768.0f) v = -32768.0f;
        buf[i] = static_cast<short>(v);
    }
}
