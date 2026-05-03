# Building RootlessJamesDSP — Pitch Shift Fork

This document describes how to build the Android app (both rootless and root variants) and the Magisk/KernelSU module from source.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Android Studio | Hedgehog 2023.1+ | or newer |
| Android NDK | r25c or r26+ | installed via SDK Manager |
| CMake | 3.22.1 | installed via SDK Manager |
| JDK | 17 | bundled with Android Studio |
| Python | 3.8+ | only for rebuilding the module zip |

Make sure **NDK** and **CMake 3.22.1** are installed:
`Android Studio → Settings → Appearance & Behavior → System Settings → Android SDK → SDK Tools`

---

## 1. Clone the repository

```bash
git clone https://github.com/mikroffarad/RootlessJamesDSP-PitchShift
cd RootlessJamesDSP-PitchShift

# Initialize the libjamesdsp submodule (C audio engine source)
git submodule update --init --recursive
```

The submodule fetches [ThePBone/JamesDSPManager](https://github.com/ThePBone/JamesDSPManager) (`extensions` branch) into `app/src/main/cpp/libjamesdsp/`. The CMake build reads DSP sources from `libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/`.

`librubberband` is included as plain source (no submodule) in `app/src/main/cpp/librubberband/` and is compiled as a static library automatically by CMake.

---

## 2. Build the Android app

### Rootless variant (no root required, Android 10+)

Package: `me.timschneeberger.rootlessjamesdsp`

```bash
./gradlew assembleRootlessFullRelease
```

Output:
```
app/build/outputs/apk/rootlessFull/release/
  RootlessJamesDSP-vX.Y.Z-rootless-full-universal-release.apk   ← install this
  RootlessJamesDSP-vX.Y.Z-rootless-full-arm64-v8a-release.apk
  ...
```

Install via `adb install` or transfer to device manually. No Magisk module needed.

### Root variant (Magisk / KernelSU, Android 8+)

Package: `james.dsp`

```bash
./gradlew assembleRootFullRelease
```

Output:
```
app/build/outputs/apk/rootFull/release/
  JamesDSP-vX.Y.Z-root-full-universal-release.apk   ← install this
  JamesDSP-vX.Y.Z-root-full-arm64-v8a-release.apk
  ...
```

**The root APK requires the Magisk/KernelSU module to be installed first** (see section 3). Use the universal APK unless you want per-ABI splits.

### Debug builds

Replace `Release` with `Debug` in the Gradle task name. Debug builds have package suffix `.debug` so they can coexist with release builds on the same device.

---

## 3. Build the Magisk/KernelSU module

The module source is in `ainur_jamesdsp/`. It is based on [MMT-Extended](https://github.com/Zackptg5/MMT-Extended) by zackptg5.

### 3a. Update the APK inside the module

After building the root APK, copy it into the module:

```bash
cp app/build/outputs/apk/rootFull/release/JamesDSP-*-root-full-universal-release.apk \
   ainur_jamesdsp/common/files/JamesDSPManager.apk
```

### 3b. Pack the flashable zip

```python
# Run from ainur_jamesdsp/ directory
python3 - <<'EOF'
import zipfile, os

SRC = 'install-v5.zip'   # previous zip (used as base for META-INF)
DST = 'install-v6.zip'   # output name — increment on each rebuild

# If no previous zip exists, build from scratch:
if not os.path.exists(SRC):
    import shutil
    # List all files to pack
    files = []
    for root, dirs, fs in os.walk('.'):
        dirs[:] = [d for d in dirs if d not in ['.git', '__pycache__']]
        for f in fs:
            if f.endswith('.zip'): continue
            path = os.path.join(root, f)
            arcname = os.path.relpath(path, '.')
            files.append((path, arcname))
    with zipfile.ZipFile(DST, 'w', zipfile.ZIP_DEFLATED) as z:
        for path, arcname in files:
            z.write(path, arcname)
else:
    UPDATE = {'module.prop', 'customize.sh', 'service.sh',
              'common/install.sh', 'common/files/JamesDSPManager.apk'}
    with zipfile.ZipFile(SRC, 'r') as src_zip:
        with zipfile.ZipFile(DST, 'w', zipfile.ZIP_DEFLATED) as dst_zip:
            for name in src_zip.namelist():
                if name in UPDATE:
                    if os.path.exists(name):
                        dst_zip.write(name, name)
                    # else skip (removed files)
                else:
                    info = src_zip.getinfo(name)
                    dst_zip.writestr(info, src_zip.read(name))
            # Add any new files not in the previous zip
            existing = set(src_zip.namelist())
            for root, dirs, fs in os.walk('.'):
                dirs[:] = [d for d in dirs if d not in ['.git', '__pycache__']]
                for f in fs:
                    if f.endswith('.zip'): continue
                    path = os.path.join(root, f)
                    arcname = os.path.relpath(path, '.')
                    if arcname not in existing and arcname not in UPDATE:
                        dst_zip.write(path, arcname)

print(f'Built {DST}: {os.path.getsize(DST):,} bytes')
EOF
```

Flash `ainur_jamesdsp/install-vX.zip` via Magisk or KernelSU. The module will auto-install the APK on first boot.

---

## 4. How the native layer fits together

```
app/src/main/cpp/
├── CMakeLists.txt               ← top-level build: wires everything below
├── libjamesdsp/                 ← git submodule: ThePBone/JamesDSPManager@extensions
│   └── Main/libjamesdsp/jni/   ← C DSP engine sources (EEL, biquad, reverb, …)
├── libjamesdsp-wrapper/         ← C++ JNI bridge
│   ├── JamesDspWrapper.cpp/h   ← main JNI entry points
│   └── PitchShifter.cpp/h      ← pitch shift integration (RubberBand ↔ DSP engine)
├── librubberband/               ← RubberBand library source (plain, no submodule)
│   └── single/RubberBandSingle.cpp  ← single-file build used by CMake
└── libjdspimptoolbox/           ← IIR/EEL toolbox
```

Pitch shift flow: `JamesDspWrapper` calls `PitchShifter` after each DSP processing block. `PitchShifter` wraps `RubberBand::RubberBandStretcher` with `OPTION_PROCESS_REALTIME`.

---

## 5. Version numbers

Defined in `buildSrc/src/main/kotlin/AndroidConfig.kt`:

```kotlin
const val versionName = "1.6.14"
const val versionCode = 51
```

`APPVER` in `ainur_jamesdsp/customize.sh` must match `versionCode`. If you bump the app version, update both files.

---

## 6. Flavors reference

| Gradle task suffix | Package | Root | Min SDK |
|---|---|---|---|
| `RootlessFullRelease` | `me.timschneeberger.rootlessjamesdsp` | No | 29 |
| `RootFullRelease` | `james.dsp` | Yes | 26 |
| `PluginFullRelease` | *(plugin)* | Yes | 26 |

`fdroid` flavor disables Firebase/Crashlytics. `full` flavor includes them.
