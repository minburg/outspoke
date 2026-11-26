#!/usr/bin/env python3
"""Generate dictated-digit WAV fixtures for DigitsBatteryRealAudioTest.

Synthesises each fixture with Piper (en_US-lessac-medium) and writes 16-bit PCM
mono WAVs into app/src/test/resources/audio/. Paused digit sequences are
synthesised word-by-word and concatenated with fixed silence gaps, mimicking
how a user dictates digit strings ("one, two, eight, eight, five").

Usage (from repo root):

    PYTHONPATH=$HOME/.local/lib/python3.14/site-packages python3.14 \
        devtools/gen_digit_fixtures.py [--voice /path/to/voice.onnx] [--out DIR]

Requires the `piper-tts` Python package. The Kotlin battery defines the
EXPECTED digit output for each fixture; this script defines the audio.
Keep the name lists in sync (DigitsBatteryRealAudioTest.CASES).
"""

import argparse
import math
import sys
import wave
from pathlib import Path

from piper import PiperVoice, SynthesisConfig

VOICE_DEFAULT = Path.home() / ".local/share/piper-voices/en_US-lessac-medium.onnx"
OUT_DEFAULT = Path("app/src/test/resources/audio")

# Speaker-diversity passes: the same digit strings rendered by other voices
# (different gender/accent/prosody) to probe ASR digit-confusion patterns.
# Each entry: (prefix, voice-file, cases). Voices other than the default are
# loaded on demand via --voice-dir.
VOICE_DIR_DEFAULT = Path.home() / ".local/share/piper-voices"
VOICE_PASSES = [
    ("amy",  "en_US-amy-medium.onnx",
     ["num-d-12885", "num-d-07001", "num-d-999", "num-d-8855", "num-d-2288",
      "num-d-countup12", "num-c-phone", "num-s-pizzas"]),
    ("ryan", "en_US-ryan-medium.onnx",
     ["num-d-12885", "num-d-07001", "num-d-999", "num-d-8855", "num-d-2288",
      "num-d-twelve-digits", "num-c-phone", "num-s-year"]),
]

# Mic-condition passes: the baseline voice degraded like a phone microphone.
# Each entry: (suffix, transform, cases). "noise" adds pink-ish noise at
# SNR_NOISE_DB; "tel" band-limits through 8 kHz (downsample → upsample).
NOISE_SNR_DB = 12.0
DEGRADED_PASSES = [
    ("nz",  "noise",
     ["num-d-12885", "num-d-07001", "num-d-999", "num-d-8855", "num-d-2288",
      "num-d-countup12", "num-d-twelve-digits"]),
    ("tel", "tel",
     ["num-d-12885", "num-d-07001", "num-d-999", "num-d-8855", "num-d-2288",
      "num-d-countup12", "num-c-phone", "num-s-pizzas"]),
]

