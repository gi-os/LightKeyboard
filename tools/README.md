# Keyboard tooling

## `gen_charmodel.py` — typing-accuracy language model

Generates `app/src/main/res/raw/charmodel.bin`, the character trigram model the keyboard uses for
per-tap accuracy (spatial × language key selection in `LightKeyboardView`).

It learns `P(next_letter | prev2, prev1)` over a 27-symbol alphabet (a-z + a word-boundary symbol),
frequency-weighted from a real word list, with trigram/bigram/unigram interpolation for smoothing.
Output is a little-endian float32 table, flattened `index = (c1*27 + c2)*27 + c3`, value `ln P`.

### Regenerate

```sh
curl -s -o /tmp/count_1w.txt http://norvig.com/ngrams/count_1w.txt   # ~4.7 MB, 333k words+counts
python3 gen_charmodel.py /tmp/count_1w.txt \
    ../app/src/main/res/raw/charmodel.bin
```

Source word list: Peter Norvig's `count_1w.txt` (Google Web Trillion Word Corpus unigrams).
Tunables for accuracy (Gaussian width, context weight `lambda`, touch offset `biasX/biasY`) live in
`LightKeyboardView.kt`, not here.

## `gen_dict.py` — word dictionary

Generates `app/src/main/res/raw/words.bin`, the word list behind autocorrect
(`text/Corrector.kt`) and swipe typing (`text/GestureDecoder.kt`).

Two sources, doing two different jobs. **Which** words exist comes from a real spelling dictionary
(SCOWL, via Debian's `wamerican` + `wbritish`); **how common** each one is comes from Norvig's
`count_1w.txt`, the same corpus `gen_charmodel.py` uses, so the two models' frequency scales agree.

Both are needed. A web corpus contains misspellings at high frequency — `teh` and `alot` are both
inside its top 80k — and a typo that is itself a dictionary word can never be corrected, because the
corrector's first move is to leave known words alone.

Output is a flat binary of `(length, log-frequency, bytes)` records sorted by length, so the decoder
can binary-search a length band and skip the rest of the file. See the script's docstring for the
exact layout, and `text/Dictionary.kt` for the reader.

### Regenerate

```sh
curl -sL -o /tmp/count_1w.txt https://norvig.com/ngrams/count_1w.txt
cd /tmp && apt-get download wamerican wbritish        # no install needed, just unpack
for f in w*.deb; do dpkg-deb -x "$f" /tmp/wam; done
cat /tmp/wam/usr/share/dict/american-english /tmp/wam/usr/share/dict/british-english \
    | sort -u > /tmp/spelling.txt
cd -   # back to tools/
python3 gen_dict.py /tmp/count_1w.txt ../app/src/main/res/raw/words.bin /tmp/spelling.txt
```

Roughly 63k words, ~690 KB. Tunables for accuracy live in `Corrector.kt` and `GestureDecoder.kt`,
not here — and both sets were fitted against the benchmarks in their unit tests, so re-run
`./gradlew :app:test` after touching them.

## `gen_bigrams.py` — word-pair context table

Generates `app/src/main/res/raw/bigrams.bin`, the table that lets the suggestion strip
(`text/Suggester.kt`), autocorrect (`text/Corrector.kt`) and swipe typing (`text/GestureDecoder.kt`)
rank a candidate against the word before it instead of on the current word alone.

What it stores is **pointwise mutual information**, `ln( P(l,r) / (P(l)·P(r)) )`, not the pair's
probability. The rankers already know how common each word is on its own, from `words.bin`; what they
lack is whether the second word follows the first more often than chance, which is exactly PMI. Storing
the conditional probability instead would count the unigram prior twice.

Output is an open-addressed hash table with **no word list in it**: the key is a 32-bit FNV-1a hash of
`left \0 right`, so the reader needs no vocabulary and no string map, and a lookup is a hash, a mask and
one or two array reads. That matters because this runs on every keystroke inside an IME. The price is
that two pairs can share a fingerprint — at 32 bits with 180k entries, about a 1-in-24,000 chance per
lookup of a small undeserved boost, which a ranker capped at three places cannot turn into a wrong word.

The hash function is duplicated, once here and once in `ContextModel.fingerprint`. **If the two drift
apart, every lookup returns 0 and context ranking silently turns itself off** with nothing else failing.
`ContextModelTest` asserts known-good pairs out of the shipped file to catch that.

### Regenerate

```sh
curl -sL -o /tmp/count_1w.txt https://norvig.com/ngrams/count_1w.txt
curl -sL -o /tmp/count_2w.txt https://norvig.com/ngrams/count_2w.txt
python3 gen_bigrams.py /tmp/count_1w.txt /tmp/count_2w.txt \
    ../app/src/main/res/raw/words.bin ../app/src/main/res/raw/bigrams.bin
```

180,000 pairs, 1.25 MB raw and about 955 KB once the APK deflates it. `words.bin` is an input, not just
a sibling: a pair naming a word the keyboard can't suggest is a pair it can never look up, so both halves
have to be in the shipped dictionary. Regenerate this file after regenerating `words.bin`.

### Where the data comes from

`count_2w.txt` — the 1/4 million most frequent lowercase two-word bigrams — and `count_1w.txt`, both
from [Peter Norvig's *Natural Language Corpus Data*](https://norvig.com/ngrams/), the data accompanying
his chapter in *Beautiful Data* (Segaran and Hammerbacher, O'Reilly, 2009). `count_1w.txt` is already
what `gen_dict.py` and `gen_charmodel.py` use, so all three models' frequency scales agree.

Norvig's page states that the **code** there is his copyright and offered under the MIT licence, and
that the **data files are derived from the Google Web Trillion Word Corpus** (Brants and Franz,
distributed by the Linguistic Data Consortium). It puts no separate licence on the data files, so take
that as the provenance rather than as a grant. What ships in the APK is not the corpus and not the
counts: it is a lossy derived statistic — one quantised byte of mutual information per pair, for 180,000
pairs, with the words themselves replaced by hashes and thrown away. There is no way to recover a word,
a count or a frequency from `bigrams.bin`.

