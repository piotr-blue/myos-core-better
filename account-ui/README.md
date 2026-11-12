# Shared account UI

Core is the source of this React component package. Both portals consume the same
versioned npm archive; Business must not maintain a second form implementation.
The package owns fields, preview, validation and recoverable creation state.
Portal adapters own generated SDK calls, captured session guards and navigation.
No credentials, media bytes or Core authority are stored by this package.

Pack with `npm pack ./account-ui --pack-destination account-ui`, install the archive
in core-ui and core-web, and vendor that exact archive in Business. Include source
and archive together in reviews. A consuming bundler must support TSX and CSS imports.

`ImagePicker` provides a shared preview, placeholder and file chooser for account
images and User photos. Local selections replace the saved preview until they
are saved or cleared. Logo previews preserve the whole image; User photos use
a circular crop. Portal adapters retain ownership of validation and persistence.
Account creation uses a compact logo field with a 64px preview and explicit
Upload/Change logo actions; the optional field label is separate from its action.

Account creation retains one request through `PENDING` registration and confirms
readiness by repeating that request with bounded backoff. Reload resumes retained
pending or uncertain intents. Images and account selection wait for `READY`;
transient status-check failures do not discard a saved account. Older retained
results without a provisioning field remain compatible. A reloaded logo upload
requires the original image to be selected again; media bytes are never retained.