# (fixture, kind, payload, length_scale, gap_seconds)
# kind "words": payload is the digit/word list, one clip per word, `gap` silence
# between clips. kind "text": payload is one sentence, continuous speech.
CASES = [
    # --- paused digit strings (user dictation style) ---
    ("num-d-12885.wav",        "words", ["one", "two", "eight", "eight", "five"],                        1.25, 0.30),
    ("num-d-07001.wav",        "words", ["zero", "seven", "zero", "zero", "one"],                        1.25, 0.30),
    ("num-d-999.wav",          "words", ["nine", "nine", "nine"],                                        1.25, 0.30),
    ("num-d-8855.wav",         "words", ["eight", "eight", "five", "five"],                              1.25, 0.30),
    ("num-d-2288.wav",         "words", ["two", "two", "eight", "eight"],                                1.25, 0.30),
    ("num-d-countup12.wav",    "words", ["one", "two", "three", "four", "five", "six",
                                         "seven", "eight", "nine", "ten", "eleven", "twelve"],           1.25, 0.30),
    ("num-d-twelve-digits.wav","words", ["zero", "one", "two", "three", "four", "five",
                                         "six", "seven", "eight", "nine", "zero", "one"],                1.25, 0.30),
    ("num-d-slow-12885.wav",   "words", ["one", "two", "eight", "eight", "five"],                        1.55, 0.35),
    ("num-d-fast-12885.wav",   "words", ["one", "two", "eight", "eight", "five"],                        0.90, 0.20),
    # --- continuous digit strings / numbers (single sentence synthesis) ---
    ("num-c-phone.wav",        "text",  "My number is five five five one two one two.",                  1.00, 0.00),
    ("num-c-countdown-fast.wav","text", "Ten nine eight seven six five four three two one.",             1.00, 0.00),
    ("num-c-ninety9.wav",      "text",  "Ninety nine nine.",                                             1.00, 0.00),
    ("num-c-room.wav",         "text",  "My room number is two zero four.",                              1.00, 0.00),
    # --- numbers inside sentences ---
    ("num-s-year.wav",         "text",  "I was born in nineteen eighty four.",                           1.00, 0.00),
    ("num-s-money.wav",        "text",  "The total is one thousand two hundred fifty dollars.",          1.00, 0.00),
    ("num-s-2024.wav",         "text",  "It is two thousand twenty four.",                               1.00, 0.00),
    ("num-s-decimal.wav",      "text",  "The price is ninety nine point nine five.",                     1.00, 0.00),
    ("num-s-time.wav",         "text",  "Meet me at three thirty.",                                      1.00, 0.00),
    ("num-s-hundred.wav",      "text",  "I need one hundred and one.",                                   1.00, 0.00),
    ("num-s-911.wav",          "text",  "Dial nine one one.",                                            1.00, 0.00),
    ("num-s-pizzas.wav",       "text",  "Order two eight eight pizzas.",                                 1.00, 0.00),
    ("num-s-zero.wav",         "text",  "Start at zero one five.",                                       1.00, 0.00),
    # --- single short utterances (short-utterance retry path) ---
    ("num-1-single7.wav",      "text",  "Seven.",                                                        1.00, 0.00),
    ("num-1-single0.wav",      "text",  "Zero.",                                                         1.00, 0.00),
]

GAIN = 0.85          # int16 full-scale headroom for piper float output
EDGE_FADE = 0.010    # raised-cosine fade per clip to avoid click artefacts at joins
LEAD_SILENCE = 0.30  # leading silence per fixture
TAIL_SILENCE = 0.40  # trailing silence per fixture


def synth_samples(voice: PiperVoice, text: str, length_scale: float) -> list[float]:
    cfg = SynthesisConfig(length_scale=length_scale)
    out: list[float] = []
    for chunk in voice.synthesize(text, syn_config=cfg):
        out.extend(chunk.audio_float_array)
    if not out:
        raise RuntimeError(f"piper produced no audio for {text!r}")
    return out


