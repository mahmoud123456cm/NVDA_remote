# Signing the app

The signing key is no longer in the repository. The build reads it from four values:

| Value | GitHub secret / environment variable | Local `~/.gradle/gradle.properties` |
|---|---|---|
| keystore file (base64 text in CI) | `KEYSTORE_BASE64` (CI) / `KEYSTORE_PATH` | `keystorePath` |
| keystore password | `KEYSTORE_PASSWORD` | `keystorePassword` |
| key alias | `KEY_ALIAS` | `keyAlias` |
| key password | `KEY_PASSWORD` | `keyPassword` |

On GitHub: repository, Settings, Secrets and variables, Actions, New repository secret. Add the four secrets.
The build fails in CI if they are missing, so a wrongly signed APK is never published.

## Which key to use

**A) Keep the key you have now (installed copies keep updating).** The old key is `app/nvdapocket.jks`
(alias `nvdapocket`, both passwords `nvdapocket`). Get its base64 text with
`base64 -w0 nvdapocket.jks`, and use it as `KEYSTORE_BASE64`.
But that key and password were public in the repository history, so anyone who can read the history can sign an
APK that installs over yours. Fine for a private project, not for a public one.

**B) Make a new private key (recommended if the repository is public).**

    keytool -genkeypair -v -keystore release.jks -alias pocketlink -keyalg RSA -keysize 4096 -validity 36500
    base64 -w0 release.jks

Use that text as `KEYSTORE_BASE64`, and the password and alias you chose for the other three secrets.
Phones with the old build must uninstall it once before installing the new one, because Android refuses updates
signed with a different key. Back up `release.jks` somewhere safe: if you lose it you can never update installed copies again.

## Removing the old key from the Git history

Deleting the file only hides it from the newest version. If the repository is public and you chose B, also rewrite
the history (for example `git filter-repo --path app/nvdapocket.jks --invert-paths`, then force-push) or simply
make the repository private.

## Building on your own computer

Put the four `gradle.properties` values in `~/.gradle/gradle.properties` (never in the project). Without them,
`gradle assembleRelease` signs with the Android debug key, which is fine for testing only.

## Changing the key from the Windows command prompt (cmd)

Needs a JDK (`keytool`). If `keytool` is not found, use the full path, for example `"%JAVA_HOME%\bin\keytool"`.

    keytool -genkeypair -v -keystore release.jks -alias pocketlink -keyalg RSA -keysize 4096 -validity 36500
    powershell -NoProfile -Command "[Convert]::ToBase64String([IO.File]::ReadAllBytes('release.jks'))" | clip

The second command copies the key as text to the clipboard: paste it into the `KEYSTORE_BASE64` secret on GitHub, and put the
password and alias you typed into `KEYSTORE_PASSWORD`, `KEY_PASSWORD` and `KEY_ALIAS`.

With the GitHub CLI (`gh`) you can set the secrets from cmd instead:

    powershell -NoProfile -Command "[Convert]::ToBase64String([IO.File]::ReadAllBytes('release.jks'))" > release.b64
    gh secret set KEYSTORE_BASE64 < release.b64
    gh secret set KEYSTORE_PASSWORD --body "your password"
    gh secret set KEY_PASSWORD --body "your password"
    gh secret set KEY_ALIAS --body "pocketlink"
    del release.b64

Keep `release.jks` somewhere safe and never commit it. After a key change, phones must uninstall the old build once.
