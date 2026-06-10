# Headwind MDM — Custom Android Fork

A powerful open-source Android MDM launcher, forked from [h-mdm/hmdm-android](https://github.com/h-mdm/hmdm-android) with added call whitelisting, a built-in custom dialer, SMS/MMS filtering, and Device Owner enforcement.


---

## What This Fork Adds

This fork extends the upstream Headwind MDM Android client with:

- **Call whitelisting** — incoming calls are screened via `CallScreeningService`; only numbers in the configured whitelist are allowed through
- **Custom dialer** — built-in dialer app that shows all contacts but blocks outgoing calls to non-whitelisted numbers, with auto-formatted phone number input
- **SMS/MMS filtering** — push a whitelist or blocklist to the companion [DPAD-Messaging](https://github.com/UncleAndy123/DPAD-Messaging) app via Device Owner restrictions; messages from non-permitted numbers are silently dropped before storage
- **Device Owner enforcement** — whitelist and dialer default are set silently on enrollment (API 29+); no user prompts needed
- **Offline resilience** — the whitelist is stored locally from the last policy sync and remains enforced even when the server is unreachable

---

## Architecture Overview

### Call filtering — `CallWhitelistManager.java`

Reads the `allowed_numbers` application setting from the locally cached MDM config and checks incoming/outgoing numbers against it. Used by `CallWhitelistScreeningService` (incoming) and `HmdmInCallService` (outgoing). This class is read-only — it has no knowledge of the messaging app.

### SMS/MMS filtering — `SmsFilterManager.java`

Reads three SMS-specific application settings from the locally cached MDM config and pushes them to the DPAD-Messaging app as a `Bundle` via `DevicePolicyManager.setApplicationRestrictions()`. Call `SmsFilterManager.getInstance(context).pushIfNeeded()` wherever config is applied after a server sync.

DPAD-Messaging reads this bundle on every incoming message via `RestrictionsManager` and drops messages that fail the filter before they are written to the system SMS/MMS store.

These two classes are deliberately separate — `CallWhitelistManager` owns call enforcement; `SmsFilterManager` owns the SMS config delivery pipeline.

---

## License

Licensed under the **Apache 2.0 License**. You may fork, modify, and distribute freely, including commercial use, with no copyleft requirement. See `LICENSE` for full terms.

---

## Prerequisites

- Android Studio (latest stable)
- Android SDK
- A signing keystore for APK release builds
- A running Headwind MDM server (see [hmdm-server](https://github.com/h-mdm/hmdm-server) or the Docker setup below)
- ADB installed and on your PATH for device enrollment

---

## Development Setup

1. Clone this repository
2. Open the project directory in Android Studio using the default import settings
3. Let Gradle sync complete — if you are on a corporate network and see SSL errors, see the note above
4. The project is ready to build

---

## Debugging on a Device

1. Connect the device by USB and enable USB debugging
2. Click **Run > Run 'app'** in Android Studio
3. Once the app is running, grant Device Owner rights via ADB (required for full lockdown and silent permission grants):

```bash
adb shell
```

Then in the ADB shell:

```bash
dpm set-device-owner com.hmdm.launcher/.AdminReceiver
```

> **Emulator tip:** Device Owner mode prevents Android Studio from hot-swapping the APK during development. For fast iteration, keep a snapshot without Device Owner active and restore it between runs. Use the emulator's Extended Controls → Snapshots to manage this.
>
> If you need to clear Device Owner to unblock the Run button:
> ```bash
> adb shell dpm clear-device-owner
> ```

---

## Configuring the Call Whitelist (Server Side)

After enrolling the device, configure the call whitelist in the Headwind MDM admin panel:

1. Go to **Configurations → your config → Application Settings**
2. Add a new entry:

| Field   | Value                                                        |
|---------|--------------------------------------------------------------|
| Name    | `allowed_numbers`                                            |
| Type    | String                                                       |
| Value   | `+14155551234,+14155555678` (comma-separated E.164 format)  |
| Package | `com.hmdm.launcher`                                          |

3. Save. The device will pick this up on its next config sync (typically within a few minutes). You can also trigger a sync manually from the device list.

> **Wildcard:** Set the value to `*` to allow all calls (disables filtering without removing the setting).

> **Emulator testing:** The emulator reports a fake number when you place a test call from Extended Controls. Check your Logcat for the exact string the screening service sees:
> ```
> D/CallScreening: Screening call from: XXXX
> ```
> Use that exact value in `allowed_numbers` when testing on emulator.

---

## Configuring SMS/MMS Filtering (Server Side)

SMS and MMS filtering is delivered to the [DPAD-Messaging](https://github.com/UncleAndy123/DPAD-Messaging) companion app via Device Owner application restrictions. The launcher reads these settings and pushes them on every config sync.

Add the following entries in **Configurations → your config → Application Settings**, all with package `com.hmdm.launcher`:

| Name                 | Type   | Description                                                         |
|----------------------|--------|---------------------------------------------------------------------|
| `sms_filter_mode`    | String | `off` \| `whitelist` \| `blocklist` — controls which list is active |
| `sms_allowed_numbers`| String | CSV of E.164 numbers permitted in whitelist mode; `*` allows all    |
| `sms_blocked_numbers`| String | CSV of E.164 numbers denied in blocklist mode                       |

### Filter behaviour reference

| Mode        | `sms_allowed_numbers` | `sms_blocked_numbers` | Result                                        |
|-------------|----------------------|-----------------------|-----------------------------------------------|
| `off`       | (any)                | (any)                 | All SMS/MMS pass through — no filtering       |
| `whitelist` | `+15551234,+15555678`| (ignored)             | Only those numbers deliver messages           |
| `whitelist` | `*`                  | (ignored)             | All numbers pass (wildcard — same as `off`)   |
| `blocklist` | (ignored)            | `+15550000000`        | That number is silently dropped; all else pass|
| `blocklist` | (ignored)            | (empty)               | No numbers blocked — effectively same as `off`|

### How the push works

`SmsFilterManager.pushIfNeeded()` is called wherever config is applied after a server sync. It reads the three keys above from the locally cached MDM config, builds an Android `Bundle`, and calls `DevicePolicyManager.setApplicationRestrictions()` targeting `com.dpad.messaging`. DPAD-Messaging reads the bundle on every incoming message via `RestrictionsManager` and drops non-permitted messages before they reach the system SMS/MMS store — they are never stored or shown.

> **Requires Device Owner.** The launcher must be Device Owner (set via ADB at enrollment) for `setApplicationRestrictions()` to succeed. On non-Device-Owner builds the push silently no-ops and SMS filtering is disabled.

> **Both apps must be signed with your key.** Since DPAD-Messaging is a fork you build and sideload, ensure both APKs are signed with the same release keystore so that the restriction delivery cannot be spoofed by a different messaging app.

---

## Building a Release APK

### In Android Studio

1. Set up a signing keystore if you do not already have one: **Build → Generate Signed Bundle / APK → Create new keystore**
2. Select **Build → Generate Signed Bundle / APK**
3. Choose **APK**, select your keystore, and choose a destination
4. The signed APK will be saved to your chosen location

### From the Command Line

1. Install the Android SDK (via Android Studio or standalone download)
2. Create `local.properties` in the project root:

```
sdk.dir=/path/to/android/sdk
```

3. Run:

```bash
./gradlew assembleRelease
```

4. Find the output APK at:

```
app/build/outputs/apk/release/app-release.apk
```

> **Note:** The command-line build requires Gradle 5.1.1 or later. On Windows, use `gradlew.bat`.

---

## Device Enrollment with Your Custom APK

Because this is a fork with a custom signing key, devices must be enrolled using your APK rather than the official Headwind launcher. Any devices previously enrolled with the upstream APK will need to be factory reset and re-enrolled.

Enrollment steps:

1. Factory reset the target device (or use a fresh device)
2. Sideload your APK, or host it on your Headwind server and use the QR code enrollment flow
3. Grant Device Owner via ADB as shown in the Debugging section above
4. The device will contact your server, pull the configuration, and enforce the whitelist automatically
5. If DPAD-Messaging is installed, SMS/MMS filter config is pushed automatically on the first successful config sync

---

## Building the Library Module

If you need to build only the `lib` module (e.g., to use it as a dependency in another project):

1. Select the `lib` item in the Android Studio project tree
2. Select **Build → Make Module 'lib'**
3. Find the output AAR at:

```
lib/build/outputs/aar/
```

---

## Keeping Up with Upstream

This fork tracks [h-mdm/hmdm-android](https://github.com/h-mdm/hmdm-android). To pull upstream security and feature updates:

```bash
git remote add upstream https://github.com/h-mdm/hmdm-android.git
git fetch upstream
git merge upstream/master
```

Resolve any conflicts in the custom files (`CallWhitelistManager.java`, `SmsFilterManager.java`, `CallWhitelistScreeningService.java`, the custom dialer activity, and `AndroidManifest.xml`) before rebuilding.

---

## Related

- [DPAD-Messaging (fork)](https://github.com/UncleAndy123/DPAD-Messaging) — the companion SMS/MMS app that enforces the filter config pushed by this launcher
- [Headwind MDM Server (Docker setup)](https://github.com/h-mdm/hmdm-server) — the companion server this client connects to
- [Upstream Android client](https://github.com/h-mdm/hmdm-android) — original source this fork is based on
- [Headwind MDM docs](https://h-mdm.com/documentation/) — official documentation for the server and admin panel