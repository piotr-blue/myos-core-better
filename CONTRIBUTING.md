# Working on MyOS Core Better

Use JDK 25 and the checked-in Gradle wrapper. The build is a composite: it expects the library
checked out beside this repository as `../blue-java-better` (or pass `-PblueLibrary=<path>`).

```bash
./gradlew format
./gradlew check
python3 -m unittest discover -s scripts -p 'test_*.py'
```

`check` compiles the service, its tests and the qualification tools with `-Xlint:all -Werror`,
runs the Java suite and verifies formatting. The SDK and console have their own gates; see
[the test strategy](docs/TESTING.md) for those commands and for which suite owns which guarantee.
Ordinary gates never contact AWS.

## Conventions

The Java conventions are the same as the library's
([its CONTRIBUTING](../blue-java-better/CONTRIBUTING.md)):

- Brace every `if`, `else`, `for` and `while` body.
- Import each type and static member by name; no on-demand (`.*`) imports. Files that use the
  AWS SDK, Apache HttpClient, Nimbus JOSE, Commons FileUpload or Thumbnailator still carry
  on-demand imports from before this rule; expand them when you next touch one.
- Mark every override with `@Override`.
- Suppress a lint warning only on the one method or variable that needs it, never on a class.
- Keep host records and storage formats out of Blue: authored behaviour belongs in Blue/BEX
  documents and published packages, not in Java.

Put a new test in the suite that owns the boundary it exercises. Performance thresholds do not
belong in correctness tests; assert structural read counts, exact results and manual clocks.
Large traces, recordings and generated reports belong under ignored `build/` output.

## Before opening a pull request

Run the three commands above. If you changed the SDK or the console, also run their gates from
`docs/TESTING.md`. State in the description what you ran and anything you could not run.

Run `./gradlew commandReplayProbe -PfirstSeed=7 -PseedCount=3 -PcommandsPerSeed=12` to exercise concurrent command replay. Each seed races two to four independent hosts on the same first command, interleaves the remaining commands in different orders, and reopens every recorded result on a fresh host. The expected final count and committed version depend only on the number of distinct commands. A failing line or assertion gives the seed for reproduction.

To exercise independent DynamoDB clients, add `-PdynamoEndpoint=http://127.0.0.1:8000 -PdynamoTable=blue-quality-race-local`. Create the local table first with the key and index definitions in `infra/stack.json`. Each invocation uses a fresh account namespace and each host opens its own DynamoStore. The default uses an in-memory store shared by those hosts.

## Check production sources with Error Prone

Run `./gradlew errorProne` with JDK 25. CI uses the pinned compiler plug-in’s default error-level checks. To review its lower-severity suggestions as well, run `./gradlew errorProne -PerrorProneWarnings=true`. Both modes compile into a separate analysis directory. Ordinary builds and shipped artifacts use the normal Java compiler inputs. Review the responsible API or invariant before adding a suppression. [Compiler checks and severities](https://errorprone.info/docs/flags)
