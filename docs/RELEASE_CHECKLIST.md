# Release checklist

For whoever cuts a public build. The first section needs something only the repository owner can do; the rest records what the repository already covers and what is still open.

## Owner actions before the first stable release

1. **Create a release keystore and add it as repository secrets.**

   ```bash
   keytool -genkeypair -v -keystore bareflash-release.jks -alias bareflash \
     -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 bareflash-release.jks     # value for KEYSTORE_BASE64
   ```

   In GitHub: Settings, Secrets and variables, Actions, then add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`bareflash` above), and `KEY_PASSWORD`. Keep the `.jks` file and both passwords in at least two places outside the repository and never commit them. If the key is lost, installed copies can never be updated. Publish the certificate fingerprint (`keytool -list -v -keystore bareflash-release.jks`, SHA-256 line) in the release notes so users can verify what they install.

2. **Test on real hardware** and fill in the matrix below. Each engine is its own code path, so one device per engine is the minimum, and different Android versions matter for USB permissions, notifications, and storage access.

3. **Get the Tests workflow green** on `test` and `main` (Actions, Tests).

4. **Release in two steps.** Run Build & Release as `1.0.0-beta.1` with "Mark as pre-release" ticked, collect reports, then run it again as `1.0.0` once nothing serious is open. The workflow will not publish a stable release without the secrets from step 1.

5. **Decide what the README promises.** It currently says no further updates are planned and that fixes should go to a fork. That is a legitimate position, but say it on the release page too so nobody expects support.

## Device test matrix

Fill in as devices are tested. "Read" means a partition dump that matches its checksum; "Write" means a write or flash that left the device booting.

| Engine | Device (model, chipset) | Android | Read | Write / flash | Notes | Tester, date |
|---|---|---|---|---|---|---|
| MediaTek | Galaxy A10s SM-A107F (MT6765) | | Used in the original recovery (see README) | Used in the original recovery (see README) | | |
| MediaTek | | | | | | |
| Qualcomm | | | | | Never run on Qualcomm hardware yet (docs/QDL_ENGINE.md) | |
| Samsung | | | | | docs/BROKKR_AND_USB.md lists real Odin flashes as unverified | |

## Already handled in the repository

- GPL-3.0 `LICENSE`, `NOTICE.md` for bundled components, in-app credits under Settings, About, and a source link in every release's notes (GPL corresponding-source requirement).
- First-launch risk screen (Exit / I understand) before any USB access, a review-and-confirm step before destructive commands, and a README risk and no-warranty section.
- Android backup disabled; permissions explained in the README.
- The release workflow refuses a stable release signed with the debug key.
- JVM unit tests for pure logic (`app/src/test`), run by the Tests workflow. They cover the GPT table parser, QDL command planning, dump free-space estimates, notification and prompt formatting.

## Known gaps

- **No on-device tests.** Unit tests cover logic only. The terminal, settings screens, tutorial, notifications, and Dynamic Island have been built but not exercised automatically; a device pass is the only check.
- **Dump integrity has no unit tests yet.** Checksums, per-region hashes, and blank-block detection (`DumpIntegrity`) are the code that decides whether a backup can be trusted, and they are the best next candidate for tests.
- **An intermittent USB handshake failure was seen earlier and its cause was never confirmed.** The cancel-race fix made at the time was a best guess. Ask beta testers to attach the app log when they report USB problems.
- **`targetSdk` is 34 while `compileSdk` is 36.** That is fine for sideloading. Raising it changes runtime behaviour (foreground services, storage, notifications) and needs a full device pass. Google Play is not a target: `MANAGE_EXTERNAL_STORAGE` is restricted there.
- **Minification is off** (`isMinifyEnabled = false`), so the APK is larger than necessary. Turning on R8 needs keep rules for the JNI bridges, Chaquopy, and anything reached by reflection, and a full test pass; do not enable it casually.
- **Hardware coverage is narrow** (see the matrix above).