def fade(edges: list[float]) -> list[float]:
    n = max(1, int(EDGE_FADE * SAMPLE_RATE_HZ))
    for i in range(min(n, len(edges) // 2)):
        w = 0.5 - 0.5 * math.cos(math.pi * i / n)
        edges[i] *= w
        edges[-1 - i] *= w
    return edges


SAMPLE_RATE_HZ = 22050  # en_US-lessac-medium


def samples_to_int16(samples: list[float]) -> bytes:
    import struct
    clipped = (max(-1.0, min(1.0, s * GAIN)) for s in samples)
    return struct.pack(f"<{len(samples)}h", *(int(round(s * 32767)) for s in clipped))


def build(voice: PiperVoice, case) -> list[float]:
    _name, kind, payload, length_scale, gap = case
    samples: list[float] = [0.0] * int(LEAD_SILENCE * SAMPLE_RATE_HZ)
    if kind == "words":
        gap_sil = [0.0] * int(gap * SAMPLE_RATE_HZ)
        for i, word in enumerate(payload):
            if i:
                samples.extend(gap_sil)
            samples.extend(fade(synth_samples(voice, word.capitalize(), length_scale)))
    elif kind == "text":
        samples.extend(fade(synth_samples(voice, payload, length_scale)))
    else:
        raise ValueError(kind)
    samples.extend([0.0] * int(TAIL_SILENCE * SAMPLE_RATE_HZ))
    return samples


def write_wav(path: Path, samples: list[float]) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE_HZ)
        w.writeframes(samples_to_int16(samples))


def base_case(name: str):
    return next(c for c in CASES if c[0] == name + ".wav")


def transform_noise(samples: list[float], seed: int = 1234) -> list[float]:
    """Add low-passed (pink-ish) white noise at NOISE_SNR_DB relative to speech RMS."""
    import random
    rng = random.Random(seed)
    speech_rms = math.sqrt(sum(s * s for s in samples) / max(1, len(samples)))
    if speech_rms == 0.0:
        return samples
    target_rms = speech_rms / (10 ** (NOISE_SNR_DB / 20))
    out = list(samples)
    prev = 0.0
    acc = 0.0
    for i, s in enumerate(samples):
        white = rng.uniform(-1.0, 1.0)
        prev = 0.7 * prev + 0.3 * white          # one-pole lowpass → pink-ish
        acc += prev * prev
        out[i] = s + prev
    noise_rms = math.sqrt(acc / max(1, len(samples)))
    gain = target_rms / noise_rms if noise_rms else 0.0
    prev = 0.0
    for i, s in enumerate(samples):
        prev = 0.7 * prev + 0.3 * (out[i] - s)
        out[i] = s + prev * gain
    return out


def transform_tel(samples: list[float]) -> list[float]:
    """Telephone band: linear resample to 8 kHz and back (kills >4 kHz content)."""
    def interp(s, rate_in, rate_out):
        n_out = int(len(s) * rate_out / rate_in)
        out = []
        for i in range(n_out):
            x = i * rate_in / rate_out
            i0 = min(int(x), len(s) - 1)
            i1 = min(i0 + 1, len(s) - 1)
            w = x - i0
            out.append(s[i0] * (1 - w) + s[i1] * w)
        return out
    return interp(interp(samples, SAMPLE_RATE_HZ, 8000), 8000, SAMPLE_RATE_HZ)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--voice", type=Path, default=VOICE_DEFAULT)
    ap.add_argument("--voice-dir", type=Path, default=VOICE_DIR_DEFAULT)
    ap.add_argument("--out", type=Path, default=OUT_DEFAULT)
    ap.add_argument("--only", default=None, help="substring filter on fixture names")
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    voices = {"default": PiperVoice.load(args.voice)}
    base_samples: dict[str, list[float]] = {}

    def synth(name: str, voice_key: str) -> list[float]:
        key = (voice_key, name)
        if key not in base_samples:
            base_samples[key] = build(voices[voice_key], base_case(name))
        return base_samples[key]

    def emit(name: str, samples: list[float]) -> None:
        if args.only and args.only not in name:
            return
        out = args.out / name
        write_wav(out, samples)
        print(f"{name:34s} {len(samples) / SAMPLE_RATE_HZ:5.2f}s")

    # 1. baseline voice, all cases
    for case in CASES:
        name = case[0]
        emit(name, build(voices["default"], case))

    # 2. speaker-diversity passes (other voices)
    for prefix, voice_file, names in VOICE_PASSES:
        path = args.voice_dir / voice_file
        if not path.exists():
            print(f"SKIP voice pass {prefix}: {path} not found")
            continue
        voices[prefix] = PiperVoice.load(path)
        for name in names:
            emit(f"num-{prefix}-{name.removeprefix('num-')}.wav", synth(name, prefix))

    # 3. mic-condition passes (baseline voice, degraded)
    for suffix, kind, names in DEGRADED_PASSES:
        for name in names:
            s = synth(name, "default")
            s = transform_noise(s) if kind == "noise" else transform_tel(s)
            emit(f"{name}-{suffix}.wav", s)
    return 0


if __name__ == "__main__":
    sys.exit(main())
