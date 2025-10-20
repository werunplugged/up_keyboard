# UP Keyboard

UP Keyboard is a privacy-focused fork of [HeliBoard](https://github.com/Helium314/HeliBoard), specifically developed for Unplugged devices. Built on HeliBoard's solid foundation (which itself is based on AOSP / OpenBoard), UP Keyboard extends the feature set with advanced offline voice recognition capabilities and thoughtful improvements tailored for the Unplugged ecosystem.

Like its predecessor, UP Keyboard does not use internet permission and operates 100% offline, ensuring your privacy is never compromised.

## Table of Contents

- [Key Features (Unplugged Additions)](#key-features-unplugged-additions)
- [Core Features (Inherited from HeliBoard)](#core-features-inherited-from-heliboard)
- [Contributing](#contributing-)
   * [Reporting Issues](#reporting-issues)
   * [Translations](#translations)
   * [To Community Creation](#to-community)
   * [Code Contribution](CONTRIBUTING.md)
- [License](#license)
- [Credits](#credits)

# Key Features (Unplugged Additions)

UP Keyboard builds upon HeliBoard with the following major enhancements:

## Built-in Offline Voice Recognition
- **Local Speech-to-Text**: Integrated Whisper/GGML engine for completely offline voice recognition
- **No Cloud Dependencies**: All voice processing happens on-device, ensuring complete privacy
- **System-wide Service**: Acts as a system voice recognition service, allowing other apps to leverage UP Keyboard's voice input capabilities
- **Visual Feedback**: Custom voice input UI with waveform visualizer, countdown timer, and clear status indicators

## Advanced Voice Features
- **Voice Activity Detection (VAD)**: Intelligent detection of speech start/end for improved voice input experience
- **Multilingual Recognition**: Support for multiple languages with automatic language detection
- **Configurable Voice Settings**: Fine-tune voice input behavior to match your preferences

## Flexible Language Settings
- **Dual Settings Modes**: Choose between using system languages or manual language selection
- **Language Detection**: Improved language detection and handling for multilingual typing

## Enhanced Layouts
- **iOS-style Keyboard Layouts**: Optional iOS-style functional and symbol keyboard layouts with prominent emoji and language switch keys

# Core Features (Inherited from HeliBoard)
<ul>
  <li>Add dictionaries for suggestions and spell check</li>
  <ul>
    <li>build your own, or get them  <a href="https://codeberg.org/Helium314/aosp-dictionaries#dictionaries">here</a>, or in the <a href="https://codeberg.org/Helium314/aosp-dictionaries#experimental-dictionaries">experimental</a> section (quality may vary)</li>
    <li>additional dictionaries for emojis or scientific symbols can be used to provide suggestions (similar to "emoji search")</li>
    <li>note that for Korean layouts, suggestions only work using <a href="https://github.com/openboard-team/openboard/commit/83fca9533c03b9fecc009fc632577226bbd6301f">this dictionary</a>, the tools in the dictionary repository are not able to create working dictionaries</li>
  </ul>
  <li>Customize keyboard themes (style, colors and background image)</li>
  <ul>
    <li>can follow the system's day/night setting on Android 10+ (and on some versions of Android 9)</li>
    <li>can follow dynamic colors for Android 12+</li>
  </ul>
  <li>Customize keyboard <a href="https://github.com/Helium314/HeliBoard/blob/main/layouts.md">layouts</a> (only available when disabling <i>use system languages</i>)</li>
  <li>Customize special layouts, like symbols, number,  or functional key layout</li>
  <li>Multilingual typing</li>
  <li>Glide typing (<i>only with closed source library</i> ☹️)</li>
  <ul>
    <li>library not included in the app, as there is no compatible open source library available</li>
    <li>can be extracted from GApps packages ("<i>swypelibs</i>"), or downloaded <a href="https://github.com/erkserkserks/openboard/tree/46fdf2b550035ca69299ce312fa158e7ade36967/app/src/main/jniLibs">here</a> (click on the file and then "raw" or the tiny download button)</li>
  </ul>
  <li>Clipboard history</li>
  <li>One-handed mode</li>
  <li>Split keyboard</li>
  <li>Number pad</li>
  <li>Backup and restore your settings and learned word / history data</li>
</ul>

For more information about HeliBoard's core features, including FAQ and detailed documentation, please visit the [HeliBoard wiki](https://github.com/Helium314/HeliBoard/wiki)

# Contributing ❤

UP Keyboard is a fork of HeliBoard with Unplugged-specific enhancements. We welcome contributions!

## Reporting Issues

Whether you encountered a bug, or want to see a new feature in UP Keyboard, you can contribute to the project. Your help is always welcome!

**For UP Keyboard specific features** (voice recognition, Unplugged-specific improvements):
- Please open issues in this repository

**For core keyboard functionality** inherited from HeliBoard:
- Consider reporting to the [upstream HeliBoard project](https://github.com/Helium314/HeliBoard/issues) so all users can benefit

Before opening a new issue, be sure to check the following:
 - **Does the issue already exist?** Make sure a similar issue has not been reported by browsing existing issues. Please search open and closed issues.
 - **Is the issue still relevant?** Make sure your issue is not already fixed in the latest version.
 - **Is it a single topic?** If you want to suggest multiple things, open multiple issues.
 - **Did you use the issue template?** It is important to make life of our kind contributors easier by avoiding issues that miss key information to their resolution.

Note that issues that ignore part of the issue template will likely get treated with very low priority, as often they are needlessly hard to read or understand (e.g. huge screenshots, not providing a proper description, or addressing multiple topics). Blatant violation of the guidelines may result in the issue getting closed.

If you're interested, you can read the following useful text about effective bug reporting (a bit longer read): https://www.chiark.greenend.org.uk/~sgtatham/bugs.html

## Translations
Translations can be added using [Weblate](https://translate.codeberg.org/projects/heliboard/). You will need an account to update translations and add languages. Add the language you want to translate to in Languages -> Manage translated languages in the top menu bar.
Updating translations in a PR will not be accepted, as it may cause conflicts with Weblate translations.

Some notes on translations
* when translating metadata, translating the changelogs is rather useless. It's available as it was requested by translators.
* the `hidden_features_message` is horrible to translate with Weblate, and serves little benefit as it's just a copy of what's already in the wiki: https://github.com/Helium314/HeliBoard/wiki/Hidden-functionality. It's been made available in the app on user request/contribution.

## To Community
You can share your themes, layouts and dictionaries with other people:
* Themes can be saved and loaded using the menu on top-right in the _adjust colors_ screen
  * You can share custom colors in a separate [discussion section](https://github.com/Helium314/HeliBoard/discussions/categories/custom-colors)
* Custom keyboard layouts are text files whose content you can edit, copy and share
  * this applies to main keyboard layouts and to special layouts adjustable in advanced settings
  * see [layouts.md](layouts.md) for details
  * You can share custom layouts in a separate [discussion section](https://github.com/Helium314/HeliBoard/discussions/categories/custom-layout)
* Creating dictionaries is a little more work
  * first you will need a wordlist, as described [here](https://codeberg.org/Helium314/aosp-dictionaries/src/branch/main/wordlists/sample.combined) and in the repository readme
  * the you need to compile the dictionary using [external tools](https://github.com/remi0s/aosp-dictionary-tools)
  * the resulting file (and ideally the wordlist too) can be shared with other users
  * note that there will not be any further dictionaries added to this app, but you can add dictionaries to the [dictionaries repository](https://codeberg.org/Helium314/aosp-dictionaries)

## Code Contribution
See [Contribution Guidelines](CONTRIBUTING.md)

# License

UP Keyboard (as a fork of HeliBoard, which is itself a fork of OpenBoard) is licensed under GNU General Public License v3.0.

 > Permissions of this strong copyleft license are conditioned on making available complete source code of licensed works and modifications, which include larger works using a licensed work, under the same license. Copyright and license notices must be preserved. Contributors provide an express grant of patent rights.

See repo's [LICENSE](/LICENSE) file.

Since the app is based on Apache 2.0 licensed AOSP Keyboard, an [Apache 2.0](LICENSE-Apache-2.0) license file is provided.
The icon is licensed under [Creative Commons BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). A [license file](LICENSE-CC-BY-SA-4.0) is also included.

## Third-Party Libraries for Voice Features

The voice recognition and voice activity detection features use the following libraries with their respective licenses:

**Speech Recognition:**
- **OpenAI Whisper Models** - Pre-trained speech recognition model weights (MIT License)
  - Reference: https://github.com/openai/whisper/blob/main/LICENSE
- **FUTO ACFT-Finetuned Whisper Models** - Optimized for mobile with dynamic audio context (Apache-2.0 License)
  - Reference: https://huggingface.co/collections/futo-org/whisper-acft-667c430f8de3a22b73151d74
  - ACFT optimization: https://github.com/futo-org/whisper-acft
- **Whisper.cpp / GGML** - C/C++ implementation for running Whisper models (MIT License)
  - Reference: https://github.com/ggml-org/whisper.cpp

**Voice Activity Detection:**
- **android-vad by Georgiy Konovalov** - Voice activity detection library (MIT License)
  - Reference: https://github.com/gkonovalov/android-vad
- **WebRTC VAD** - Underlying VAD implementation (BSD-style license)

These third-party libraries retain their original licenses and copyrights.

# Credits

## UP Keyboard Specific
- **Unplugged Team** - Voice recognition integration and Unplugged-specific enhancements
- **OpenAI** - [Whisper](https://github.com/openai/whisper) base models and pre-trained weights for speech recognition
- **FUTO** - [ACFT-finetuned Whisper models](https://huggingface.co/collections/futo-org/whisper-acft-667c430f8de3a22b73151d74) optimized for on-device mobile use with dynamic audio context
- [Whisper.cpp](https://github.com/ggerganov/whisper.cpp) - C/C++ port of Whisper for efficient on-device inference
- [GGML](https://github.com/ggerganov/ggml) - Machine learning library for efficient inference
- [android-vad](https://github.com/gkonovalov/android-vad) by Georgiy Konovalov - Voice activity detection library
- [WebRTC](https://webrtc.org/) - Voice activity detection implementation

## HeliBoard and Upstream Projects
- [HeliBoard](https://github.com/Helium314/HeliBoard) - The upstream keyboard project this fork is based on
- Icon by [Fabian OvrWrt](https://github.com/FabianOvrWrt) with contributions from [The Eclectic Dyslexic](https://github.com/the-eclectic-dyslexic)
- [OpenBoard](https://github.com/openboard-team/openboard)
- [AOSP Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)
- [LineageOS](https://review.lineageos.org/admin/repos/LineageOS/android_packages_inputmethods_LatinIME)
- [Simple Keyboard](https://github.com/rkkr/simple-keyboard)
- [Indic Keyboard](https://gitlab.com/indicproject/indic-keyboard)
- [FlorisBoard](https://github.com/florisboard/florisboard/)
- [HeliBoard contributors](https://github.com/Helium314/HeliBoard/graphs/contributors)
