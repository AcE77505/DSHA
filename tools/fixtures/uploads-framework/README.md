# Upload / PiP host framework fixture

Run `python tools/test-web-upload-framework.py --jdk <JDK17> --gradle-home <Gradle cache>`.
This is a separate Java-only Gradle 9.3.1 project with Robolectric 4.15.1 and Android 14
framework classes. It does not configure AGP, produce an APK, open a device connection,
or access user conversations.

The runner compiles the current production upload policies and their JUnit tests, then
compiles the current `PictureInPictureFrame`, `WebUploads`, `DocumentStreams` and
`BackupControl` source files into the framework fixture. The real Android View,
ViewGroup, FrameLayout, requestLayout, AssetFileDescriptor offset/length and
CancellationSignal implementations are used.

The insets helper and runtime task gate are explicit fixtures; they do not alter
measure/layout/requestLayout or stream copying. The cache OS/descriptor adapters model
host file open, duplicate and close because Robolectric's default descriptor duplicate
copies bytes to a different file instead of duplicating the descriptor. Those adapters
verify production resource scopes and error handling, **not native Android NOFOLLOW,
file descriptor counts, Binder cancellation transport, ENOSPC or ROM Provider behavior**.
A Provider may ignore cancellation and return later; the test verifies that cancelling
does not wait on the caller and that a late result is closed and rejected.

The fixture verifies frozen PiP geometry, nested same-size requests, idle/resize work
deduplication, AFD ranges, one source-open retry, sanitized missing-source diagnostics,
strict versus optional metadata queries, and source closure on output-open failure.
It does not validate WebView/Gecko pixels, real 5 GB Android uploads, keyboard/insets,
power consumption or the original Android 16 flicker.
