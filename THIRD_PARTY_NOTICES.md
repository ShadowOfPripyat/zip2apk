# Third-party notices

## Google Prefab CLI

Zip2APK 0.5.0 embeds `com.google.prefab:cli:2.1.0:all@jar` as a JVM tool used to generate native build-system integration for Prefab packages.

- Project: Google Prefab
- Version: 2.1.0
- Project site: https://google.github.io/prefab/
- License: Apache License 2.0
- Expected SHA-256 of `cli-2.1.0-all.jar`: `e219c8cd6bfd9ff71503a57c6af34b9ba060f03525cc3e58330dee53245a5ed6`

The artifact remains a separate tool asset and is executed by the on-device JDK; it is not modified or dexed into Zip2APK's application classes.
