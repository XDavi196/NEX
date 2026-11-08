# NEX Forgejo in GitHub Codespaces

This is the temporary, no-VPS option for running NEX Forgejo on GitHub's Linux Codespaces environment.

## Start it

1. Open the [NEX repository](https://github.com/XDavi196/NEX).
2. Select **Code → Codespaces → Create codespace on main**.
3. Wait for the Codespace to finish creating its container.
4. In the **Ports** tab, open port `3000`. It is private at first. Keep it private while completing initial setup.
5. Complete Forgejo's initial setup and create the first administrator as `XDavi196`.
6. When the repository is ready to be public, change port 3000 visibility to **Public** in the Ports tab. Anyone with the forwarded URL can then open the repository and release pages.

The URL will look like:

`https://CODESPACE-NAME-3000.app.github.dev/XDavi196/NEX/releases`

GitHub generates the actual Codespaces hostname. It is not a permanent custom domain and may change if the Codespace is recreated.

## Import the NEX repository

In Forgejo, use **New Migration → Git** (or GitHub, if shown):

- Clone address: `https://github.com/XDavi196/NEX.git`
- Owner: `XDavi196`
- Repository: `NEX`
- Visibility: Public
- Enable mirror/pull mirror

Let the first migration finish so that branches and tags exist in Forgejo.

## Copy release notes and APK assets

Git mirroring does not create Forgejo release records or copy binary attachments. Use the included synchronizer after creating a Forgejo application token with repository write permission.

From the Codespaces terminal:

```bash
export FORGEJO_API_URL=http://localhost:3000/api/v1
export FORGEJO_OWNER=XDavi196
export FORGEJO_REPO=NEX
export FORGEJO_TOKEN='PASTE_YOUR_FORGEJO_TOKEN_HERE'
export SYNC_ASSET_RELEASES=8
python3 deploy/forgejo/sync-releases.py
```

Do not commit the token or paste it into an issue or chat. The command copies release notes and assets for the newest eight releases. Re-run it after new releases are published.

## Important limits

- This is a development/demo host, not an always-on production Forgejo server.
- GitHub Codespaces stops after inactivity (default 30 minutes) and has a maximum lifetime of 12 hours. When stopped, the Forgejo website is offline until the Codespace is restarted and its startup command runs again.
- Included personal GitHub Free usage is 120 core-hours and 15 GB storage per month. A 2-core Codespace uses two core-hours for each real hour it runs.
- The Codespace must not be deleted while you still need its Forgejo database and repositories. Back up data before deleting or recreating it.
- Public port forwarding allows anyone with the URL to access the web service. Keep it private until the admin account and repository settings are configured.

For a permanent address like `https://git.your-domain.tld/XDavi196/NEX/releases`, deploy the production bundle in this directory to an always-on server and point a domain you own to it.
