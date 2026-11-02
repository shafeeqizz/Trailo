# Working on Trailo

## Scope and incremental development

Trailo is an Android-first convoy navigation app. This milestone is an app
foundation; maps, navigation, live location, and Firebase are future work.
Implement one small, reviewable vertical slice at a time. Distinguish working
features from plans. Avoid speculative modules, permissions, and dependencies.

Use the existing checkout: cloud tasks are already isolated. Do not create a
worktree unless requested. Use feature branches/PRs; never push directly to main.
Preserve user changes. Do not provision services or publish releases without
authorization.

## Architecture and conventions

- Kotlin, Compose, Material 3, Gradle Kotlin DSL; Kotlin official style.
- Keep one `app` module until a concrete reuse or build boundary justifies more.
- Organize presentation by feature under `ui/<feature>`. Routes obtain ViewModels;
  screens receive immutable state and callbacks. ViewModels own state/actions.
- Keep SDK, location, database, and network operations out of composables.
- Add plain Kotlin domain models/repository contracts and data implementations
  when needed. Domain code must not depend on Android, Maps, or Firebase SDKs.
  Do not add empty layers. Prefer constructor injection before adopting DI tools.
- Synchronous presentation state can use Compose state. Use StateFlow with
  lifecycle-aware collection for future asynchronous features.
- Use string resources and accessible components; support dark mode, large text,
  scrolling, and system insets.
- Pin stable versions in `gradle/libs.versions.toml`. Use the Compose BOM and Kotlin
  Compose compiler plugin. Commit the wrapper and its distribution checksum.

## Security and privacy

- Never commit or log credentials, service-account keys, signing material, local
  SDK paths, `.env`, `secrets.properties`, or Firebase client config files.
- Client API keys are extractable. Restrict by API, app ID, and signing certificate;
  separate development/production projects and quotas. Privileged credentials and
  Routes web-service calls belong on a trusted backend.
- Firebase client configuration is not authorization. Require authentication and
  least-privilege, deny-by-default database rules. Test membership, cross-session
  isolation, revocation, and expiry with emulators. Evaluate App Check.
- Location sharing must be opt-in, scoped to membership, stoppable, and governed
  by retention/deletion rules. Never log coordinates or tokens. Request only
  permissions required by implemented features.
- Preserve TLS/artifact verification; do not suppress checks to make builds pass.

## Validation and handoff

For source/build changes, run from the repository root:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Add meaningful JVM tests for changed ViewModel/domain behavior; cover error and
cancellation paths when relevant. Avoid placeholder arithmetic tests. Add device
tests with Android/SDK integration; JVM tests do not validate rendering or Maps.
For UI changes check light/dark mode, large fonts, rotation, and edge-to-edge on an
API 26+ emulator/device when available. Report exact commands, test counts,
failures, and unrun checks; never claim checks passed without running them.

Before handoff review `git diff --check`, scope, and untracked files for secrets
and generated artifacts. Do not commit SDKs, caches, APKs, or machine paths.
Update documentation with behavior and setup changes.
