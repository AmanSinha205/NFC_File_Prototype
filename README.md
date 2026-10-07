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
2. **On Phone A (Sender):**
   - Click **"Select File"**.
   - Choose a small test file (e.g., a `.txt` note, small `.json`, or small image under ~100–300 KB).
   - Phone A displays: *"File ready. Bring phones back-to-back to transfer via NFC."* with progress indicator set to 0%.
3. **On Phone B (Receiver):**
   - Keep the app open on the screen. The receiver card indicates: *"Idle (Ready to receive via NFC)"*.
4. **Initiate Transfer:**
   - Bring Phone A and Phone B back-to-back so their NFC antennas align (usually located near the rear camera bump or center back).
   - **Hold both devices still and steady** for a few seconds.
   - You will see the chunk counter increment rapidly on both screens (`Receiving chunk 12/50`, etc.).
5. **Completion:**
   - Phone A displays: `✅ Transfer Complete! (X bytes sent)`.
   - Phone B displays: `✅ File Saved: filename (X bytes)` and provides the exact file path in the `Downloads` directory (`Android/data/com.example.nfcfileproto/files/Download/`).
