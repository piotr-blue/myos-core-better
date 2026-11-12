# Local HTTPS test fixture

`localhost.p12` is a generated localhost-only certificate and private key for hermetic transport tests.
The public test password is `test-fixture`. It is not a provider signing key or a deployment credential.
Unit tests use a dedicated SSLContext. The separate qualification tool also includes this fixture and
explicitly supplies its trust store to the isolated Main subprocess for a real HTTPS provider test.
The service distribution excludes it; production TLS validation is unchanged. The certificate includes
localhost/127.0.0.1 subject alternatives.
