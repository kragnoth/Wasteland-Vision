#!/usr/bin/env python3
"""
Synthesises the Occult Foundry vision's sounds from scratch (no samples, nothing licensed):

  grove/crackle    loop   worn 78rpm surface: hiss, crackle, pops and the once-per-turn thump
  grove/hum        loop   low mains hum with slow beating - the machines' spirits
  grove/waltz1-5   phrases: public-domain waltzes (Strauss, Rosas, Brahms) on a detuned music box,
                          played through a phonograph: wow & flutter, narrow band, crackle
  grove/whisper1-4 one-shots: formant-shaped breath noise that almost makes syllables
  bell             one-shot: one distant, muffled stroke of a large church bell

usage: python3 tools/gen_occult_audio.py [out_dir]   (needs numpy, scipy, ffmpeg with libvorbis)
"""
import os, subprocess, sys, tempfile
import numpy as np
from scipy import signal
from scipy.io import wavfile

OUT = sys.argv[1] if len(sys.argv) > 1 else \
    'src/main/resources/wastelandvision_defaults/visions/occult_foundry/assets/occult/sounds'
SR = 32000
rng = np.random.default_rng(1866)


def t(sec): return np.arange(int(sec * SR)) / SR


def bp(x, lo, hi, order=2):
    b, a = signal.butter(order, [lo / (SR / 2), hi / (SR / 2)], 'band'); return signal.lfilter(b, a, x)


def lp(x, f, order=2):
    b, a = signal.butter(order, f / (SR / 2), 'low'); return signal.lfilter(b, a, x)


def hp(x, f, order=2):
    b, a = signal.butter(order, f / (SR / 2), 'high'); return signal.lfilter(b, a, x)


def norm(x, peak=0.9):
    m = np.max(np.abs(x)); return x * (peak / m) if m > 0 else x


def reverb(x, seconds=1.6, mix=0.35, damp=3000):
    n = int(seconds * SR)
    ir = rng.standard_normal(n) * np.exp(-np.arange(n) / (SR * seconds / 6))
    ir = lp(ir, damp); ir /= np.sqrt(np.sum(ir ** 2))
    wet = signal.fftconvolve(x, ir)
    dry = np.concatenate([x, np.zeros(len(wet) - len(x))])
    return dry * (1 - mix) + wet * mix


def seamless(x, fade=1.5):
    """Crossfade the tail into the head so the loop has no seam."""
    n = int(fade * SR)
    head, body, tail = x[:n], x[n:-n] if n else x, x[-n:]
    w = np.linspace(0, 1, n)
    return np.concatenate([body, tail * (1 - w) + head * w])


# Headphone-safe levels: each file is matched to a perceived-loudness target (LUFS, measured on the
# encoded OGG with ffmpeg's EBU R128 meter) and its true peak kept under a ceiling with a soft limiter,
# iterating until both hold - so crackle pops, whisper hiss and the bell's strike can't spike. Loops sit
# lower than one-shots; in game they're scaled further by grove depth and the Ambient/Environment slider.
LEVELS = {                      # name: (target integrated loudness LUFS, true-peak ceiling dBTP)
    'grove/crackle': (-31, -14),
    'grove/hum': (-31, -16),
    'grove/waltz': (-28, -12),          # all waltz phrases
    'grove/whisper': (-29, -13),
    'bell': (-25, -9),
}


def limit(x, ceil_lin):
    knee = 0.6 * ceil_lin                           # untouched below the knee, tanh-compressed above
    y = x.copy(); over = np.abs(y) > knee
    y[over] = np.sign(y[over]) * (knee + (ceil_lin - knee) * np.tanh((np.abs(y[over]) - knee) / (ceil_lin - knee)))
    return y


def encode(x, path):
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
        wavfile.write(f.name, SR, (np.clip(x, -1, 1) * 32767).astype(np.int16))
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', f.name, '-ac', '1', '-c:a', 'libvorbis', '-q:a', '3', path], check=True)
        os.unlink(f.name)


