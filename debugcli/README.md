# :debugcli

The command line behind the app's ADB/fastboot Console and Commands tabs.
Pure Kotlin/JVM, no Android dependencies: everything here is tested on a plain
JVM against the fake devices in `:fakes`, driving the real `:adb` and
`:fastboot` code.

## Pipeline

typed line → `CommandParser` → `DeviceCommand` → `RiskAssessor` → (confirm) → `CommandRunner` → `TerminalSink`

| File | Purpose |
|---|---|
| `CommandLine.kt` | POSIX-style tokenizer (quotes, escapes, offsets into the raw line) and safe quoting |
| `Commands.kt`, `CommandParser.kt` | `adb ...` and `fastboot ...` lines (prefix optional once connected) into typed commands; unsupported real commands carry a hint about what to do instead |
| `Risk.kt` | Exact judgement for fastboot and tool commands; a heuristic for shell text (splits `;`, `&&`, `\|`, `$(...)`, sees through `su -c`/`sh -c`/`env`, flags `rm -r`, `dd`, `mkfs`, redirects into `/dev`) |
| `CommandRunner.kt` | Executes commands: streaming shell with a remembered working directory (`cd`), exec-out to a file, recursive push/pull with progress and cancel, fastboot flash/erase/etc., error classification (`linkLost` when the connection can no longer be trusted) |
| `Ops.kt` | `AdbOps` / `FastbootOps` interfaces and their native adapters over `AdbConnection` / `FastbootClient` |
| `LocalFiles.kt` | Paths on this phone (`~`, relative to home, absolute) |
| `TextFilters.kt` | Streaming UTF-8 decoding and terminal-escape removal for device output |
| `ConsoleBuffer.kt` | Scrollback with partial lines, wrapping and a line limit |
| `CliCompleter.kt` | Tab completion for tools, verbs, reboot targets, partitions, variables |
| `CommandCatalog.kt` | ~80 ready-made commands with fill-in blanks; `TemplateFiller` quotes every value |
| `CliHelp.kt` | Text behind `help` |

## Behaviour worth knowing

- Every shell command runs in its own device shell, so the working directory is kept by this module and prepended (`cd '<dir>' && ...`). Environment variables are not kept. Input is not forwarded: stdin is closed at once so commands that read it see end of file.
- Risk levels: SAFE runs at once; CAUTION asks with one tap; DANGEROUS asks the user to type a word (partition name, `rm`, `unlock`, ...). The shell judgement is a safety net, not a guarantee.
- Pull/push of folders skip symbolic links and refuse device-supplied names that could escape a folder. A failed or cancelled pull deletes the partial file.
- A cancelled fastboot download leaves the device waiting for data, so the runner reports the link as lost.

## Verification status

| Check | Status |
|---|---|
| 121 JUnit tests: tokenizer, parser, risk, filters, buffer, completer, catalog consistency (every card's template parses and its declared risk equals the assessor's), and end-to-end runner tests through the real `:adb`/`:fastboot` code against `:fakes` with real files | passing when last run, using `kotlinc` and JUnit directly |
| Gradle build of this module | run by CI |
| The Compose screens that use it (`AdbConsoleScreen`, `AdbCommandsScreen`) | built by CI only; not run on a device |
| Real devices | **not tested** |

## Not implemented yet

Remote path completion (needs a device round trip), `adb install` (use `adb push` then `adb shell pm install`), interactive programs (no terminal emulation: `top`, `vi`), forwarding stdin to commands, zip bundles for fastboot.
