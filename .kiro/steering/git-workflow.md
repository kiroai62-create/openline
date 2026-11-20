# Git workflow

- Commit and push every change directly to `main`.
- Do not create feature branches or pull requests for this repository.
- Keep `main` as the only branch on the remote.
- Before pushing, make sure the Android build and tests pass
  (`./gradlew :protocol:test :app:testDebugUnitTest :app:assembleDebug` in `android/`).
