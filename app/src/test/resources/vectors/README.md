Synthetic AES Crypt files for unit tests (random data, not from any backup), made by a Python
implementation of the AES Crypt spec using the `cryptography` package. Passphrase: `pässwörd 🔑`.

- `pN.bin`: plaintext of N bytes; `v2_N.aes` / `v3_N.aes`: it encrypted as AES Crypt v2 / v3.
  `v3_17.aes` uses the HMAC variant that also covers the version byte.
- `dlist.aes`: a tiny dlist zip (manifest with a BOM, a Folder, a multi-block file and an empty file).
- `dblock_v2.aes` / `dblock_v3.aes`: a dblock-like zip (40 unique blocks named by their base64url
  SHA-256, stored and deflated, one entry written with zip64 headers), encrypted as v2 / v3.
