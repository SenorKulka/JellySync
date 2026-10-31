# Jellysync

**Jellyfin for Android TV, plus SyncPlay.** Watch together with Jellyfin Web, iOS and Android clients from your TV:
create, join and leave SyncPlay groups, with shared play, pause, seek and queue.

Jellysync is an unofficial, personal fork of [Jellyfin for Android TV](https://github.com/jellyfin/jellyfin-androidtv).
It is not made, reviewed or supported by the Jellyfin team. Please don't report Jellysync problems to them; open an
issue here instead.

## Credits

- **The [Jellyfin](https://jellyfin.org) team and contributors**, who build the server, the SyncPlay protocol and
  the Android TV app this fork is built on. Everything below the line is their work and their README.
- **[Tomer Horowitz](https://github.com/tomerh2001)**, who wrote SyncPlay for the TV app in
  [jellyfin/jellyfin-androidtv#5810](https://github.com/jellyfin/jellyfin-androidtv/pull/5810)
  (branch `feature/syncplay` in [his fork](https://github.com/tomerh2001/jellyfin-androidtv)). The PR was written with
  AI assistance and was declined under Jellyfin's [LLM policy](https://jellyfin.org/docs/general/contributing/llm-policies/),
  which is why it lives here rather than upstream. Jellysync's own fixes on top are AI-assisted too.

## What is in this fork

| Branch | Contents |
|---|---|
| `master` | Untouched mirror of upstream `master`. |
| `jellysync` (default) | Upstream `master`, merged with Tomer's SyncPlay branch, plus Jellysync fixes. |

Fixes on top of the SyncPlay branch, from an independent code review before first use:

- The SyncPlay socket builds its handshake from one snapshot of the server URL, token and device, so a server or
  account switch during a reconnect cannot pair one server's address with another server's token.
- Leaving or signing out while a group is still being created or joined no longer sends a stale Create/Join request.

## Install

Jellysync builds as its own app (`org.jellyfin.androidtv.jellysync`, shown as **Jellysync**) that installs **next to**
the official app, so your normal Jellyfin app is never touched. It is optimized like an upstream release build and
signed with your own key.

1. Create a signing key once, and keep a backup of it: every update must be signed with the same key.

   ```sh
   keytool -genkeypair -keystore ~/.android/jellysync-release.jks -storetype PKCS12 -alias jellysync \
     -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=JellySync"
   ```

2. Tell Gradle about it in `~/.gradle/gradle.properties` (outside the repo):

   ```properties
   jellysync.keystore.file=/path/to/jellysync-release.jks
   jellysync.keystore.password=...
   jellysync.key.alias=jellysync
   jellysync.key.password=...
   ```

3. Build and install (JDK 21, Android SDK platform `android-37.0`, build-tools `36.0.0`):

   ```sh
   TV_HOST=<tv ip> jellysync/build.sh   # runs the tests, builds, checks the signature, installs over ADB
   ```

   Bump `jellysync/VERSION` before installing an update: Android only accepts a higher version.

Then open Jellysync, sign in, and choose **SyncPlay** in the home toolbar.

The logo is Jellyfin's mark with a "Jellysync" wordmark made from Jellyfin's own lettering; see
[`branding/`](branding/README.md) for how it is generated and its CC BY-SA 4.0 notice.

## Keeping up with upstream

We try to keep this current by merging upstream regularly:

```sh
git fetch upstream && git checkout master && git merge --ff-only upstream/master && git push
git checkout jellysync && git merge master   # resolve, bump jellysync/VERSION, then: jellysync/build.sh
```

### The weak point

SyncPlay hooks into the player and the playback code, and upstream is in the middle of rewriting exactly that
([#1057](https://github.com/jellyfin/jellyfin-androidtv/issues/1057)). For now upstream merges in cleanly, but a large
playback change upstream will eventually break the merge. At that point the SyncPlay code has to be reworked on top of
the new player, or, if upstream ships its own SyncPlay as part of that rewrite, this fork has served its purpose and
should be retired in favour of the official app. Expect lag after big upstream releases.

## Known issues

- Tested against a Jellyfin 12.3 server. SyncPlay has broken between server versions before.
- Group messages are handled in order, and fetching item details for a queue blocks the commands behind it: with a
  long shared playlist or a slow server, play/pause/seek from others can arrive late.
- With the experimental "new playback" setting on, resuming an ordinary (non-SyncPlay) video can start from the
  beginning. It is off by default.
- Subtitle files at absolute URLs provided by the server are fetched as given, even from another host or over plain
  HTTP. No token is attached to them.

---

<h1 align="center">Jellyfin for Android TV</h1>
<h3 align="center">Part of the <a href="https://jellyfin.org">Jellyfin Project</a></h3>

---

<p align="center">
<img alt="Logo banner" src="https://raw.githubusercontent.com/jellyfin/jellyfin-ux/master/branding/SVG/banner-logo-solid.svg?sanitize=true"/>
<br/><br/>
<a href="https://github.com/jellyfin/jellyfin-androidtv">
<img alt="GPL 2.0 License" src="https://img.shields.io/github/license/jellyfin/jellyfin-androidtv.svg"/>
</a>
<a href="https://github.com/jellyfin/jellyfin-androidtv/releases">
<img alt="Current Release" src="https://img.shields.io/github/release/jellyfin/jellyfin-androidtv.svg"/>
</a>
<a href="https://translate.jellyfin.org/projects/jellyfin-android/jellyfin-androidtv/">
<img alt="Translation Status" src="https://translate.jellyfin.org/widgets/jellyfin-android/-/jellyfin-androidtv/svg-badge.svg"/>
</a>
<br/>
<a href="https://opencollective.com/jellyfin">
<img alt="Donate" src="https://img.shields.io/opencollective/all/jellyfin.svg?label=backers"/>
</a>
<a href="https://features.jellyfin.org">
<img alt="Feature Requests" src="https://img.shields.io/badge/fider-vote%20on%20features-success.svg"/>
</a>
<a href="https://matrix.to/#/+jellyfin:matrix.org">
<img alt="Chat on Matrix" src="https://img.shields.io/matrix/jellyfin:matrix.org.svg?logo=matrix"/>
</a>
<br/>
<a href="https://play.google.com/store/apps/details?id=org.jellyfin.androidtv">
<img width="153" alt="Jellyfin on Google Play" src="https://jellyfin.org/images/store-icons/google-play.png"/>
</a>
<a href="https://www.amazon.com/gp/aw/d/B07TX7Z725">
<img width="153" alt="Jellyfin on Amazon Appstore" src="https://jellyfin.org/images/store-icons/amazon.png"/>
</a>
<a href="https://f-droid.org/en/packages/org.jellyfin.androidtv/">
<img width="153" alt="Jellyfin on F-Droid" src="https://jellyfin.org/images/store-icons/fdroid.png"/>
</a>
<br/>
<a href="https://repo.jellyfin.org/releases/client/androidtv/">Download archive</a>
</p>

Jellyfin for Android TV is a Jellyfin client for Android TV, Nvidia Shield, and Amazon Fire TV devices. We welcome all contributions and pull
requests! If you have a larger feature in mind please open an issue so we can discuss the implementation before you start. 

## Building

The app uses Gradle and requires the Android SDK. We recommend using Android Studio, which includes all required dependencies, for
development and building. For manual building without Android Studio make sure a compatible JDK and Android SDK are installed and in your
PATH, then use the Gradle wrapper (`./gradlew`) to build the project with the `assembleDebug` Gradle task to generate an apk file:

```shell
./gradlew assembleDebug
```

The task will create an APK file in the `/app/build/outputs/apk/debug` directory. This APK file uses a different app-id from our stable
builds and can be manually installed to your device.

## Branching

The `master` branch is the primary development branch and the target for all pull requests. It is **unstable** and may contain breaking
changes or unresolved bugs. For production deployments and forks, always use the latest `release-x.y.z` branch. Do not base production work
or long-lived forks on `master`.

Release branches are created at the start of a beta cycle and are kept up to date with each published release. Maintainers will cherry-pick
selected changes into release branches as needed for backports. These branches are reused for subsequent patch releases.

## Translating

Translations can be improved very easily from our [Weblate](https://translate.jellyfin.org/projects/jellyfin-android/jellyfin-androidtv)
instance. Look through the following graphic to see if your native language could use some work! We cannot accept changes to translation
files via pull requests.

<p align="center">
<a href="https://translate.jellyfin.org/engage/jellyfin-android/">
<img alt="Detailed Translation Status" src="https://translate.jellyfin.org/widgets/jellyfin-android/-/jellyfin-androidtv/multi-auto.svg"/>
</a>
</p>
