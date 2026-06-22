# Contributing

DeadDrop is early-stage software. Small, focused changes are easiest to review.

## Keep the project focused

Please avoid changes that add:

- Android Internet permission;
- accounts, servers, telemetry, ads, contacts, SMS, location, or extra platform permissions;
- persistent audio files in normal operation;
- large frameworks without a clear need;
- broad security claims that the project has not earned.

## Before submitting changes

Run the relevant public build checks:

```bash
gradle --no-daemon assembleRelease
./scripts/build_desktop.sh
```

For packaging changes, also run the affected package helper:

```bash
./scripts/package_android.sh
./scripts/package_desktop.sh
```

For changes to crypto, packet parsing, invites, local storage, identity handling, or packaging, update the relevant docs in `docs/` and `packaging/`.

## Do not commit

- `local.properties`
- signing keys or passwords
- APK/AAB/JAR/ZIP/TAR artifacts
- OAuth tokens or API keys
- real group invites, second factors, or message logs
