"""Turns a clean 48 kHz mono f32 song into what a phone microphone 50 cm from a speaker hears.

Speaker: no deep bass, soft top. Room: reverb with RT60 0.5 s, direct sound 3 dB above it.
Level: music at a given RMS in dBFS, over a fixed noise floor (mic self-noise and room).
"""
import sys
import numpy as np
from scipy import signal

SR = 48_000


def mic(x: np.ndarray, rms_db: float, seed: int = 1) -> np.ndarray:
    rng = np.random.default_rng(seed)
    hp = signal.butter(2, 120, "highpass", fs=SR, output="sos")
    lp = signal.butter(2, 11_000, "lowpass", fs=SR, output="sos")
    y = signal.sosfilt(lp, signal.sosfilt(hp, x))

    t = np.arange(int(0.8 * SR)) / SR
    tail = rng.standard_normal(len(t)) * np.exp(-6.9 * t / 0.5)
    tail[: int(0.003 * SR)] = 0
    tail = signal.sosfilt(signal.butter(1, 5_000, "lowpass", fs=SR, output="sos"), tail)
    tail *= np.sqrt(10 ** (-3 / 10) / np.sum(tail**2))
    ir = tail
    ir[0] = 1.0
    y = signal.fftconvolve(y, ir)[: len(x)]

    y *= 10 ** (rms_db / 20) / np.sqrt(np.mean(y**2))
    white = rng.standard_normal(len(y))
    pink = signal.lfilter([0.049922, -0.095993, 0.050613, -0.004408], [1, -2.494956, 2.017265, -0.522189], white)
    pink *= 10 ** (-66 / 20) / np.sqrt(np.mean(pink**2))
    return (y + pink).astype(np.float32)


if __name__ == "__main__":
    src, dst, level = sys.argv[1], sys.argv[2], float(sys.argv[3])
    mic(np.fromfile(src, dtype=np.float32).astype(np.float64), level).tofile(dst)
