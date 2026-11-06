# AxGram Android 12.10.0

The 1271 release APK targets arm64-v8a and uses the :TMessagesProj module.
Clone with recursive submodules, then use JDK 17, the Gradle wrapper and the
SDK, NDK and CMake versions configured in the project. Configure your own
Telegram app credentials and Android signing settings.

The native source is under TMessagesProj/jni, with pinned submodules and
dependency build scripts under its third_party and ffmpeg directories.
The release used a previously built arm64-v8a libtmessages.49.so with 16 KiB
ELF segment alignment. The packaging invocation set NATIVE_TARGET=arm64-v8a,
ran :TMessagesProj:assembleRelease, and used a Gradle init script to set
release jniLibs to the prebuilt directory, restrict the ABI to arm64-v8a,
disable external native build tasks and clear release debuggable flags.
For a normal source build, configure and build the native dependencies and
use the configured CMake integration. A byte-for-byte rebuild of the signed
release from a fresh checkout has not been verified.