def measure(path):
    out = subprocess.run(['ffmpeg', '-hide_banner', '-nostats', '-i', path, '-af', 'ebur128=peak=true', '-f', 'null', '-'],
                         capture_output=True, text=True).stderr
    summ = out[out.rfind('Summary:'):]
    lufs = float(summ.split('I:')[1].split('LUFS')[0])
    peak = float(summ.split('Peak:')[1].split('dBFS')[0])
    return lufs, peak


def save(x, name):
    key = 'grove/whisper' if name.startswith('grove/whisper') else 'grove/waltz' if name.startswith('grove/waltz') else name
    target, ceil_db = LEVELS[key]
    x = np.asarray(x, dtype=np.float64)
    n = int(0.02 * SR)                              # 20 ms fades: no clicks at the file edges
    x[:n] *= np.linspace(0, 1, n)
    if 'whisper' in name or name == 'bell': x[-n:] *= np.linspace(1, 0, n)
    x = x * (0.1 / (np.sqrt(np.mean(x ** 2)) or 1e-9))
    path = os.path.join(OUT, name + '.ogg')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    gain, ceil = 1.0, 10 ** (ceil_db / 20)
    for _ in range(6):
        encode(limit(x * gain, ceil), path)
        lufs, peak = measure(path)
        if abs(lufs - target) <= 0.5 and peak <= ceil_db + 0.3: break
        gain *= 10 ** ((target - lufs) / 20)
        if peak > ceil_db + 0.3: ceil *= 10 ** ((ceil_db - peak) / 20)   # codec overshoot: limit a bit harder
    print(f'wrote {name:15s} {len(x) / SR:5.1f}s  {lufs:6.1f} LUFS  peak {peak:6.1f} dBTP')


# ---- crackle: worn shellac -----------------------------------------------------------------------
def crackle(sec):
    n = int(sec * SR)
    hiss = bp(rng.standard_normal(n), 800, 7000) * 0.06 * (0.8 + 0.2 * np.sin(np.linspace(0, sec * 2 * np.pi * 1.3, n)))
    pops = np.zeros(n)
    k = rng.poisson(sec * 38)
    idx = rng.integers(0, n, k)
    pops[idx] = rng.standard_normal(k) * rng.pareto(2.2, k) * 0.12
    pops = hp(pops, 600) + 0.4 * lp(pops, 400)
    pops = np.tanh(pops * 6) / 6                    # tame the biggest pops so the hiss isn't buried
    turn = np.zeros(n)                              # 78 rpm: one faint thump every 0.77 s
    for s in np.arange(0, sec, 60 / 78):
        i = int(s * SR); turn[i:i + 200] += np.hanning(200) * 0.08
    rumble = lp(rng.standard_normal(n), 60) * 0.25
    return hiss + pops + lp(turn, 200) + rumble


save(norm(seamless(crackle(26)), 0.8), 'grove/crackle')

# ---- hum ----------------------------------------------------------------------------------------
x = t(22)
hum = (np.sin(2 * np.pi * 55 * x) + 0.5 * np.sin(2 * np.pi * 110.3 * x) + 0.25 * np.sin(2 * np.pi * 165.2 * x)
       + 0.12 * np.sin(2 * np.pi * 221 * x))
hum *= 0.75 + 0.25 * np.sin(2 * np.pi * 0.09 * x) * np.sin(2 * np.pi * 0.023 * x)   # slow, uneven breathing
hum += 0.05 * bp(rng.standard_normal(len(x)), 100, 400)
save(norm(seamless(hum, 3), 0.7), 'grove/hum')

# ---- waltz phrases: music box through a phonograph ------------------------------------------------
# Five separate phrases (all public-domain tunes) that the game plays one at a time with long silences
# between: Strauss 'The Blue Danube' (1866), Rosas 'Over the Waves' (1888), Brahms Waltz Op.39 No.15
# (1865). Each gets its own tempo, detune and wow; two end like a gramophone winding down, one sticks
# in a skipping groove.
def hz(n):
    names = {'C': -9, 'C#': -8, 'D': -7, 'D#': -6, 'E': -5, 'F': -4, 'F#': -3, 'G': -2, 'G#': -1, 'A': 0, 'A#': 1, 'B': 2}
    return 440.0 * 2 ** ((names[n[:-1]] + (int(n[-1]) - 4) * 12) / 12)


