<img src="assets/images/example.png" alt="Light Keyboard">

A clone of the Light Phone 3's built-in keyboard, for any app — with swipe typing.

> A fork of [adam-weber/light-keyboard](https://github.com/adam-weber/light-keyboard), adding swipe
> typing and a real on-device dictionary for autocorrect. The keyboard looks exactly the same.

On a stock Light Phone, the black-and-white keyboard lives inside Light's own tools. Other apps use the
system keyboard, and there is no Light one to choose. This is a faithful recreation, packaged as a
system keyboard you can set as the default, so every app on a modified Light Phone shares the same look.

Swipe across the letters to write a whole word. Autocorrect and voice dictation are optional, and
everything runs on-device — no network, ever. Swipe down on the keyboard to hide it.

## Install

### With [Obtainium](https://github.com/ImranR98/Obtainium), which keeps you up to date

1. Install Obtainium.
2. Add an app, and give it this repository:
   `https://github.com/gi-os/LightKeyboard`
3. It installs the latest release, and tells you when there is a new one.

### Or by hand

Download the latest APK from [Releases](../../releases) and open it.

## Turn it on

Open Light Keyboard. The setup screen holds everything:

1. **Enable Light Keyboard**: opens Android's keyboard settings, where you switch it on.
2. **Choose it as your keyboard**: makes it the active one.

A few optional settings sit below:

- **Autocorrect** (on by default): fixes misspellings when you finish a word, using a dictionary
  bundled in the app. Backspace once to undo a correction. Turn it off to type exactly what you tap.
- **Swipe typing** (on by default): drag from letter to letter to write a whole word, then lift. The
  word appears with a space after it. If it guessed wrong, press backspace to cycle through the other
  words your trace could have been — there is no suggestion bar, so the keyboard looks unchanged.
- **Auto-Capitalize** (on by default): capitalizes the start of each sentence.
- **Auto-Period** (on by default): double-tap the space bar to insert a period.
- **Return key** / **Emoji keyboard** (both on by default): show or hide those keys.
- **Voice dictation** (off by default): turning it on downloads a ~40 MB offline speech-to-text model
  (Vosk) once, then a mic key lets you speak instead of type, entirely on-device. Once downloaded, a
  **Delete model** link appears beside the toggle to reclaim the space.
- **Compact keyboard** (off by default): shorter keys and tighter spacing, so the keyboard takes up
  less of the screen.
- **Keyboard layout**: choose QWERTY, AZERTY, or QWERTZ.

Layout / appearance changes take effect the next time the keyboard opens. That is the whole setup.

## Build it yourself

```sh
./gradlew :app:assembleDebug      # debug build
./gradlew :app:assembleRelease    # release build (unsigned unless signing env vars are set)
```

Needs JDK 17 and the Android SDK (API 35). Every push is built and tested by
[`.github/workflows/build.yml`](.github/workflows/build.yml); tagged releases (`v*`) are built and
signed by [`.github/workflows/release.yml`](.github/workflows/release.yml).

The two bundled data files are generated, not hand-written — see [`tools/`](tools/README.md):

- `res/raw/charmodel.bin` — character trigram model for per-tap key selection ([`tools/gen_charmodel.py`](tools/gen_charmodel.py))
- `res/raw/words.bin` — the word list behind autocorrect and swipe typing ([`tools/gen_dict.py`](tools/gen_dict.py))

The typing logic lives in `app/src/main/java/app/lightphonekeyboard/text/` and deliberately has no
Android dependencies, so it is unit-tested on real data rather than only on a phone. The weights in
`Corrector.kt` and `GestureDecoder.kt` were fitted against the benchmarks in those tests — if you
change them, run `./gradlew :app:test` and see what you traded away.

## A note

This is an independent, open-source project. It is made for the Light Phone, but it is not made by
Light. The original is [adam-weber/light-keyboard](https://github.com/adam-weber/light-keyboard).

## License

[MIT](LICENSE). Do what you like with it.
