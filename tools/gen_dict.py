#!/usr/bin/env python3
"""
Build the keyboard's bundled word dictionary: app/src/main/res/raw/words.bin

Why bundle one at all: the phone's own SpellCheckerSession is the only dictionary the keyboard used
to have, and LightOS ships without the Google spell-check service, so on a real Light Phone that
session returns nothing and autocorrect silently does nothing. Swipe typing needs a word list too —
there is nothing to decode a gesture against without one. One file serves both.

Format (little-endian throughout), designed so the Kotlin side can mmap-ish read it in one pass and
keep it in flat primitive arrays — no per-word object, which matters on a keyboard that has to stay
responsive while a gesture is in flight:

    magic   u32   'LKD1'
    count   u32   number of words
    then `count` records, sorted by length then alphabetically:
        len     u8      word length in bytes (ASCII a-z and ' only)
        logf    i16     round(ln(freq / total) * 1000), i.e. log-probability in milli-nats
        bytes   len     the word, lowercase ASCII

Sorted by length so the decoder can binary-search a length band and skip whole swathes of the
dictionary: a gesture of n sampled points can only plausibly be a word within a length window.

Two sources, doing two different jobs:

  * WHICH words exist: a real spelling dictionary (SCOWL, via Debian's wamerican + wbritish).
  * HOW COMMON each one is: Peter Norvig's count_1w.txt (Google Web Trillion Word Corpus unigrams),
    the same source gen_charmodel.py uses, so the two models' frequency scales agree.

Both are needed, and using the corpus alone was a real bug. A web corpus contains misspellings at
genuinely high frequency — "teh" and "alot" both sit inside the top 80k — and a typo that is itself a
dictionary word can never be corrected, because the corrector's first move is to leave known words
alone. Gating membership on the spelling list drops those while keeping the corpus frequencies that
make the ranking work.

Regenerate:
    curl -sL -o /tmp/count_1w.txt https://norvig.com/ngrams/count_1w.txt
    # SCOWL word lists, from Debian (no install needed, just unpack):
    cd /tmp && apt-get download wamerican wbritish
    for f in w*.deb; do dpkg-deb -x "$f" /tmp/wam; done
    cat /tmp/wam/usr/share/dict/american-english /tmp/wam/usr/share/dict/british-english \
        | sort -u > /tmp/spelling.txt
    python3 gen_dict.py /tmp/count_1w.txt ../app/src/main/res/raw/words.bin /tmp/spelling.txt
"""
import math
import re
import struct
import sys

MAGIC = 0x314C4B44  # 'LKD1' little-endian
MAX_WORDS = 80_000
MIN_LEN = 1
MAX_LEN = 24
WORD_RE = re.compile(r"^[a-z']+$")

# Very short junk tokens dominate a web corpus (single letters, "aa", "ab"). Keep only the
# single letters that are real words plus the two-letter words a Scrabble dictionary would allow;
# everything else short is noise that would hijack short gestures.
KEEP_1 = {"a", "i"}
KEEP_2 = {
    "am", "an", "as", "at", "be", "by", "do", "go", "he", "hi", "if", "in", "is", "it", "me",
    "my", "no", "of", "oh", "ok", "on", "or", "so", "to", "up", "us", "we", "ah", "aw", "ax",
    "ex", "id", "lo", "ma", "pa", "re", "un", "yo",
}

# Words no spelling dictionary lists but a phone typist needs constantly: apostrophe-less
# contractions (nobody types the apostrophe), and chat shorthand. Without these, autocorrect would
# "fix" every "dont" into something else, which is far more annoying than missing a correction.
EXTRA = [
    "im", "ive", "id", "dont", "cant", "wont", "isnt", "wasnt", "didnt", "doesnt", "couldnt",
    "shouldnt", "wouldnt", "havent", "hasnt", "arent", "youre", "youve", "youll", "theyre",
    "theyve", "thats", "whats", "lets", "hes", "shes", "its", "wanna", "gonna", "gotta", "yeah",
    "yep", "nope", "hey", "hmm", "lol", "omg", "btw", "idk", "tbh", "ok", "okay", "email",
    "wifi", "app", "apps", "online", "offline", "login", "signup", "podcast", "smartphone",
]
EXTRA_LOGF = -13.0  # rare-but-present: below any common word, above the corpus tail


def load_spelling(path):
    """The set of words a real dictionary recognises, lowercased. Possessives are dropped: the
    apostrophe forms are in the corpus already and `cat's` adds nothing the keyboard can type."""
    words = set()
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            w = line.strip().lower()
            if w and WORD_RE.match(w):
                words.add(w)
    return words


def load(path, allowed):
    freqs = {}
    total = 0
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            parts = line.split()
            if len(parts) != 2:
                continue
            word, count = parts[0].lower(), parts[1]
            if not WORD_RE.match(word):
                continue
            n = len(word)
            if n < MIN_LEN or n > MAX_LEN:
                continue
            if n == 1 and word not in KEEP_1:
                continue
            if n == 2 and word not in KEEP_2:
                continue
            # Frequency comes from the corpus; existence does not. See the module docstring.
            if allowed is not None and word not in allowed:
                # Count it toward the total anyway, so the remaining log-probabilities stay
                # comparable to the character model's, which was trained on the unfiltered list.
                total += int(word_count(count))
                continue
            try:
                c = int(count)
            except ValueError:
                continue
            freqs[word] = freqs.get(word, 0) + c
            total += c
    return freqs, total


def word_count(count):
    try:
        return int(count)
    except ValueError:
        return 0


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/count_1w.txt"
    out = sys.argv[2] if len(sys.argv) > 2 else "words.bin"
    spelling = sys.argv[3] if len(sys.argv) > 3 else None

    allowed = None
    if spelling:
        allowed = load_spelling(spelling)
        print(f"spelling dictionary: {len(allowed)} words")
    else:
        print("WARNING: no spelling dictionary given — the output will contain common misspellings "
              "(teh, alot, ...) which autocorrect can then never fix. See the module docstring.")

    freqs, total = load(src, allowed)
    if not freqs:
        sys.exit(f"no usable words parsed from {src}")

    # Keep the most frequent MAX_WORDS. The tail of a web corpus is misspellings and URL fragments,
    # which is exactly what an autocorrect dictionary must not contain — a typo that happens to be
    # in the dictionary can never be corrected.
    top = sorted(freqs.items(), key=lambda kv: -kv[1])[:MAX_WORDS]

    entries = {}
    for word, c in top:
        entries[word] = max(-32768, min(32767, round(math.log(c / total) * 1000)))
    for word in EXTRA:
        entries.setdefault(word, round(EXTRA_LOGF * 1000))

    # Sort by length, then alphabetically: lets the Kotlin decoder jump straight to a length band.
    ordered = sorted(entries.items(), key=lambda kv: (len(kv[0]), kv[0]))

    with open(out, "wb") as f:
        f.write(struct.pack("<II", MAGIC, len(ordered)))
        for word, logf in ordered:
            f.write(struct.pack("<Bh", len(word), logf))
            f.write(word.encode("ascii"))

    import os
    print(f"{len(ordered)} words -> {out} ({os.path.getsize(out)} bytes)")
    by_len = {}
    for w, _ in ordered:
        by_len[len(w)] = by_len.get(len(w), 0) + 1
    print("by length:", dict(sorted(by_len.items())))


if __name__ == "__main__":
    main()
