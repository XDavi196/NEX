# NEX Forgejo — deployment

This directory deploys a self-hosted Forgejo instance for the NEX project.

It is designed to provide a repository/release URL in this format:

`https://git.example.com/XDavi196/NEX/releases`

Replace `git.example.com` with a real domain you own. The example is a placeholder, not an address that has already been registered.

## What this deployment contains

- Forgejo **17.0.1**, pinned to the current stable container release.
- SQLite database and Git data stored in a persistent Docker volume.
- Caddy reverse proxy with automatic HTTPS certificates.
- SSH Git access on port 2222 by default.
- A release synchronizer that copies GitHub release notes and recent binary assets into Forgejo.
- Forgejo Actions disabled in this small deployment; this does not use GitHub Actions to build NEX.

The release synchronizer is separate from Git mirroring: Git mirrors transfer commits, branches and tags, while Forgejo release records and uploaded binaries are separate objects.

## Requirements

- A Linux VPS or always-on server with Docker Engine and the Docker Compose plugin.
- A public IPv4 address (and optionally IPv6).
- A domain/subdomain you control.
- Inbound TCP ports 80, 443 and 2222 available.
- Enough storage for Git history, database backups and APK/AAB release assets.

A phone or laptop that sleeps is not a suitable public Forgejo host.

## 1. Configure DNS and domain

Create a DNS record:

- Type: `A`
- Name: `git`
- Value: the public IPv4 address of your server

Add an `AAAA` record only if the server has working public IPv6.

Copy the environment template and edit it:

```sh
cp .env.example .env
nano .env
```

Set `FORGEJO_DOMAIN` to your real hostname, for example `git.your-domain.tld`, and set `ACME_EMAIL` to an email address you control. Do not use the literal example values.

## 2. Start Forgejo

Start only Forgejo and Caddy for the initial setup:

```sh
docker compose up -d forgejo caddy
docker compose logs -f forgejo caddy
```

Open `https://YOUR_DOMAIN` and complete the first administrator account setup. Use the account name `XDavi196` if you want the final repository path to match the GitHub owner. Keep registration disabled after creating the first administrator.

In **Settings → Applications**, create a token with repository read/write permission. Put it in `FORGEJO_TOKEN` inside the server's private `.env` file. Never put a token in GitHub, source code, screenshots or a committed file.

## 3. Import NEX as a pull mirror

Sign in as `XDavi196` and select **New Migration → GitHub**.

Use these values:

- Clone address: `https://github.com/XDavi196/NEX.git`
- Owner: `XDavi196`
- Repository name: `NEX`
- Visibility: public
- Enable **This repository will be a mirror**

Complete the migration. Forgejo will periodically pull commits, branches and tags from GitHub. Use **Synchronize Now** in repository settings to request an immediate sync.

Do not create an empty `NEX` repository first: pull migration must create the repository as a mirror.

## 4. Synchronize Releases and APK files

Start the companion release synchronizer after the repository mirror and token are ready:

```sh
docker compose up -d release-sync
docker compose logs -f release-sync
```

It reads public GitHub releases from `XDavi196/NEX`, creates matching Forgejo release entries, and uploads assets for the newest `SYNC_ASSET_RELEASES` releases. Older release notes are retained without downloading every old APK, limiting storage use. Increase `SYNC_ASSET_RELEASES` in `.env` to copy more binaries.

The matching Git tag must exist in Forgejo before a release can be created. If the mirror has not fetched a tag yet, the synchronizer reports the issue and retries on the next interval.

## 5. Expected URLs

With owner `XDavi196`, repository `NEX` and your actual hostname:

- Repository: `https://YOUR_DOMAIN/XDavi196/NEX`
- Releases: `https://YOUR_DOMAIN/XDavi196/NEX/releases`
- Latest release: `https://YOUR_DOMAIN/XDavi196/NEX/releases/latest`
- Git clone (HTTPS): `https://YOUR_DOMAIN/XDavi196/NEX.git`
- Git clone (SSH): `ssh://git@YOUR_DOMAIN:2222/XDavi196/NEX.git`

Forgejo's repository URL has both an owner and a repository segment, like Eden's `/eden-emu/eden/releases`. Therefore the full NEX path includes `/XDavi196/NEX/releases`; `/NEX/releases` by itself omits the owner segment.

## Backups and maintenance

Back up the `forgejo-data` Docker volume regularly and keep an off-server copy. Back up before every Forgejo upgrade. Pin a tested Forgejo image version instead of blindly using `latest`; update the tag after reading the release notes.

Useful commands:

```sh
docker compose ps
docker compose logs -f forgejo
docker compose logs -f release-sync
docker compose pull
docker compose up -d
```

## Important limitation

The GitHub repository can store this deployment configuration, but it cannot create DNS records, provide a public server, or turn on a self-hosted Forgejo instance by itself. A domain and server must be supplied/configured by the owner before the NEX Forgejo URL becomes publicly accessible.
