# Developers UI source adaptation

The Core console uses the MyOS Developers UI in the sibling `myos-java` repository as its source. It is adapted to the Core service's HTTP API and hash routes; the Spring API, TanStack application router, and Business/Bootstrap features are not dependencies of this deployment.

Reused source and visual primitives:
- `apps/myos-blue/src/app/dev/components/dev-layout.tsx`: header, sidebar order, navigation icons, responsive layout, account menu placement.
- `apps/myos-blue/src/app/dev/components/data-table.tsx`: table component.
- `apps/myos-blue/src/app/dev/components/section-header.tsx`: section titles and actions.
- `apps/myos-blue/src/app/dev/documents.$sessionId/components/epoch-navigation.tsx`: previous/next/current epoch navigation semantics.
- `libs/myos-platform-ui/src/components/page-header/page-header.tsx`: title, back button, white card and Gilroy heading styling.
- `libs/ui/src/styles/theme.css`: gray and brand blue palette.
- `libs/myos-platform-ui/src/assets`: MyOS logo, IBM Plex Sans fonts, Gilroy ExtraBold.

The adapted timeline list, timeline details, document detail tabs, code viewers, content retention, account authentication, and API key screens call the deployed Core API. YAML rendering uses `yaml`; the editor supports JSON and YAML. The current release also provides MyOS Events and Webhooks pages using the shared layout, table, form, and content-viewer components. Bootstrap remains outside this deployment.

## 2026-10-03 UI alignment

The stylesheet now uses one consistent set of MyOS design values rather than layered overrides: `#f5f7fa` page background, `#0062ff` links and controls, `#cfd9de` borders, a 72px header, 256px desktop navigation, 32px content gap, 24px card padding, 8px card corners, IBM Plex Sans body text, and 32px Gilroy page titles. Medium and semibold font weights map to the corresponding upstream font files.

Additional source references:
- `apps/myos-blue/src/app/dev/timelines/index.tsx`: selection column, identifier/copy layout, table actions and creation dialog.
- `apps/myos-blue/src/app/dev/components/date-time-cell.tsx`: `dd/MM/yyyy · HH:mm:ss` in the browser timezone.
- `apps/myos-blue/src/app/dev/documents.$sessionId/index.tsx`: two-column definition list, processing controls, tab layout, and delete confirmation.
- `apps/myos-blue/src/app/dev/components/timeline-entries-listing.tsx`: pale-blue entry headers, entry links, and expandable content.

Core-specific adaptations remain explicit: timeline selection copies IDs, and the timeline action opens details because Core has no timeline deletion endpoint. Root document deletion uses the existing API and a confirmation dialog. The Updated field displays a dash when the API does not provide an update timestamp. Agent and Shared are No because this Core deployment does not expose either feature. Bootstrap remains absent; API Explorer, MyOS Events, and Webhooks are available. Entry links open the content detail route, and current/historical state and JSON/YAML remain accessible.

Verification evidence is in `reports/core-ui-alignment.json` and the `reports/core-ui-alignment-*.png` screenshots. Deployment uses `scripts/core-deploy.py --web-only`, which checks that the service distribution is identical before switching the web release.

## 2026-10-03 reference browsing and compact views

Timeline entry cards start closed and fetch content only after opening. The code viewer has a fixed-height virtual window, clickable Blue IDs, full-content copying, JSON/YAML switching, and optional standard-scalar simplification. Document polling reads lightweight status and reloads document content only when the stable version or processing state changes. Source and historical views preserve embedded documents as exact Blue ID references.

The Packages page derives its vocabulary from the selected native runtime, rather than listing every package present in the sibling repository. Language package exports supplement the built-in scalar types; BEX has expression operators, not named document types. Mandate vocabulary is distinguished from the mandates profile's native authorization support.

Verification: `reports/core-ui-browsing-tests.log`, `reports/core-ui-presentation-performance.json`, and `reports/core-ui-browsing-live.json`.

The Documents listing includes all native managed instances, including nested embedded documents. Embedded rows use an Open action; explicitly registered roots retain Delete. The server paginates summaries and caches display names by immutable state identity. Verification: `reports/core-documents-listing-tests.log`, `reports/core-documents-listing-build.log`, and `reports/core-documents-listing-live.json`.

The document viewer opens on current State. Source remains the original admitted document.
Scalar simplification is always enabled; the full-types toggle was removed. Display keys
put name, description and specification first, and contracts last. Collections larger
than ten items use exact collection Blue IDs with counts and a ten-item browsing page.
Collection navigation carries the immutable owner identity and JSON pointer so it can
be reconstructed after a restart. Reference resolution replaces its temporary history
entry, allowing one Back step to return to the originating page.
