# Packages and native capabilities

Set `MYOS_RUNTIME` to an absolute path to a `myos.runtime/1` JSON manifest before starting the service.
The process opens its selected store, verifies all archives and exact execution requirements, and
binds any configured Timeline sources before accepting HTTP requests. Failed startup closes the store. Restart with the same pins to restore those documents. The host does not
fetch packages implicitly and a document cannot install native code.

```json
{
  "format": "myos.runtime/1",
  "features": ["document-dispatch"],
  "packages": [
    {
      "archive": "payment.bluepkg",
      "blueId": "<the trusted semantic package blueId>",
      "mounts": {
        "Payment": [],
        "Agreements": ["Agreements"],
        "Activation": ["Activation"],
        "Parties": ["Activation", "Parties"]
      }
    }
  ]
}
```

This is a configuration template: substitute a real pinned publication and its actual import routes.
`[]` selects the archive root; each route step follows one case-sensitive import declared by that
package. `mounts` keys are consumer-chosen type prefixes, so authoring uses `Payment/Payment Clause`
without changing the package's content identity. Every archive must use the supported Language edition.
Unknown features fail startup.

Package `purpose` defaults to `execution`: the root and every mounted import must have supported
exact execution requirements. Explicit `purpose: "definitions"` loads verified type/content data
without asserting that the host implements that publication's native protocols. It installs no native
code. Documents still execute only supported handlers and capabilities. Use this mode when selecting
the exact [Timeline channel profile](TIMELINE-SERVICE.md) from the broader Coordination publication.
Changing a package to definitions does not implement or qualify the full Coordination protocol.

The `features` list currently accepts only `document-dispatch`. The optional top-level `timelines`
configuration separately installs the published Timeline channel profile with pinned public source
definitions; see [Timeline service configuration](TIMELINE-SERVICE.md). Language, BEX and Contracts are the
bundled deterministic editions. Time, Mandates, MyOS services and the full Coordination native
protocol are not implemented by this host; selecting packages requiring them fails closed.
The `DocumentRuntime` protocol remains document-local; loading packages does not opt into global
proofs or cross-document atomic execution.

Archive paths must be relative to the manifest directory, including after resolving symlinks. There
are bounds on manifest size, archive count and mount count; the library also bounds archive expansion.
Keep runtime configuration in a versioned release, owned by the deployment administrator. No package
publisher is included.