DANUBE_A = [('D4', 1), ('D4', 1), ('F#4', 1), ('A4', 1), ('A4', 2), ('A5', 1), ('A5', 2), ('F#5', 1), ('F#5', 2),
            ('D4', 1), ('D4', 1), ('F#4', 1), ('A4', 1), ('A4', 2), ('A5', 1), ('A5', 2), ('G5', 1), ('G5', 2)]
DANUBE_B = [('C#4', 1), ('C#4', 1), ('E4', 1), ('B4', 1), ('B4', 2), ('B5', 1), ('B5', 2), ('G5', 1), ('G5', 2),
            ('C#4', 1), ('C#4', 1), ('E4', 1), ('B4', 1), ('B4', 2), ('B5', 1), ('B5', 2), ('F#5', 1), ('F#5', 2),
            ('D4', 1), ('D4', 1), ('F#4', 1), ('A4', 1), ('D5', 2), ('D6', 1), ('D6', 2), ('A5', 1), ('A5', 3)]
WAVES = [('E4', 1), ('F#4', 1), ('G4', 1), ('B4', 3), ('A4', 1), ('G4', 1), ('F#4', 1), ('A4', 3),
         ('G4', 1), ('F#4', 1), ('E4', 1), ('G4', 2), ('F#4', 1), ('D#4', 3), ('E4', 1), ('F#4', 1), ('G4', 1),
         ('B4', 3), ('C5', 1), ('B4', 1), ('A4', 1), ('G4', 3), ('F#4', 1), ('E4', 1), ('D#4', 1), ('E4', 3)]
BRAHMS = [('B4', 1), ('D5', 2), ('D5', 1), ('C5', 1), ('B4', 1), ('B4', 2), ('A4', 1), ('G4', 3),
          ('A4', 1), ('C5', 2), ('C5', 1), ('B4', 1), ('A4', 1), ('A4', 2), ('G4', 1), ('F#4', 3),
          ('G4', 1), ('B4', 2), ('B4', 1), ('A4', 1), ('G4', 1), ('E5', 2), ('D5', 1), ('D5', 3), ('G4', 3)]


def tine(f, dur, vel, cents):
    x = t(dur)
    f *= 2 ** ((cents + rng.normal(0, 12)) / 1200)   # out-of-tune comb
    s = (np.sin(2 * np.pi * f * x) * np.exp(-x * 2.2) + 0.45 * np.sin(2 * np.pi * f * 2.76 * x) * np.exp(-x * 6)
         + 0.18 * np.sin(2 * np.pi * f * 5.4 * x) * np.exp(-x * 11))
    return s * vel * np.minimum(1, x * 400)


