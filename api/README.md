# Public declaration baseline

`public-api.txt` records the public API of host commit
`c0b686e756bd9cb2b72fe6cb7762b9f727f8be86`: the `Main` class and entry point (three lines
including its JVM descriptor). Internal HTTP, persisted-data and SDK contracts require
their own tests; a green Java API check does not cover them.

`./gradlew checkApi` is included in `check`. The JDK-only checker rejects changed existing
public/protected declarations, generic signatures, JVM descriptors and constants. New abstract
methods on existing types also fail: existing implementers must not acquire new obligations.
Other additions still need compatibility review. Nesting is read from class-file metadata.
Never automatically replace a failed baseline. The library's own baseline covers the embedded
engine API separately. The guard does not prove behavior, overload resolution, annotations,
external dependency changes or every inherited-member/source compatibility case.

`./gradlew testApiChecker` compiles 18 independent fixtures covering successful additions and
incompatible changes; it is part of `check`. Both tool classes are formatted and compiled with
warnings as errors. Expected-rejection diagnostics remain in `build/api-checker-test/`, and the
tools are excluded from runtime artifacts. Keep the tool and fixtures aligned with the library.
