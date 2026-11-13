#!/usr/bin/env python3
"""
'Ghost broadcast' clips for occult_foundry: a 1930s radio voice that can't be understood.

Source: FDR, Fireside Chat 1 'On the Banking Crisis' (12 March 1933) - a US federal work, public domain
(Wikimedia Commons). Not shipped; pass the downloaded .ogg as the first argument.

Processing:
  - the loud opening (first 44.5 s) is dropped
  - speech is cut into syllable-sized grains (100-220 ms), shuffled across the whole address and ~40%
    reversed, so no word longer than a syllable - names, places - survives; pitched down ~2 semitones
  - bursts of 1.5-4.5 s of 'speech' separated by 1-3 s gaps of static only
  - radio chain: AM band (300-2800 Hz), valve saturation, slow fading, static that rises as the signal
    fades, a drifting heterodyne whistle, a tuning sweep at the start of each clip, crackle
  - loudness-matched (EBU R128) with a soft limiter, like the other grove sounds

usage: python3 tools/gen_occult_radio.py <fdr_fireside_chat_1.ogg> [out_dir] [clips]
"""
import os, subprocess, sys, tempfile
import numpy as np
from scipy import signal
from scipy.io import wavfile

SRC = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else \
    'src/main/resources/wastelandvision_defaults/visions/occult_foundry/assets/occult/sounds/grove'
CLIPS = int(sys.argv[3]) if len(sys.argv) > 3 else 4
SR = 32000
rng = np.random.default_rng(1933)
TARGET_LUFS, CEIL_DB = -30.0, -14.0


def bp(x, lo, hi, order=2):
    b, a = signal.butter(order, [lo / (SR / 2), hi / (SR / 2)], 'band'); return signal.lfilter(b, a, x)


def lp(x, f, order=2):
    b, a = signal.butter(order, f / (SR / 2), 'low'); return signal.lfilter(b, a, x)


def load(path):
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', path, '-ac', '1', '-ar', str(SR), f.name], check=True)
        sr, x = wavfile.read(f.name); os.unlink(f.name)
    return x.astype(np.float64) / 32768


src = load(SRC)[int(44.5 * SR):]                   # drop the loud opening
src = src / (np.sqrt(np.mean(src ** 2)) + 1e-9) * 0.1
# voiced regions only: grains are taken where someone is actually speaking
env = np.sqrt(signal.lfilter(np.ones(400) / 400, 1, src ** 2))
voiced = np.where(env > np.percentile(env, 55))[0]


def grain():
    n = int(rng.uniform(0.10, 0.22) * SR)
    for _ in range(50):
        i = int(voiced[rng.integers(len(voiced))])
        if i + n < len(src): break
    g = src[i:i + n].copy()
    if rng.random() < 0.4: g = g[::-1]               # backwards syllables
    w = np.ones(n); f = int(0.015 * SR)
    w[:f] = np.linspace(0, 1, f); w[-f:] = np.linspace(1, 0, f)
    return g * w


def babble(sec):
    """Shuffled grains overlap-added into continuous 'speech', then pitched down ~2 semitones."""
    n = int(sec * SR); out = np.zeros(n + SR); pos = 0
    hop = int(0.012 * SR)
    while pos < n:
        g = grain(); out[pos:pos + len(g)] += g[:len(out) - pos]
        pos += len(g) - hop
        if rng.random() < 0.12: pos += int(rng.uniform(0.08, 0.3) * SR)   # breath between 'words'
    out = out[:n]
    ratio = 2 ** (-2 / 12)                          # slower and deeper: no longer his voice
    read = np.arange(0, len(out) - 1, ratio)
    return np.interp(read, np.arange(len(out)), out)[:n]


def static(n, level):
    s = bp(rng.standard_normal(n), 250, 5000) * level
    pops = np.zeros(n); k = rng.poisson(n / SR * 9); idx = rng.integers(0, n, k)
    pops[idx] = rng.standard_normal(k) * 0.6
    return s + bp(pops, 400, 4000) * level * 3