def phrase(mel, beat, cents=0.0, wow=0.012, wind_down=False, skip=None):
    """skip=(start_beat, beats, repeats): the needle sticks and repeats that span, then stops."""
    if skip:
        b0, n, reps = skip
        acc, cut = 0, []
        for note, b in mel:
            if acc >= b0 + n: break
            cut.append((note, b)); acc += b
        loop = [];  acc = 0
        for note, b in cut:
            if acc >= b0: loop.append((note, b))
            acc += b
        mel = cut + loop * reps
    total = sum(b for _, b in mel) * beat + 3
    mix = np.zeros(int(total * SR)); pos = 0.0; bar = 0
    for note, b in mel:
        s = tine(hz(note), 2.6, 0.8 + 0.2 * rng.random(), cents)
        i = int(pos * SR); mix[i:i + len(s)] += s[:len(mix) - i]
        if bar % 3 == 0:                           # a soft low tine on each downbeat
            f = hz(note); root = f / (4 if f > 600 else 2)
            lo = tine(root, 2.2, 0.35, cents); mix[i:i + len(lo)] += lo[:len(mix) - i]
        pos += b * beat; bar += b
    x = np.arange(len(mix)) / SR
    speed = 0.97 + wow * np.sin(2 * np.pi * rng.uniform(0.4, 0.7) * x) + 0.004 * np.sin(2 * np.pi * 6.3 * x)
    if wind_down:                                   # the spring runs out: slower and flatter to a stop
        k = np.clip((x - x[-1] * 0.6) / (x[-1] * 0.4), 0, 1)
        speed = speed * (1 - 0.55 * k ** 1.5)
    read = np.cumsum(speed); read = read[read < len(mix) - 1]
    w = np.interp(read, np.arange(len(mix)), mix)
    w = bp(w, 320, 3200, 3)                         # horn-gramophone band
    w = reverb(w, 2.2, 0.4, 2400)
    w = norm(w, 0.7) + 0.35 * norm(crackle(len(w) / SR), 0.6)
    fade = int(1.5 * SR); w[-fade:] *= np.linspace(1, 0, fade) ** 2
    return w


save(phrase(DANUBE_A, 0.62), 'grove/waltz1')
save(phrase(DANUBE_A + DANUBE_B, 0.70, cents=-25, wind_down=True), 'grove/waltz2')
save(phrase(WAVES, 0.66, cents=15, wow=0.016), 'grove/waltz3')
save(phrase(BRAHMS, 0.80, cents=-10, wow=0.009, wind_down=True), 'grove/waltz4')
save(phrase(DANUBE_B, 0.64, cents=30, skip=(9, 3, 4)), 'grove/waltz5')

# ---- whispers -------------------------------------------------------------------------------------
VOWELS = [(300, 870), (400, 2000), (550, 1700), (700, 1200), (350, 2300), (450, 950)]


def whisper(sec, syllables):
    n = int(sec * SR); out = np.zeros(n)
    edges = np.sort(rng.uniform(0.1, sec - 0.3, syllables))
    for e in edges:
        d = rng.uniform(0.12, 0.32); m = int(d * SR); i = int(e * SR)
        f1, f2 = VOWELS[rng.integers(len(VOWELS))]
        nz = rng.standard_normal(m)
        v = bp(nz, f1 * 0.8, f1 * 1.25) + 0.7 * bp(nz, f2 * 0.85, f2 * 1.2)
        env = np.sin(np.linspace(0, np.pi, m)) ** 1.5
        out[i:i + m] += v * env
        if rng.random() < 0.45:                     # a hissed consonant before it
            k = int(0.07 * SR); j = max(0, i - k)
            out[j:j + k] += hp(rng.standard_normal(k), 4500) * np.hanning(k) * 0.6
    if rng.random() < 0.5: out = out[::-1]          # half of them run backwards
    return reverb(out, 1.0, 0.22, 3500)


for k in range(4):
    save(norm(whisper(rng.uniform(2.4, 3.6), int(rng.integers(3, 7))), 0.8), f'grove/whisper{k + 1}')

# ---- bell -----------------------------------------------------------------------------------------
x = t(11)
f0 = 146.8                                          # a big, low bell (D3 nominal region)
partials = [(0.5, 1.0, 0.35), (1.0, 0.9, 0.5), (1.19, 0.55, 0.9), (1.5, 0.45, 1.1), (2.0, 0.6, 1.3),
            (2.52, 0.3, 1.8), (2.99, 0.25, 2.2), (4.17, 0.15, 3.0), (5.43, 0.1, 4.0)]
bell = sum(a * np.sin(2 * np.pi * f0 * r * x + rng.random() * 6) * np.exp(-x * dcy) for r, a, dcy in partials)
bell[:int(0.01 * SR)] *= np.linspace(0, 1, int(0.01 * SR))
bell = lp(bell, 1500, 2)                            # muffled by distance
bell = reverb(bell, 3.5, 0.55, 1800)
save(norm(bell, 0.9), 'bell')
