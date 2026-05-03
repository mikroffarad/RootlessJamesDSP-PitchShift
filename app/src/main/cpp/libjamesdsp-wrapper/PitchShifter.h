#pragma once

#include <cmath>
#include <cstring>
#include <vector>
#include <rubberband/RubberBandStretcher.h>

class PitchShifter {
public:
    explicit PitchShifter(double sampleRate, int channels = 2);
    ~PitchShifter();

    void setPitch(double octaves, double semitones, double cents);
    void updateSampleRate(double sampleRate);

    // Process interleaved stereo audio in-place.
    // For Int16: converts to float internally.
    void processInterleaved(float* buf, int frames);
    void processInterleaved(short* buf, int frames);

    bool isActive() const { return std::abs(pitchScale - 1.0) > 1e-6; }

private:
    void recreateStretcher();
    void ensureBuffers(int frames);

    RubberBand::RubberBandStretcher* stretcher = nullptr;
    double sampleRate;
    int channels;
    double pitchScale = 1.0;

    // Per-channel deinterleave buffers
    float** inBufs  = nullptr;
    float** outBufs = nullptr;
    int bufCapacity = 0;

    // Scratch buffer for Int16 conversion
    std::vector<float> floatScratch;
};
