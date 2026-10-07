# NFC Pure File Transfer Prototype

An Android research prototype demonstrating direct, **peer-to-peer file transfer over pure NFC** between two Android devices (Android 12+ / API 31+) without using Wi-Fi Direct, Bluetooth, Wi-Fi Hotspots, or cellular internet.

---

## 1. How It Works (Pure NFC APDU Architecture)

Modern Android removed Android Beam in API 29. However, pure NFC data transfer between two phones is achieved at the low-level ISO-DEP (ISO 14443-4) layer by combining two complementary NFC modes:

1. **Receiver (Phone B) &ndash; Host Card Emulation (HCE):**
   - Implements `HostApduService` (`NfcReceiverApduService.kt`).
   - Registers a custom 7-byte Application Identifier (AID): `F0010203040506` in `res/xml/apdu_service.xml`.
   - Listens for command APDUs, validates instructions, buffers incoming chunks, and saves the completed file directly to `Downloads`.

2. **Sender (Phone A) &ndash; Reader Mode:**
   - Uses `NfcAdapter.enableReaderMode` (`MainActivity.kt`) with `IsoDep` flags.
   - Discovers Phone B's emulated card upon contact.
   - Executes `NfcSenderTransceiver.kt` to send command APDUs and wait for Status Word `0x90 0x00` acknowledgements.

---

## 2. Minimal Protocol Specification

The protocol uses ISO/IEC 7816-4 APDUs formatted as:  
`[CLA] [INS] [P1] [P2] [Lc] [Data...] [Le]`

| Step | Command Name | Instruction (`INS`) | Payload Description | Response (`SW1 SW2`) |
|------|-------------|---------------------|----------------------|-----------------------|
| 1 | **SELECT AID** | `0xA4` | `F0010203040506` (AID) | `0x90 0x00` (OK) |
| 2 | **START_TRANSFER** | `0x01` | `[TotalChunks (4B)][FileSize (4B)][NameLength (2B)][FileName (UTF-8)]` | `0x90 0x00` (OK) |
| 3 | **SEND_CHUNK** | `0x02` | `[ChunkIndex (4B)][ChunkLength (2B)][ChunkBytes (≤200B)]` | `0x90 0x00` (ACK per chunk) |
| 4 | **COMPLETE_TRANSFER** | `0x03` | Empty payload | `0x90 0x00` (File saved) |

> **Note on chunk size:** Chunks are fixed to 200 bytes so they easily fit inside the universal 255-byte short APDU limit supported by all Android NFC chipsets (Broadcom, NXP, etc.).

---

## 3. Project Structure

```
NFC_File_Prototype/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml              # NFC feature requirements & HCE Service declaration
│   │   ├── java/com/example/nfcfileproto/
│   │   │   ├── MainActivity.kt              # Simple Compose UI, file selection & NFC ReaderMode
│   │   │   ├── NfcProtocol.kt               # APDU commands, serializers & constants
│   │   │   ├── NfcSenderTransceiver.kt      # Sender IsoDep chunking and transceive loop
│   │   │   ├── NfcReceiverApduService.kt    # Receiver HostApduService (HCE) & file saving
│   │   │   └── ui/theme/                    # Jetpack Compose theme definition
│   │   └── res/
│   │       ├── values/strings.xml
│   │       └── xml/apdu_service.xml         # AID registration for Host Card Emulation
│   └── build.gradle.kts
├── settings.gradle.kts
└── build.gradle.kts
```

---

## 4. Permissions & Manifest Configuration

Only pure NFC permissions are required:
```xml
<uses-feature android:name="android.hardware.nfc" android:required="true" />
<uses-feature android:name="android.hardware.nfc.hce" android:required="true" />
<uses-permission android:name="android.permission.NFC" />
```
*(No Wi-Fi permissions, no Location permissions, no Bluetooth permissions, no Internet permissions).*

---

## 5. Build Instructions

1. Open **Android Studio** (Flamingo, Hedgehog, Iguana, Ladybug, or newer with JDK 17/21).
2. Select **Open an Existing Project** and choose `c:\Users\Aman\OneDrive\Desktop\NFC_File_Prototype`.
3. Let Gradle sync project dependencies.
4. Build APK via menu: **Build > Build Bundle(s) / APK(s) > Build APK(s)**, or run:
   ```bash
   ./gradlew assembleDebug
   ```

---

## 6. Testing Instructions (Using Two Android Phones)

### Preparation
1. Install the debug APK on **both Android devices** (Phone A and Phone B).
2. On both phones, verify that **NFC is enabled** in Android system settings:
   - *Settings > Connected devices > Connection preferences > NFC > ON*.
3. Verify that the **Default payment app / Contactless payment service** allows background/foreground HCE.

### Test Workflow
1. **Open the app** on both Phone A and Phone B.
2. **On Phone B (Receiver):**
   - Keep the app on **📥 Receive Mode** (default).
   - Phone B disables Reader mode polling (preventing RF interference) and claims HCE priority via `CardEmulation.setPreferredService`.
   - Displays: *"Ready to receive via NFC"*.
3. **On Phone A (Sender):**
   - Select **📤 Send Mode** (or click **"Select File"**).
   - Choose any file &ndash; image, PDF, note, etc.
   - For images, an optional **"Optimize image"** toggle is enabled to downscale large camera photos to ~80–120 KB for instant 3-second NFC transfer.
   - Phone A displays: *"Ready to send! Hold phones back-to-back with Receiver phone."*
4. **Initiate Transfer:**
   - Align the phones back-to-back near their NFC coils (typically near the camera bump or upper center).
   - A haptic vibration confirms the connection.
   - Chunks are streamed with automatic retries over ISO-DEP (`INS_SEND_CHUNK`).
5. **Completion:**
   - Both devices vibrate upon completion.
   - Phone A shows: `✅ Transfer Complete!`.
   - Phone B shows: `✅ File Received!`, displays image preview (if image), and provides **"Open File"** and **"Share"** buttons to open in Google Photos, PDF viewer, or system Files!

---

## 7. Resolution for SELECT AID Issue

Previously, transfers between OnePlus (Sender) and Samsung Galaxy (Receiver) failed at the `SELECT AID` step due to:
1. **Dual-Reader RF Collisions**: Both phones had `enableReaderMode` polling simultaneously, preventing Samsung's NFC chip from acting as an HCE card target.
2. **Missing Foreground HCE Priority**: Samsung Wallet/default NFC routing dropped proprietary `other` category AIDs without `CardEmulation.setPreferredService`.
3. **APDU Framing**: Trailing `Le=0x00` in SELECT commands caused incompatibilities with certain chipsets.

These have been resolved:
- **Clean Mode Roles**: Phone B runs purely in Listen/HCE mode when in *Receive Mode*; Phone A runs as *Reader* when in *Send Mode*.
- **Dynamic HCE Priority**: `CardEmulation.setPreferredService` forces direct AID routing to `NfcReceiverApduService`.
- **Universal SELECT Framing & Retries**: Robust Case 3 APDU formatting with fallback, delay stabilization, and per-chunk packet retries.
- **Direct View/Share**: Integrated `FileProvider` so received images and PDFs open directly in system viewers.
