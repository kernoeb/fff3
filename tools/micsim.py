"""Turns a clean 48 kHz mono f32 song into what a phone microphone 50 cm from a speaker hears.

Speaker: no deep bass, soft top. Room: reverb with RT60 0.5 s, direct sound 3 dB above it.
Level: music at a given RMS in dBFS, over a fixed noise floor (mic self-noise and room).
Optional arguments change the speaker high-pass, the RT60 and the direct-to-reverb ratio,
to check results on a second room that tuning never saw.
"""
import sys
import numpy as np
from scipy import signal

SR = 48_000


def mic(x: np.ndarray, rms_db: float, seed: int = 1, highpass_hz: float = 120, rt60: float = 0.5,
        direct_db: float = 3) -> np.ndarray:
    rng = np.random.default_rng(seed)
    hp = signal.butter(2, highpass_hz, "highpass", fs=SR, output="sos")
    lp = signal.butter(2, 11_000, "lowpass", fs=SR, output="sos")
    y = signal.sosfilt(lp, signal.sosfilt(hp, x))

    t = np.arange(int(max(0.8, 1.6 * rt60) * SR)) / SR
    tail = rng.standard_normal(len(t)) * np.exp(-6.9 * t / rt60)
    tail[: int(0.003 * SR)] = 0
    tail = signal.sosfilt(signal.butter(1, 5_000, "lowpass", fs=SR, output="sos"), tail)
    tail *= np.sqrt(10 ** (-direct_db / 10) / np.sum(tail**2))
    ir = tail
    ir[0] = 1.0
    y = signal.fftconvolve(y, ir)[: len(x)]

    y *= 10 ** (rms_db / 20) / np.sqrt(np.mean(y**2))
    white = rng.standard_normal(len(y))
    pink = signal.lfilter([0.049922, -0.095993, 0.050613, -0.004408], [1, -2.494956, 2.017265, -0.522189], white)
    pink *= 10 ** (-66 / 20) / np.sqrt(np.mean(pink**2))
    return (y + pink).astype(np.float32)


if __name__ == "__main__":
    # Usage: micsim.py in.f32 out.f32 level_db [highpass_hz rt60_s direct_db seed]
    src, dst, level = sys.argv[1], sys.argv[2], float(sys.argv[3])
    extra = [float(a) for a in sys.argv[4:]]
    options = dict(zip(["highpass_hz", "rt60", "direct_db", "seed"], extra))
    if "seed" in options:
        options["seed"] = int(options["seed"])
    mic(np.fromfile(src, dtype=np.float32).astype(np.float64), level, **options).tofile(dst)