def clip(sec):
    n = int(sec * SR); t = np.arange(n) / SR
    voice = np.zeros(n); gate = np.zeros(n)
    pos = int(rng.uniform(1.2, 2.2) * SR)           # tuning in first
    while pos < n - SR:
        burst = int(rng.uniform(1.5, 4.5) * SR); burst = min(burst, n - pos - int(0.5 * SR))
        if burst < SR: break
        b = babble(burst / SR); f = int(0.12 * SR)
        e = np.ones(burst); e[:f] = np.linspace(0, 1, f); e[-f:] = np.linspace(1, 0, f)
        voice[pos:pos + burst] += b * e; gate[pos:pos + burst] = np.maximum(gate[pos:pos + burst], e)
        pos += burst + int(rng.uniform(1.0, 3.0) * SR)  # a gap: static only
    # AM radio: narrow band, valve warmth
    v = bp(voice, 300, 2800, 3)
    v = np.tanh(v * 6) / 6
    # the signal fades in and out
    fade = 0.55 + 0.45 * np.sin(2 * np.pi * rng.uniform(0.08, 0.2) * t + rng.uniform(0, 6)) \
        * (0.6 + 0.4 * np.sin(2 * np.pi * rng.uniform(0.02, 0.05) * t))
    fade = np.clip(fade, 0.12, 1)
    v = v * fade
    # static rises as the signal sinks, and fills the gaps
    st = static(n, 0.035) * (1.25 - 0.8 * fade * (0.4 + 0.6 * gate))
    # heterodyne whistle drifting in pitch, coming and going
    wf = 1400 + 500 * np.sin(2 * np.pi * 0.03 * t + rng.uniform(0, 6)) + 120 * np.sin(2 * np.pi * 0.31 * t)
    whistle = np.sin(2 * np.pi * np.cumsum(wf) / SR) * 0.012 * np.clip(np.sin(2 * np.pi * 0.05 * t + rng.uniform(0, 6)), 0, 1) ** 2
    # tuning sweep at the start: a falling whistle and a burst of noise as the dial finds the station
    sw = int(1.8 * SR); ts = np.arange(sw) / SR
    sweep = np.sin(2 * np.pi * np.cumsum(np.linspace(3200, 600, sw)) / SR) * 0.05 * np.exp(-ts * 1.6)
    sweep += static(sw, 0.09) * np.exp(-ts * 1.2)
    out = v + st + whistle
    out[:sw] += sweep
    hum = 0.006 * np.sin(2 * np.pi * 60 * t)        # mains hum under it all
    out = lp(out + hum, 4500)
    f = int(0.4 * SR); out[:int(0.02 * SR)] *= np.linspace(0, 1, int(0.02 * SR)); out[-f:] *= np.linspace(1, 0, f) ** 2
    return out


def limit(x, c):
    knee = 0.6 * c; y = x.copy(); o = np.abs(y) > knee
    y[o] = np.sign(y[o]) * (knee + (c - knee) * np.tanh((np.abs(y[o]) - knee) / (c - knee)))
    return y


def encode(x, path):
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
        wavfile.write(f.name, SR, (np.clip(x, -1, 1) * 32767).astype(np.int16))
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', f.name, '-c:a', 'libvorbis', '-q:a', '3', path], check=True)
        os.unlink(f.name)


def measure(path):
    e = subprocess.run(['ffmpeg', '-hide_banner', '-nostats', '-i', path, '-af', 'ebur128=peak=true', '-f', 'null', '-'],
                       capture_output=True, text=True).stderr
    s = e[e.rfind('Summary:'):]
    return float(s.split('I:')[1].split('LUFS')[0]), float(s.split('Peak:')[1].split('dBFS')[0])


os.makedirs(OUT, exist_ok=True)
for k in range(CLIPS):
    x = clip(rng.uniform(14, 22))
    x = x * (0.1 / (np.sqrt(np.mean(x ** 2)) + 1e-9))
    path = os.path.join(OUT, f'radio{k + 1}.ogg'); g, c = 1.0, 10 ** (CEIL_DB / 20)
    for _ in range(6):
        encode(limit(x * g, c), path); lufs, pk = measure(path)
        if abs(lufs - TARGET_LUFS) <= 0.5 and pk <= CEIL_DB + 0.3: break
        g *= 10 ** ((TARGET_LUFS - lufs) / 20)
        if pk > CEIL_DB + 0.3: c *= 10 ** ((CEIL_DB - pk) / 20)
    print(f'wrote radio{k + 1}  {len(x) / SR:5.1f}s  {lufs:6.1f} LUFS  peak {pk:6.1f} dBTP')
