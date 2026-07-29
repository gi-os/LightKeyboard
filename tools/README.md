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
