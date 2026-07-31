#!/usr/bin/env python3
"""
Build the keyboard's word-pair context table: app/src/main/res/raw/bigrams.bin

Why: autocorrect and the suggestion strip rank candidates on the current word alone — spelling
distance and how common the word is — so they happily offer a word that cannot follow the one before
it. Typing "i thin" offers "thing" above "think" because "thing" is the commoner word, even though
"i thing" is not English. A word-pair table is the smallest thing that fixes that class of mistake;
it is not a language model and does not pretend to be one.

What is stored is POINTWISE MUTUAL INFORMATION, not the pair's probability:

    pmi(l, r) = ln( P(l, r) / (P(l) · P(r)) )

The rankers already know how common `r` is on its own. What they are missing is whether `r` follows
`l` more often than chance, which is exactly what PMI is, so the table adds information instead of
repeating the unigram prior that is already in words.bin. Pairs with pmi <= PMI_MIN are dropped
rather than stored as a zero: "no signal" and "absent from the table" have to behave identically
anyway, so storing them would be paying bytes for nothing.

Format (little-endian). An open-addressed hash table, sized so a lookup is one or two array reads —
this runs on every keystroke inside an IME, where a few hundred microseconds is the whole budget:

    magic     u32   'LKB1'
    capacity  u32   power of two, number of slots
    count     u32   live entries (sanity check only)
    keys      u32 × capacity   32-bit fingerprint of "l\\0r", 0 = empty slot
    scores    u8  × capacity   round(min(pmi, PMI_FULL) / PMI_FULL * 255)

There is deliberately NO word list in the file and no string table in memory. The key is a hash of
the pair, so the reader needs no vocabulary and no per-word map — 1.25 MB of flat primitive arrays
and nothing else. The price is that two different pairs can share a fingerprint, and at 32 bits with
~180k entries that is a 0.004% chance per lookup of a word getting a boost it did not earn. A
reranker that can only move a candidate a couple of places cannot turn that into a wrong word, which
is why the trade is worth making here and would not be in, say, a spelling dictionary.

Sources, the same two the other generated assets use so the frequency scales agree:

  * Pair counts: Peter Norvig's count_2w.txt (Google Web Trillion Word Corpus bigrams, the top
    286k pairs).
  * Single-word counts, for the P(l) and P(r) in the PMI: Norvig's count_1w.txt.

Both are filtered against the words actually in words.bin — a pair naming a word the keyboard cannot
suggest is a pair it can never look up.

Regenerate:
    curl -sL -o /tmp/count_1w.txt https://norvig.com/ngrams/count_1w.txt
    curl -sL -o /tmp/count_2w.txt https://norvig.com/ngrams/count_2w.txt
    python3 gen_bigrams.py /tmp/count_1w.txt /tmp/count_2w.txt \\
        ../app/src/main/res/raw/words.bin ../app/src/main/res/raw/bigrams.bin
"""
import math
import os
import re
import struct
import sys

MAGIC = 0x314C4B42          # 'LKB1', matching words.bin's 'LKD1' convention
DICT_MAGIC = 0x314C4B44

# Capacity is a power of two so the reader can mask instead of dividing. 2**18 slots at 5 bytes is
# 1.25 MB; MAX_ENTRIES keeps the load factor at ~0.7, past which linear probing starts costing a
# dozen reads per miss and the "one or two array reads" promise stops being true.
CAPACITY = 1 << 18
MAX_ENTRIES = 180_000

# Below this the pair says nothing a unigram prior doesn't already say. The median in-vocabulary pair
# scores about 1.8, so this only drops the genuinely uninformative tail.
PMI_MIN = 0.5
# PMI is capped here before quantising. The 99th percentile is ~7.8, so 10 keeps the whole useful
# range inside a byte at 0.04-nat resolution and clips only fixed phrases, which are already maxed.
PMI_FULL = 10.0

WORD_RE = re.compile(r"^[a-z']+$")


def load_vocab(path):
    """The words words.bin actually contains. Reading the shipped asset rather than re-deriving it
    keeps the two files honest: a pair is only useful if both halves are suggestible."""
    with open(path, "rb") as f:
        data = f.read()
    magic, count = struct.unpack_from("<II", data, 0)
    if magic != DICT_MAGIC:
        sys.exit(f"{path}: bad magic {magic:#x}")
    off = 8
    words = set()
    for _ in range(count):
        (length,) = struct.unpack_from("<B", data, off)
        off += 3                                    # length byte + the i16 log-frequency
        words.add(data[off:off + length].decode("ascii"))
        off += length
    return words


