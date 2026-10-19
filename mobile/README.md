# MyMoney for phones

The phone half of MyMoney sync (see [`../docs/SYNC-DESIGN.md`](../docs/SYNC-DESIGN.md)):
enter transactions on the go, see balances, and sync with MyMoney on the computer
through an encrypted mailbox on the NAS.

- `shared/` is Kotlin Multiplatform: the sync code (encryption, the WebDAV mailbox,
  snapshots, pending changes) and the screens (Compose Multiplatform). It's written
  once for Android now and iOS later.
- `androidApp/` is the Android app around it: QR scanning, the encrypted state file
  (key in the Android Keystore), and background sync (WorkManager, about every 15 minutes).

## Building

You need the Android SDK (Android Studio installs it in `~/Android/Sdk`; `local.properties`
points at it) and a JDK. Use **Java 26**, the same JDK as the desktop build, so the
end-to-end test can load the desktop's classes:

```bash
cd mobile
JAVA_HOME=/usr/lib/apache-netbeans/jdk ./gradlew :androidApp:assembleDebug
```

The app is `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

## Testing

```bash
JAVA_HOME=/usr/lib/apache-netbeans/jdk ./gradlew :shared:jvmTest
```

- `PhoneTest`: the phone's own logic, including reading a file the desktop encrypted.
- `DesktopInteropTest`: the **real desktop sync code** and the phone code syncing through
  one folder (adds, edits, deletes, transfers, conflicts, refusals, re-pairing). It runs
  when the desktop has been built (`mvn package` in the folder above), using its
  `target/MyMoney.jar`.

## Installing on a phone

Either:

- **USB:** on the phone, turn on *Settings → About phone → tap Build number 7 times*, then
  *Developer options → USB debugging*. Plug it in, then:
  `~/Android/Sdk/platform-tools/adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- **Copy the file:** put the `.apk` on the phone, open it, and allow *Install unknown apps*
  for the app you opened it with.

It's signed with this computer's debug key (`~/.android/debug.keystore`). An update installs
over the old one only if it's signed with the same key, so keep that file (it's in your
home folder, not the repository).

Debug builds allow plain HTTP (for testing against the computer); release builds will
need HTTPS.
