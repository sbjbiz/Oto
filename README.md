# Oto

A lightweight, personal Windows (x64) music player for YouTube Music. No account, no sign-in.


## Get the app

Every push to `main` builds the Windows installer on GitHub Actions. Download it from the
**Releases → v1** page: `Oto-setup-x64.exe` (installer) or `Oto-portable-x64.zip` (no install).

## Build locally (optional)

Needs JDK 21 on Windows:

```
gradlew.bat :desktopApp:packageExe
```

The installer lands in `desktopApp/build/compose/binaries/main/exe/`.

## License

GPL-3.0 — see `LICENSE`. This is a modified version of an upstream GPL-3.0 project (modified 2026).