def load_unigrams(path):
    counts = {}
    total = 0
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            parts = line.split()
            if len(parts) != 2:
                continue
            word = parts[0].lower()
            try:
                c = int(parts[1])
            except ValueError:
                continue
            counts[word] = counts.get(word, 0) + c
            total += c
    return counts, total


def load_pairs(path, vocab):
    """In-vocabulary pairs, plus the corpus total over ALL pairs — including the ones filtered out,
    so P(l, r) stays a real probability rather than one renormalised over the kept subset.

    Counts are summed per lowercased pair. The corpus keeps case, so "The Same", "the same" and
    "The same" arrive as three separate lines; treating them as three pairs both understates every
    PMI and, because they hash alike, silently threw away 10,920 of 180,000 table entries as
    apparent duplicates."""
    pairs = {}
    total = 0
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 2:
                continue
            try:
                c = int(parts[1])
            except ValueError:
                continue
            total += c
            words = parts[0].lower().split()
            if len(words) != 2:
                continue
            left, right = words
            if not WORD_RE.match(left) or not WORD_RE.match(right):
                continue
            if left not in vocab or right not in vocab:
                continue
            key = (left, right)
            pairs[key] = pairs.get(key, 0) + c
    return [(l, r, c) for (l, r), c in pairs.items()], total


def fingerprint(left, right):
    """FNV-1a over "left\\0right", never zero (zero marks an empty slot). Mirrored exactly by
    ContextModel.fingerprint in Kotlin — the two must agree byte for byte or every lookup misses."""
    h = 0x811C9DC5
    for b in left.encode("ascii") + b"\x00" + right.encode("ascii"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h or 1


def main():
    uni_path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/count_1w.txt"
    bi_path = sys.argv[2] if len(sys.argv) > 2 else "/tmp/count_2w.txt"
    dict_path = sys.argv[3] if len(sys.argv) > 3 else "../app/src/main/res/raw/words.bin"
    out = sys.argv[4] if len(sys.argv) > 4 else "bigrams.bin"

    vocab = load_vocab(dict_path)
    print(f"vocabulary: {len(vocab)} words from {dict_path}")

    uni, uni_total = load_unigrams(uni_path)
    print(f"unigrams: {len(uni)} words, {uni_total} occurrences")

    pairs, pair_total = load_pairs(bi_path, vocab)
    print(f"pairs: {len(pairs)} in vocabulary, {pair_total} occurrences")

    scored = []
    for left, right, c in pairs:
        cl = uni.get(left)
        cr = uni.get(right)
        if not cl or not cr:
            continue
        pmi = math.log((c / pair_total) / ((cl / uni_total) * (cr / uni_total)))
        if pmi <= PMI_MIN:
            continue
        scored.append((c, left, right, pmi))

    # Top by pair frequency, not by PMI. PMI alone selects for oddities — rare pairs of rare words
    # score highest on it — and the point of the table is to be right about the sentences people
    # actually type.
    scored.sort(key=lambda t: -t[0])
    kept = scored[:MAX_ENTRIES]
    print(f"kept {len(kept)} of {len(scored)} pairs above pmi {PMI_MIN} "
          f"(count floor {kept[-1][0] if kept else 0})")

    keys = [0] * CAPACITY
    scores = [0] * CAPACITY
    mask = CAPACITY - 1
    live = 0
    collisions = 0
    probes = 0
    for c, left, right, pmi in kept:
        h = fingerprint(left, right)
        i = h & mask
        while keys[i] != 0 and keys[i] != h:
            i = (i + 1) & mask
            probes += 1
        if keys[i] == h:
            collisions += 1                        # same fingerprint, different pair: keep the commoner
            continue
        keys[i] = h
        # Quantised so that 0 can keep meaning "nothing here": PMI_MIN maps to 12, never to 0.
        scores[i] = max(1, min(255, round(min(pmi, PMI_FULL) / PMI_FULL * 255)))
        live += 1
    print(f"table: {live} entries in {CAPACITY} slots (load {live / CAPACITY:.2f}), "
          f"{collisions} fingerprint collisions, {probes / max(live, 1):.2f} extra probes per insert")

    with open(out, "wb") as f:
        f.write(struct.pack("<III", MAGIC, CAPACITY, live))
        f.write(struct.pack(f"<{CAPACITY}I", *keys))
        f.write(bytes(scores))
    print(f"{out}: {os.path.getsize(out)} bytes")


if __name__ == "__main__":
    main()
