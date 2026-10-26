# Phonex

Codex on your phone. Describe a web app, and Codex builds it on the phone,
in Arch Linux running next to Android, while you watch it run in a live
preview. No laptop.

Phonex was built in a day at the OpenAI DevDay community hackathon in Berlin,
on top of [Omalauncher](https://github.com/SimonSchubert/omalauncher) by Simon
Schubert, which does the hard part: Arch Linux ARM under proot, without root,
with its own Wayland compositor.

**Phonex is an experiment and isn't maintained.** Omalauncher already runs
Claude Code, Codex, Copilot, opencode and others from its home screen, so the
right home for these ideas is Omalauncher itself, through pull requests.
Issues here may go unanswered.

## What it adds to Omalauncher

- **A studio app for web apps.** Type what you want ("a blog engine"), and
  Phonex creates a project in the guest's `~/projects`, starts a Codex thread
  in it, and streams the conversation as Codex works.
- **A live preview.** Codex writes a `.phonex/dev` script that starts the
  project's dev server; Phonex runs it and shows the app next to the chat.
  Ask for a change and the preview follows.
- **A native Codex client.** Phonex speaks Codex's app server protocol
  (`codex app-server`, JSON-RPC over stdin and stdout) instead of driving a
  terminal, and signs in with Codex's device-code flow.
- **Node.js, npm and Python** installed with Linux, since most web stacks need
  them; Codex adds the rest of a project's tools itself.
- **Staying awake.** A wakelock keeps the CPU on while Linux sets up or Codex
  works, and Phonex asks to be exempt from battery optimization, because some
  ROMs cut a background app's network and Codex's replies never arrive.

Everything else, from the themes to the widgets and the Linux apps, is
Omalauncher's; see [its README](https://github.com/SimonSchubert/omalauncher#readme).

## Building

```sh
./scripts/fetch-proot.sh          # Termux's proot -> app/src/main/jniLibs (needs bsdtar)
./gradlew :app:installDebug
# optional: skip the 830 MB download on the device
adb push ArchLinuxARM-aarch64-latest.tar.gz /sdcard/Android/data/me.paolino.phonex/files/
```

Phonex installs as its own app (`me.paolino.phonex`), next to Omalauncher if
you have it. Needs an arm64 phone with Android 10 or newer, and a ChatGPT
account for Codex.

Tests: `./gradlew :app:testDebugUnitTest`.

## License

MIT, see [LICENSE](LICENSE). Omalauncher's licensing notes apply to the rest
of the code and artwork; [THIRD_PARTY.md](THIRD_PARTY.md) lists the parts that
are not covered.

Phonex is an independent project and is not affiliated with or endorsed by
OpenAI, Omalauncher, Omarchy or Arch Linux.
