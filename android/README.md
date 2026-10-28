# Experimental Compressor for Android

Native Kotlin, Jetpack Compose Material 3 Expressive, and the repository's C11 codec through JNI. Only monochrome light and dark modes are offered; the first launch follows the system appearance, and a mode button remembers the chosen appearance. No dynamic color, banner, network access, analytics, or account is included. The supplied SVG is used for adaptive, monochrome themed, and legacy launcher icons.

## Build

Open this folder in Android Studio, with JDK 17 or newer. Set `sdk.dir` in the untracked `local.properties`, or set `ANDROID_HOME`. The project uses Android SDK 37, NDK 27.2.12479018, and CMake 4.1.2. Gradle downloads the pinned build and UI dependencies; the installed app does not download anything.

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Minimum Android version is 6.0 / API 23. Target is API 37. ARMv7, ARM64, and x86-64 are included. The native library is built with flexible page-size support for 16 KB devices. Material 3 Expressive uses the pinned 1.5.0-alpha18 release.

## Flows

- Share one image to Experimental Compressor using Android's image share intent, or choose **Pick image**. A result dialog reports the original file size, decoded pixel size, EXTC file size, conversion duration including import/decode, and dimensions. Choose **Save**, **Copy**, or **Discard**. File size increases are reported honestly.
- **Copy** puts a granted content URI on Android's clipboard, not a text path or Base64 data. The receiving app must support pasting files. Copied files remain in a private sharing cache for up to seven days and are removed on a later launch after expiry. Discarded files are deleted immediately.
- **Convert folder** scans images recursively, then offers only **Cancel** or **Select folder** for the output. Folder structure is preserved, output collisions receive numeric suffixes, and each file is converted sequentially. Failed images are counted and skipped. Cancellation interrupts the current native conversion, removes its incomplete output, and preserves completed files. Keep the app open while a batch runs; it is not a scheduled background job. Rotation retains the active task.
- **Open EXTC** or an external `ACTION_VIEW` opens a simple viewer with pinch zoom, double-tap zoom, and Fit. The manifest registers EXTC MIME types, generic binary files, and EXTC filename patterns. The actual header is always checked; Android file managers still control which handlers they offer for their content URIs.

System document/folder pickers grant access to selected items. Broad storage permissions and all-files access are not requested. Unsupported or unreadable files produce an error dialog.

The image import uses Android's available bitmap decoders, applies EXIF orientation, and encodes grayscale or RGB pixels. Transparent areas are composited onto white; alpha and metadata are not preserved. Input images are never silently resized. The app enforces a device-memory-dependent limit, capped at 16 megapixels, and a 512 MB input file limit. A smaller image is required when those limits are exceeded. The desktop CLI retains its own larger limits.

## Format and integration

The JNI library includes `src/lib/codec.h` from the repository root. It writes EXTC v3 image containers and reads v1, v2, and v3. Unknown container versions and non-image media types fail before image allocation. CRC validation detects damaged/truncated pixel payloads. A mutex serializes the C minimizer across native calls; a cancellation callback observes an `AtomicBoolean` between block rows.

`NativeCodec.compress(Bitmap, outputPath, cancellation, sourceBytes)` writes an EXTC file. `NativeCodec.decompress(inputPath, cancellation, maxPixels)` returns an ARGB bitmap. No copy of the compression algorithm is maintained in Kotlin.

Instrumentation tests exercise real JNI round trips, v1 compatibility, unsupported headers, checksums, memory limits, cancellation, share/view intents, result actions, folder conversion, and Storage Access Framework saving. A debug-only document provider supplies deterministic test folders; it is disabled by default and never included in release builds.

## Validation

Built the debug APK for ARM64, ARMv7, and x86-64. All five instrumentation tests passed on the Android Studio Android 17 / API 37 emulator. Android lint completed with zero errors; remaining warnings concern pinned dependency upgrades and backup configuration for older Android versions. ARM64 ELF load segments use 16 KB alignment. Desktop C v2 RGB round trips, v1 backward compatibility, and rejection of unknown versions/media/flags/header sizes were also checked.

Android 6.0 is the declared minimum supported platform; older Android releases have not yet been exercised in a separate emulator/device matrix. iOS has not been built.

The viewer reports Original file, Decoded pixels, and EXTC file sizes. V3 files retain the encoded input file size; v1/v2 show Unavailable for that value. Full block encoding cost is checked against raw storage; memory limits do not guarantee a file-size reduction over PNG or JPEG. Toolbar and dialog action icons have TalkBack descriptions and long-press tooltips.

## Signed release

`build-release.ps1` builds an optimized, non-debuggable APK, aligns it for 16 KB pages, signs it, and verifies the signature. Invoke it with a private file containing the keystore password:

```powershell
.\build-release.ps1 -PasswordFile <private-password-file>
```

The default key is stored outside this repository at `%LOCALAPPDATA%\EXTC\signing\release.p12`, with alias `extc-release`. Keep this key and its password for future updates; a different key cannot update an existing installation. Passwords are read directly by signing tools from the private file and are never written into Gradle configuration. Keystores are ignored by Git.

Output: `app/build/outputs/apk/release/experimental-compressor-0.2.0-release.apk`. Release builds exclude the debug test document provider and preserve the JNI entry points during R8 shrinking.
