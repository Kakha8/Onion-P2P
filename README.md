# Onion-P2P

Onion based Peer to Peer messenger. Currently implements a hello world onion server.

Windows x64 / Java 21+. Tor is bundled in the application resources; no Tor install or runtime download is needed.

Run `./mvnw.cmd javafx:run` (or run `HelloApplication` in your IDE). The window starts a local HTTP server and Tor in the background. Watch the console for:

```text
Onion address: http://<56-character-address>.onion/
```

Open that URL in Tor Browser to see `Hello world!`. Initial Tor connection and onion publication can take a few minutes. Keep the app open while others connect. Closing the window stops the server and its Tor process. No router port forwarding is needed.

The persistent identity and Tor state live under `%LOCALAPPDATA%/Onion-P2P` (home directory fallback). Keep the `service` directory private and back it up to retain the same address. Do not commit it. Only one app instance can use a data directory. For development, override it with the JVM option `-Donion.dataDir=C:/some/private/path`.

## Public-key links

Click **Generate public-key link** to display your link, then **Copy link** to copy it. The same X25519 key is reused on subsequent clicks and app launches, including when Tor is offline. The private key is stored in `contact-x25519.pk8` in the app data directory with owner-only access; it is never included in the link. Back up this file privately to keep your contact key. A damaged key produces an error rather than silently replacing your identity.

Link format: `onionp2p://contact/v1/x25519/<public-key>`, where the public key is the 32-byte little-endian X25519 public coordinate encoded as unpadded Base64url. This is our app's format, not a Briar link. It currently shares a public key only: it does not include an onion address, add a contact, or perform a messaging handshake. X25519 is not post-quantum cryptography.

## Bundled runtime

`src/main/resources/tor/windows-x64.zip` repackages the official Tor Expert Bundle 16.0 for Windows x86_64, including its `docs` license notices. Source:

https://dist.torproject.org/torbrowser/16.0/tor-expert-bundle-windows-x86_64-16.0.tar.gz

Original archive SHA-256 (verified against the publisher's HTTPS checksum list):

```text
2ee123e9167318a3742778ff704e75f01d4780f05a14522d451fe011011077c4
```

The archive is extracted on first launch to the versioned app data directory. Update the bundled runtime and versioned extraction directory together when updating Tor. This initial version supports direct Tor connections, without bridge configuration.

Build a Windows runtime image including Java with `./mvnw.cmd javafx:jlink`; its launcher is `target/app/bin/app.bat`. Development requires a JDK, but recipients of that image do not need Java or Tor installed separately.
