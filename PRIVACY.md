# NEX — Privacy Policy

**Last updated: 5 November 2026**

This Privacy Policy explains how the independent NEX Android emulator project ("NEX", "we", or "the app") handles information when you use the app. NEX is a pre-release project and its features may change. This policy describes the current design and the data handling expected for the features being developed.

## 1. Who operates NEX

NEX is an independent community software project. It is not operated by Nintendo, Nextendo Network, or the Eden project. NEX currently does not operate a cloud account service or game-matching server of its own.

When you use Nextendo Network features, Nextendo processes information under its own privacy policy and terms. Review those documents before linking your account.

## 2. Information handled by NEX

Depending on the features you choose to use, NEX may handle the following information:

**Emulator data stored on your device**
- Emulator settings and preferences.
- Game files, firmware, keys, save data, screenshots, and shader/cache files that you choose to add or generate.
- Local diagnostic and crash logs created to help troubleshoot problems.

These files are used by the emulator on your device. NEX does not provide games, firmware, or console keys.

**Nextendo account information**
When you choose to sign in and authorize NEX, the app is designed to receive and use:
- OAuth access and refresh tokens;
- your Nextendo player ID (PID), username/display name, and Friend Code;
- profile and friend information returned by Nextendo;
- presence information, such as online status and the game you are playing, when you enable or use those features;
- a separate game credential used for supported online game authentication.

NEX uses OAuth Authorization Code with PKCE through your system browser. NEX does not ask for or receive your Nextendo account password.

**Technical information**
The app may process your Android version, device characteristics, NEX version, error details, and emulator logs locally to operate the app and diagnose crashes. If you voluntarily share a log or bug report, the information in that submission will be visible to the recipient and may include device or game details. Review files before sharing them.

## 3. How information is used

Information is handled only as needed to:
- run the emulator and save your local settings and progress;
- authenticate your Nextendo account after you authorize NEX;
- display your account identity, friends, and presence;
- request online game authentication and connect to supported third-party game services;
- diagnose crashes, troubleshoot errors, and improve reliability;
- protect the app and its integrations against misuse.

## 4. Where information is sent

When you use optional Nextendo features, NEX communicates directly with Nextendo's services over HTTPS. This may transmit the relevant OAuth token, player identity, friend/presence information, and game-authentication data required by the requested feature.

Game servers and other players may receive or see information required for online play, such as your in-game identity, online status, or game activity, depending on the game and service.

NEX does not sell your personal information. The current project does not operate its own advertising network or analytics backend. Third-party services that you choose to use are governed by their own privacy policies.

If you post a report or comment on GitHub, GitHub will process that information under GitHub's privacy policy, and public repository posts may be visible to anyone.

## 5. Storage and security

Emulator files and settings are stored locally on your device in the app's storage locations. The NEX Android integration is designed to store Nextendo session credentials encrypted using Android Keystore-backed encryption. Authentication tokens should never be included in public logs, screenshots, issues, or messages.

No method of storage or transmission is completely secure. Keep your device protected, install updates from sources you trust, and do not share your session files or credentials.

## 6. Retention and deletion

Local emulator data remains on your device until you delete it, clear the app's storage, uninstall the app, or remove the relevant files. Signing out should clear the locally stored Nextendo session where that feature is available; it does not delete your Nextendo account or information already held by Nextendo.

Nextendo controls retention and deletion of information held on its servers. To delete or manage server-side account data, use Nextendo's account tools and privacy channels.

If you voluntarily submit a diagnostic report or GitHub issue, the recipient may retain it according to the policies of that service. Avoid including personal data that is not needed to diagnose the issue.

## 7. Your choices and privacy rights

You can choose not to link a Nextendo account and can avoid optional online features. You can sign out or revoke NEX's authorization through your Nextendo account settings when available. You can also delete local app data through Android settings, understanding that this may remove saves, settings, and other local files.

Depending on your location, applicable privacy laws may give you rights to request access to, correction of, deletion of, or information about the processing of your personal data, and to withdraw consent where processing is based on consent. Requests concerning data stored by Nextendo must be directed to Nextendo.

## 8. Children's privacy

NEX is not designed to bypass age requirements or parental controls imposed by law or by third-party services. If you are not legally permitted to use a linked service on your own, do not create or link an account without the required parent or guardian involvement.

## 9. International processing

Nextendo and GitHub may process information in countries other than the country where you live. Their own privacy policies describe their processing and any applicable international transfers.

## 10. Changes to this Policy

This Policy may be updated as NEX's features or data practices change. The "Last updated" date at the top indicates the latest revision. Material changes should be reflected here before the changed feature is released.

## 11. Contact

For questions about this policy or NEX's data handling, contact the project through its public GitHub repository:

https://github.com/XDavi196/NEX

Do not include passwords, authentication tokens, private keys, or unnecessary sensitive personal information in public issues.
