# Onion-P2P

Onion based Peer to Peer messenger prototype with a hello world onion server and authenticated contact pairing.

Windows x64 / Java 21+. Tor is bundled in the application resources; no Tor install or runtime download is needed.

Run `./mvnw.cmd javafx:run` (or run `HelloApplication` in your IDE). The window starts a local HTTP server and Tor in the background. Watch the console for:

```text
Onion address: http://<56-character-address>.onion/
```

Open that URL in Tor Browser to see `Hello world!`. Initial Tor connection and onion publication can take a few minutes. Keep the app open while others connect. Closing the window stops the server and its Tor process. No router port forwarding is needed.

The persistent identity and Tor state live under `%LOCALAPPDATA%/Onion-P2P` (home directory fallback). Keep the `service` directory private and back it up to retain the same address. Do not commit it. Only one app instance can use a data directory. For development, override it with the JVM option `-Donion.dataDir=C:/some/private/path`.

## Local account

On first launch, create a local username and password. Later launches ask for those credentials before starting Tor or opening the app. Usernames are 3–32 ASCII letters, numbers, dots, dashes, or underscores; passwords must be at least 12 characters.

The username and Argon2id password verifier (unique 16-byte salt, 19 MiB memory, 2 passes, 1 thread) live in the local `contacts.sqlite` database. The verifier can confirm a password but cannot recover it. The password is not currently used to encrypt the database, private keys, or messages; local encryption is still a separate planned feature. Forgetting the password currently means the account cannot be unlocked; there is no password-reset flow yet.

## Public-key links

Click **Generate public-key link** to display your link, then **Copy link** to copy it. The same X25519 key is reused on subsequent clicks and app launches, including when Tor is offline. The private key is stored in `contact-x25519.pk8` in the app data directory with owner-only access; it is never included in the link. Back up this file privately to keep your contact key. A damaged key produces an error rather than silently replacing your identity.

Link format: `onionp2p://contact/v2/<base64url-username>/<base64url-public-key>`. Both fields use unpadded Base64url; the key is a 32-byte little-endian X25519 public coordinate. The advertised username is a claimed display name, not proof of a real-world identity. The pairing handshake authenticates that the remote participant controls the private key matching the link and binds the advertised name into the handshake. Legacy v1 links (`onionp2p://contact/v1/x25519/<public-key>`) can still be imported and appear as `Unknown`. This is our app's format, not a Briar link. X25519 is not post-quantum cryptography.

Paste another user's link into **Import a contact's public-key link** and click **Import link**. The app displays and prints the derived contact onion address. Import also works before you generate your own link: it creates or loads your local key automatically. Wrong formats, noncanonical keys, unusable low-order keys, and your own link are rejected.

Both users must import each other's links and keep their apps running. Once Tor is ready, each app publishes its contact-specific rendezvous service and retries connecting every 10 seconds. Initial onion publication can take several minutes. Pending requests time out after 10 minutes; import the link again to retry. Pending imports are kept in memory, so re-import them if you restart before pairing finishes.

The status changes to **Acknowledged—contact saved locally** only after a fresh authenticated acknowledgment and a successful SQLite write. Confirmed contacts appear in **Saved contacts** and reload on restart. “Saved” means paired previously, not currently online. The existing hello-world website retains its separate address; rendezvous endpoints speak the pairing protocol, not HTTP.

## Basic messenger

Click **Open Messenger** to open a separate window with saved contacts, conversation history, a text composer, and an **Attach file** button. Messages and file attachments are sent to the contact-specific onion service over Tor and protected end to end with AES-256-GCM using keys derived from the pair's X25519 shared secret. The receiver stores a message or verified file before returning an authenticated receipt; retries with the same message ID do not create duplicate rows. Both apps must be running and the contact service available to send; offline message queuing is not implemented. Message text is limited to 4096 UTF-8 bytes and files to 1 GiB. A progress bar tracks outgoing file bytes. Received files are saved to `%LOCALAPPDATA%/Onion-P2P/downloads` (or your configured `onion.dataDir` plus `downloads`); duplicate names receive a numeric suffix. Each received attachment has an **Export…** link that opens a save dialog to copy that individual file wherever you choose.

Conversation history is stored locally in `contacts.sqlite` as plaintext, with the database file restricted to the current OS user. The account password does not encrypt this history yet. This experimental transport has not been independently audited and does not provide forward secrecy; treat it as a prototype, not as a production secure messenger.

## Acknowledgment and storage

Pairing v1 binds the ordered X25519 public keys and two fresh 32-byte nonces into HMAC-SHA256 proofs. HKDF-SHA256 derives independent authentication and acknowledgment keys under the `Onion-P2P/pairing/v1/` namespace. AES-256-GCM protects the client acknowledgment and the server's saved receipt. Every phase/direction uses a separate key derived from the fresh nonce pair and authenticates the transcript. Old acknowledgments cannot be replayed into a new session.

The receiver commits after validating the client acknowledgment, then sends its saved receipt. The initiating app commits only after validating that receipt. A dropped connection can temporarily leave only one side saved; retries are idempotent and finish the other side. This prototype protocol is not audited, is not post-quantum, and does not provide forward secrecy. Verify contact links through a trusted channel.

Acknowledged contacts are stored in `%LOCALAPPDATA%/Onion-P2P/contacts.sqlite` (or your configured `onion.dataDir`). Each row contains the public-key link, remote rendezvous address, advertised username, and first acknowledgment timestamp. The public-key link is unique, so repeat handshakes do not duplicate contacts. Private keys and handshake secrets are not written to this database. **The SQLite database is not encrypted at rest**; it has owner-only file access. Password-protected storage is a separate feature.

Our experimental rendezvous v1 calculation is X25519(local private key, remote public key), then HKDF-SHA256 with ASCII salt `Onion-P2P/rendezvous/v1` and info `endpoint owner's raw public key || peer's raw public key` (32 bytes each), yielding a 32-byte Ed25519 seed. The resulting Ed25519 public key is encoded using Tor's v3 onion address formula. Reversing the owner/peer order gives the other endpoint. Both participants can derive both endpoint keys, so these are rendezvous addresses, not independently authenticated identities. This is not Briar-compatible and is not an audited messaging protocol.

## Bundled runtime

The emoji picker bundles [Twemoji v16.0.1 artwork](https://github.com/jdecked/twemoji/tree/v16.0.1) by Twitter, Inc. and contributors, licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The artwork archive includes the license and attribution notice.

`src/main/resources/tor/windows-x64.zip` repackages the official Tor Expert Bundle 16.0 for Windows x86_64, including its `docs` license notices. Source:

https://dist.torproject.org/torbrowser/16.0/tor-expert-bundle-windows-x86_64-16.0.tar.gz

Original archive SHA-256 (verified against the publisher's HTTPS checksum list):

```text
2ee123e9167318a3742778ff704e75f01d4780f05a14522d451fe011011077c4
```

The archive is extracted on first launch to the versioned app data directory. Update the bundled runtime and versioned extraction directory together when updating Tor. This initial version supports direct Tor connections, without bridge configuration.

Build a Windows runtime image including Java with `./mvnw.cmd javafx:jlink`; its launcher is `target/app/bin/app.bat`. Development requires a JDK, but recipients of that image do not need Java or Tor installed separately.

Run local tests with `./mvnw.cmd test`. The opt-in live Tor test starts two independent Tor processes, exchanges acknowledgments, verifies both SQLite databases, and stops the test processes:

```powershell
.\mvnw.cmd '-Dtest=TorPairingIT' '-Donion.liveTest=true' test
```
