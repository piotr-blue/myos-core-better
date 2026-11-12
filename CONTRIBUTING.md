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
