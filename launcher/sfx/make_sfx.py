# Synthesises the launcher's navigation and select sounds into res/raw (16-bit mono WAV).
# Usage: python3 -I launcher/sfx/make_sfx.py launcher/app/src/main/res/raw
#
# A soft wood block (two inharmonic modes and a filtered mallet click, with a short room tail) over a light
# sub-bass that slides from 95 Hz down to 55 Hz. 48 kHz on purpose: it is the TV output's own rate, so the
# sound needs no resampling and can play on the low-latency fast mixer (measured 99 ms instead of 163 ms
# at 44.1 kHz on a MediaTek Google TV). Change RATE if your TV's output runs at another rate.
import math, random, struct, sys, wave

RATE = 48000
out = sys.argv[1]
random.seed(7)


def write(name, samples, gain):
    peak = max(abs(s) for s in samples) or 1
    with wave.open(f"{out}/{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(s / peak * gain * 32767)) for s in samples))


def tone(ms, parts, attack_ms=0.6, noise=0.0, noise_tau=1.0):
    """parts: (start Hz, end Hz, amplitude, decay time constant in ms)"""
    n = int(RATE * ms / 1000)
    phase = [0.0] * len(parts)
    samples = []
    for i in range(n):
        t = i / RATE * 1000
        s = 0.0
        for k, (f0, f1, amp, tau) in enumerate(parts):
            phase[k] += 2 * math.pi * (f0 + (f1 - f0) * min(1.0, t / ms)) / RATE
            s += amp * math.sin(phase[k]) * math.exp(-t / tau)
        s += noise * (random.random() * 2 - 1) * math.exp(-t / noise_tau)
        samples.append(s * min(1.0, t / attack_ms))
    return samples


def lowpass(s, a):
    out_s, y = [], 0.0
    for x in s:
        y += a * (x - y); out_s.append(y)
    return out_s


def reverb(s, mix, tail_ms):
    n = len(s) + int(RATE * tail_ms / 1000)
    dry = s + [0.0] * (n - len(s))
    wet = [0.0] * n
    for delay_ms, fb in ((29.7, 0.55), (37.1, 0.5), (41.1, 0.46), (43.7, 0.42)):
        d = int(RATE * delay_ms / 1000); buf = [0.0] * n
        for i in range(n):
            buf[i] = dry[i] + (fb * buf[i - d] if i >= d else 0.0)
            wet[i] += buf[i] * 0.25
    wet = lowpass(wet, 0.25)
    return [x + mix * w for x, w in zip(dry, wet)]


def woodblock(f, ms=120):
    s = tone(ms, [(f, f * 0.97, 1.0, 22), (f * 2.76, f * 2.7, 0.35, 10)], attack_ms=0.8, noise=0.35, noise_tau=2.5)
    return reverb(lowpass(s, 0.30), 0.22, 160)


def sub(ms, f0=95, f1=55, tau=45):
    return tone(ms, [(f0, f1, 1.0, tau)], attack_ms=4)


def mix(a, b, gain_b):
    n = max(len(a), len(b)); a = a + [0.0] * (n - len(a)); b = b + [0.0] * (n - len(b))
    return [x + gain_b * y for x, y in zip(a, b)]


SUB = 0.6   # how much of the sinking low end to add under the wood block
write("nav", mix(woodblock(330), sub(180), SUB), 0.55)
write("select", mix(woodblock(330)[: int(RATE * 0.09)] + woodblock(495),
                    [0.0] * int(RATE * 0.09) + sub(260, 110, 55, 70), SUB), 0.55)
