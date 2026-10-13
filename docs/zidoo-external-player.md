# External Player: Zidoo via PlexToZidoo

This fork of the Silo **Android TV** app can play movies and episodes in the
Zidoo media player instead of Silo's built-in player. It uses
[PlexToZidoo](https://github.com/bowlingbeeg/PlexToZidoo) as the go-between,
the same way the ZidooPlexMod add-on for ZDMC does. The phone app is
unchanged.

Silo still tracks the playback:

- **Resume.** Playback starts at Silo's resume point. Silo saves your position
  every 10 seconds while the Zidoo player plays, and once more when it closes,
  so stopping or switching the box off resumes there next time. Past your
  server's watched threshold (90% unless changed), the title counts as
  watched, as it does in Silo's own player.
- **Skip intro and credits.** Silo reads the Zidoo player's position every
  second and jumps over the intro and credits using the server's markers. Each
  marker is skipped once per playback, so seeking back into one plays it.
- **Audio and subtitles.** Your audio choice carries over. A subtitle carries
  over when it is embedded in the file; sidecar subtitle files are left to the
  Zidoo player's own language settings.

Watch Party, Shuffle and casting from a phone keep using Silo's built-in
player. If a server administrator ends the session, Silo stops the Zidoo
player, as it leaves its own player.

For Profile Selection ("Who's watching?"), playback in the Zidoo player
counts as using the profile, the way a phone's Cast session does: coming back
to Silo straight after a film does not ask, and a player left paused stops
counting. A fresh install asks every time until you accept Silo's offer to
remember the profile or change Profile Selection in Settings.

## Requirements

- A Zidoo player with firmware 6.4.42 or later (the new Zidoo player API).
- PlexToZidoo installed from its
  [releases page](https://github.com/bowlingbeeg/PlexToZidoo/releases).
- The Zidoo player can open your media over SMB or NFS.
- Zidoo *Quick settings → Playback → Play mode* set to **Single file**.
- A Silo account that can see file paths: an admin, or an account with the
  Metadata curation permission.

## Set up

1. In **PlexToZidoo → Settings**, fill in path substitution so a path as Silo
   reports it becomes a location the Zidoo box can open. For example, *Part of
   the path to replace* `/media` and *Replace with* `smb://192.168.1.20/media`,
   plus the SMB username and password if your share needs them. Several paths
   can be listed, comma-separated, and there are slots for more shares. The
   Plex settings in PlexToZidoo are not used.
2. In **Silo → Settings → External Player**, turn on **Play in Zidoo Player**,
   and choose whether to skip intros and credits automatically. Intro skipping
   starts on; credits skipping starts off, because on a film it jumps past any
   scene after the credits.
3. Play something. If the Zidoo player reports it cannot open
   `no-path-substitution-matched`, no substitution rule matched the file's
   path: turn on PlexToZidoo's debug screen and compare *Video Path* with your
   rules.

If Silo cannot hand off (no file path, PlexToZidoo missing, or the Zidoo player
never starts), it says why and offers **Play in Silo**.

## Getting the APK, and staying up to date

The **Zidoo TV APK** workflow (`.github/workflows/zidoo-tv-apk.yml`) builds the
app on GitHub and publishes it as a release. The newest build is always at:

    https://github.com/<you>/silo-android/releases/latest/download/silo-tv-zidoo.apk

Type that address into the *Downloader* app on the Zidoo to install or update.

One-time setup on your fork:

1. Keep the fork's changes on its **main** branch. GitHub only runs scheduled
   workflows from the default branch.
2. Open the **Actions** tab and enable workflows.
3. Disable the upstream workflows you don't need in a fork: **Android
   Builds** and **Discord Commits** (open each workflow → "…" → Disable).
   Leave **Zidoo TV APK** on.

How updates arrive:

- Every Monday the workflow fetches the newest Silo code, merges it into a
  temporary copy of your branch, runs the External Player tests, builds, and
  publishes a release. A week with no new commits builds nothing.
- Run it any time from **Actions → Zidoo TV APK → Run workflow**.
- The workflow never changes your branch. To bring the branch itself up to
  date, use **Sync fork** on the fork's main page; that also triggers a build.
- GitHub pauses scheduled workflows in repositories with no activity for 60
  days. Re-enable it from the Actions tab if that happens.

Every build is signed with the same debug key (`.github/zidoo-debug.keystore`),
so a new APK installs over the old one and keeps your sign-in. The build keeps
Silo's application ID, so the official Silo TV app has to be uninstalled once
before the first install. Locally, `./gradlew :androidTvApp:assembleDebug`
builds the same app; copy `.github/zidoo-debug.keystore` to
`~/.android/debug.keystore` first if it should install over the workflow's
builds.

## When an update clashes

If upstream Silo changes lines the fork also changed, the run fails and lists
the files in its summary (and opens an issue if the fork has issues turned on).
To fix it, on a computer with git:

    git clone https://github.com/<you>/silo-android && cd silo-android
    git remote add upstream https://github.com/Silo-Server/silo-android.git
    git fetch upstream && git merge upstream/main
    # resolve the listed files, keeping both upstream's change and the [zidoo-fork] lines
    git commit && git push

Or ask Claude with the fork's name: it can do the merge, run the tests and
push. The clash is almost always in one of the four Silo files listed below,
each change marked with `[zidoo-fork]`.

## What the fork changes

New files (no clash possible):

| Part | File |
| --- | --- |
| PlexToZidoo launch URI (`PlexToZidoo-*` parameters) | `android-shared/.../player/external/PlexToZidooLaunch.kt` |
| Zidoo player HTTP API on `127.0.0.1:9529` | `android-shared/.../player/external/ZidooPlayerApi.kt` |
| Polling, skips, end-of-playback detection | `android-shared/.../player/external/ZidooPlaybackMonitor.kt`, `ZidooSkipPlanner.kt` |
| Audio and subtitle index translation | `android-shared/.../player/external/ZidooTrackMapping.kt` |
| Settings store | `android-shared/.../player/external/ExternalPlayerSettings.kt` |
| Unit tests for all of the above | `android-shared/src/androidUnitTest/.../player/external/` |
| One hand-off, from server session to final save | `androidTvApp/.../player/external/ZidooHandoffCoordinator.kt` |
| Keeps Silo alive while the Zidoo player plays | `androidTvApp/.../player/external/ZidooHandoffService.kt` |
| Screen behind the Zidoo player | `androidTvApp/.../player/external/TvExternalPlayerScreen.kt` |
| Route switch, settings group, wiring | `androidTvApp/.../player/external/TvExternalPlayerRoute.kt`, `TvExternalPlayerSettingsGroup.kt`, `ExternalPlayerTvModule.kt`, `PlexToZidooAvailability.kt` |
| Build and release workflow, debug key | `.github/workflows/zidoo-tv-apk.yml`, `.github/zidoo-debug.keystore` |

Edits to Silo's own files, each marked `[zidoo-fork]` (`git grep "\[zidoo-fork\]"`):

| File | Change |
| --- | --- |
| `androidTvApp/src/androidMain/AndroidManifest.xml` | `<queries>` for PlexToZidoo; the foreground service |
| `androidTvApp/.../tv/di/AndroidTvModule.kt` | `includes(externalPlayerTvModule)`; `isCasting = ZidooHandoffCoordinator::isHandingOffNow` on the profile tracker, so Zidoo playback counts as using the profile |
| `androidTvApp/.../tv/ui/navigation/TvAppNavigation.kt` | `handOffToZidooIfEnabled(...)` in the `TvRoute.Player` destination, just before Silo's player is composed |
| `androidTvApp/.../tv/ui/screens/settings/TvSettingsScreen.kt` | `item { TvExternalPlayerSettingsGroup() }` |

## How it works

A hand-off starts a normal Silo playback session (asking for original
quality), because that session is how Silo records progress. Silo opens
PlexToZidoo with the file's server path; PlexToZidoo applies its substitution
rules and starts the Zidoo player. The session's own stream is never fetched.
Silo then polls `http://127.0.0.1:9529/ZidooVideoPlay/getPlayStatus` once a
second, seeks with `ZidooVideoPlay/seekTo` to skip markers, reports progress
to the session, and stops the session when the Zidoo player closes. A
foreground service keeps Silo's process alive in the meantime, because Zidoo
firmware only lets a couple of apps stay in the background.

## Known limits

- The next episode does not start automatically when one finishes.
- A multi-part film (CD1/CD2 files) plays only the part you start in.
- Subtitles Silo downloaded on the server are not available to the Zidoo
  player; embedded and sidecar files are.
- If Silo's process is killed during playback despite the foreground service,
  the resume point is the last one saved, at most about ten seconds earlier.
- The server plans the session for what Silo detects this box can decode. If
  it decides the file needs a transcode or remux (for example lossless audio
  with no AV receiver detected), it starts one that the Zidoo player never
  uses. The server's throttling pauses it after about five minutes of output,
  but it holds one of your server's transcode slots until playback ends. With
  a receiver connected, or for files the box decodes directly, nothing extra
  runs.
