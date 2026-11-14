# Public declaration baseline

`public-api.txt` records the public API of host commit
`c0b686e756bd9cb2b72fe6cb7762b9f727f8be86`: the `Main` class and entry point (three lines
including its JVM descriptor). Internal HTTP, persisted-data and SDK contracts require
their own tests; a green Java API check does not cover them.

`./gradlew checkApi` is included in `check`. The JDK-only checker rejects changed existing
public/protected declarations, generic signatures, JVM descriptors and constants. Additions
are allowed and still need compatibility review. Never automatically replace a failed
baseline. The library's own baseline covers the embedded engine API separately.
