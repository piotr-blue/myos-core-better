# Published Hello clause through the durable host

This example mounts byte-identical Activation and Connections publications, plus their Agreements
and Parties imports. It installs clauses, requires owner consent, binds a Hello action to a root
field, validates its input, and writes an audit field after a successful action. The explicit owner
channel and test reader key are local test transport bindings; this is not production identity or
key management.

From the project directory, build the distribution and start it with:

```sh
MYOS_RUNTIME="$PWD/examples/hello/runtime.json" build/install/myos-core-better/bin/myos-core-better serve
python3 scripts/demo_hello.py
```

Set `MYOS_TABLE` and `AWS_REGION` to use the selected DynamoDB table. Without `MYOS_TABLE`, the process
uses ephemeral memory. The normal deployment remains private behind SSM. The script uses only the
HTTP API, checks successful consent and Hello, verifies rejection leaves the state unchanged, and
writes all inputs, states and events to an ignored report.

`localBlueId` identifies the local Blue body, excluding the host's external embedding links; the
response's `version` is the host document revision. They have different meanings. The example has
no embeddings, so the exact local body is the intended target of its authored consent policy.

A BEX projection can contain undefined schema slots, which differ from JSON null. Reads return
`data` plus `undefinedPaths`, JSON pointers relative to `data`; only positions listed there represent
BEX undefined. Null at any other position remains null. The host preserves this distinction on disk
and restores it for BEX reads. No business field is reserved to encode this metadata. The canonical
runtime checkpoint remains the authority for restoring local execution.

Use `/documents/head` when a client only needs a version and `localBlueId` for an exact-version
request. It does not expand inherited schemas or materialize the document's business read view.
