# External setup for future features

The foundation works without Google credentials. These are future prerequisites,
not completed integrations. Add SDKs/configuration only alongside a real feature.

## Google Maps SDK for Android

1. Confirm the final application ID (currently `com.trailo.app`). Create separate
   development/production Cloud projects with billing, quotas, and budget alerts.
2. Enable Maps SDK for Android. Restrict a dedicated key to that API and to the
   application ID plus SHA-1 signing fingerprints. Register debug certificates
   separately from release/Play App Signing certificates.
3. In the map feature PR, add a stable SDK and supported local-secret mechanism
   such as the Maps Platform Secrets Gradle Plugin. Read the key from ignored
   `secrets.properties` or secure CI input and supply a manifest placeholder.
   This scaffold contains no key placeholder or secret-reading build logic.
4. Verify rendering/authorization on a device with Google APIs/Play services;
   JVM tests cannot validate it.

Android keys are extractable from APKs. Local-file secrecy does not replace key
restrictions. Never commit actual keys or print them during build configuration.

Official guide: https://developers.google.com/maps/documentation/android-sdk/start

## Google Routes API

Routes is not required to follow a leader's movement. Establish the use case first.
Web-service credentials belong on a trusted backend with client authentication,
API restrictions, quotas, and rate limits. Never ship backend keys or service-account
credentials in an APK. Review Maps Platform usage/storage terms before retaining
route data.

Official guide: https://developers.google.com/maps/documentation/routes

## Firebase

1. Create separate development/production projects. Register Android using the
   confirmed app ID and relevant signing fingerprints.
2. Choose auth providers and database regions deliberately. Proposed roles:
   Authentication for identity, Realtime Database for ephemeral location/presence,
   Firestore for durable metadata. Validate the design before implementation;
   avoid conflicting membership authorities across databases.
3. Only during integration, put downloaded client `google-services.json` in the
   ignored `app/` directory and add Google Services/Firebase BOM dependencies.
   Client config is not authorization. Keeping it local avoids mixing environments.
   Never include Firebase Admin/service-account keys in an Android client.
4. Deploy deny-by-default rules before clients connect. Require authenticated,
   active membership and validate identities, schema, and timestamps. Commit rules
   and emulator tests for unauthorized/cross-session access, expiry, and revoked
   membership with the feature. Never use public test-mode rules. Evaluate App Check.
5. Define sharing consent, retention/deletion, account deletion, expiry, and offline
   behavior. Minimize location history and never log coordinates or auth tokens.

Use isolated emulators with synthetic data for integration tests, never production.
Emulator configuration/tooling will be added with Firebase integration.

Official docs:

- https://firebase.google.com/docs/android/setup
- https://firebase.google.com/docs/emulator-suite
- https://firebase.google.com/docs/rules

## Android and release prerequisites

The current manifest declares no network or location permissions. Introduce only
permissions required by implemented features, with denial/revocation handling and
visible sharing controls. Background sharing needs separate evaluation of Android
foreground-service restrictions, battery impact, and Play location policies.

Before public release: confirm package ownership, configure secure release signing
and Play App Signing, provide a privacy policy and accurate Data safety disclosures,
verify current target-API requirements, and complete device integration testing.
Keep signing keys/passwords in secure storage, never version control.
