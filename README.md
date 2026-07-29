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
  words your trace could have been.
- **Suggestions** (off by default): a thin strip above the keys showing three words — completions of
  what you are typing, the repair autocorrect has in mind, or the other readings of a swipe. Tap one to
  use it. Off by default because the LightOS keyboard has no suggestion bar, and turning it on makes the
  keyboard one strip taller in every app.
- **My words**: names and anything else the dictionary has never heard of. A word you add stops being
  autocorrected into something else, becomes a word autocorrect can arrive *at*, and becomes traceable
  by swipe. Without it, "Bjorn" is rewritten to "born" every time you type it.
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

The typing logic lives in `app/src/main/java/app/lightphonekeyboard/text/` — dictionary, autocorrect,
swipe decoding, prefix completion and the personal word list — and deliberately has no Android
dependencies, so it is unit-tested on real data rather than only on a phone. The weights in
`Corrector.kt` and `GestureDecoder.kt` were fitted against the benchmarks in those tests — if you
change them, run `./gradlew :app:test` and see what you traded away.

## A note

This is an independent, open-source project. It is made for the Light Phone, but it is not made by
Light. The original is [adam-weber/light-keyboard](https://github.com/adam-weber/light-keyboard).

## License

[MIT](LICENSE). Do what you like with it.

## Releases and signing

Every push to `main` builds, tests, and publishes a signed APK as the next `v1.0.<n>` release —
see [`.github/workflows/build.yml`](.github/workflows/build.yml). Obtainium picks it up on its own.

The signing key is a repo secret (`KEYSTORE_BASE64`), not a file in the repository. Only its
certificate fingerprint is committed, in `signing-fingerprint.txt`, and CI fails if a build's
certificate doesn't match it. That guard exists because Android identifies an app by
`(packageName, certificate)`: change the certificate and every existing install stops being
upgradeable, which surfaces only as an unhelpful `Failure: Invalid` at install time.

Release builds are never left unsigned — the Gradle build fails outright with no key, rather than
producing an APK that looks fine and then refuses to install.

To build a signed APK yourself, either set `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD`, or drop a `keystore.properties` in the project root:

```properties
storeFile=/path/to/your.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Debug builds share that key and the same `applicationId`, so `adb install -r` replaces an installed
release in place instead of leaving a second Light Keyboard in the input-method list. Without a key,
debug still builds with ordinary debug signing.

> Coming from the upstream app, or from a build made before this key existed? Android will refuse the
> update because the certificate differs. Uninstall the old one first.
