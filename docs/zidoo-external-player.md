# External Player: Zidoo via PlexToZidoo

This fork of the Silo **Android TV** app can play movies and episodes in the
Zidoo media player instead of Silo's built-in player. It uses
[PlexToZidoo](https://github.com/bowlingbeeg/PlexToZidoo) as the go-between,
the same way the ZidooPlexMod add-on for ZDMC does. The phone app is
unchanged.

Silo still tracks the playback:

- **Resume.** Playback starts at Silo's resume point; if the Zidoo player
  opens anywhere else, Silo moves it there, and no marker is skipped until it
  arrives. Silo saves your position
  every 10 seconds while the Zidoo player plays, and once more when it closes,
  so stopping or switching the box off resumes there next time. Past your
  server's watched threshold (90% unless changed), the title counts as
  watched, as it does in Silo's own player.
- **Skip intro and credits.** Silo reads the Zidoo player's position every
  second and jumps over the intro and credits using the server's markers. Each
  marker is skipped once per playback, so seeking back into one plays it.
- **Audio and subtitles.** Your audio choice carries over. A subtitle carries
  over when it is embedded in the file; anything else is left to the Zidoo
  player's own language settings.
- **Up Next.** When an episode plays to the end (into the credits, or its
  last half minute), Silo shows the next one, from this season or the next.
  With Settings → Playback → Auto-Play Next Episode on, it opens in the Zidoo
  player after a countdown, 10 seconds unless you change **Settings →
  External Player → Up Next Countdown** (right away, or 3 to 30 seconds).
  A ring beside the episode counts the seconds down, as in Silo's own player.
  **Play Now** starts it straight away and **Back** returns to the show. The Still Watching Prompt applies as in
  Silo's own player: after that many episodes in a row, Silo waits for Play
  Now.
- **The file as it is.** Silo asks the server to plan for the Zidoo player,
  not for Silo's own player, so the server sends the original file: DTS,
  TrueHD, Blu-ray `.m2ts`, VC-1 and Dolby Vision Profile 7 included. The
  Zidoo player then does what it always does, for example decoding DTS to PCM
  for a TV without DTS, as its own audio settings say.

Watch Party, Shuffle and casting from a phone keep using Silo's built-in
player. If a server administrator ends the session, Silo stops the Zidoo
player, as it leaves its own player.

Zidoo players report a lower screen density than other Android TV boxes,
which makes most apps look small. Silo lays itself out at the standard
Android TV size instead. To use the Zidoo's own size, turn off **Settings →
Interface Size → Fit Interface to TV**.

For Profile Selection ("Who's watching?"), playback in the Zidoo player
counts as using the profile, the way a phone's Cast session does: coming back
to Silo straight after a film does not ask, and a player left paused stops
counting. A fresh install asks every time until you accept Silo's offer to
remember the profile or change Profile Selection in Settings.

## Requirements

- A Zidoo player with firmware 6.4.42 or later (the new Zidoo player API).
- PlexToZidoo installed from its
  [releases page](https://github.com/bowlingbeeg/PlexToZidoo/releases).
- Zidoo *Quick settings → Playback → Play mode* set to **Single file**.

## Set up

1. In **Silo → Settings → External Player**, turn on **Play in Zidoo Player**,
   and choose whether to skip intros and credits automatically. Intro skipping
   starts on; credits skipping starts off, because on a film it jumps past any
   scene after the credits. **Up Next Countdown** sets how long Up Next waits
   before the next episode.
2. Play something.

That is all most setups need. The Zidoo player streams the original file from
your Silo server, as ZDMC streams from Plex, so PlexToZidoo needs no path
substitution rules.

### Optional: play from your network share

A PlexToZidoo path substitution makes the Zidoo player open the file over SMB
or NFS instead of streaming it from Silo. It is also the way to play a file
the server still converts (see Known limits). Rules
need a Silo account that can see file paths: an admin, or an account with the
Metadata curation permission.

In **PlexToZidoo → Settings → Substitutions**, add a rule that turns a path as
Silo reports it into a location the Zidoo box can open. Silo reports the path
inside its own container, so with Silo's standard Docker setup paths start with
`/mnt/media`. For example, *Part of the path to replace* `/mnt/media` and
*Replaced with* `smb://192.168.1.20/video`, where `video` is the NAS share that
Silo's media folder points to. Add the SMB username and password if the share
needs them. Silo's paths usually differ from Plex's, so rules made for Plex
won't match Silo's files, and those files keep streaming from Silo. To use both,
list them comma-separated in each field, or use *Add additional substitutions*.
The Plex settings in PlexToZidoo are not used.

### When it doesn't play

If the Zidoo player doesn't play the file, Silo says what it sent PlexToZidoo
(the file's path and the Silo server it streams from), what to check, and
offers a button that opens PlexToZidoo's settings. The Zidoo player saying it
cannot open `no-path-substitution-matched` means Silo had no stream to offer
and no rule matched. PlexToZidoo's *Debug data* setting shows what it plays
before playback starts.

If Silo cannot hand off at all (PlexToZidoo missing, or neither a stream nor a
file path for the title), it says why and offers **Play in Silo**.

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

The APK is a release build, the same kind as Silo's own sideload APK:
optimized and compiled ahead of time by Android, so it runs smoothly on a
Zidoo. (Builds before October 14, 2026 were debug builds, which run much
slower.) It carries the version of Silo's newest tester build, shown in
Settings as, for example, "Silo 1.0.0 (23)", and that build's versionCode,
which only goes up, so each APK installs over the last. As in Silo's own
release builds, Settings → Experimental → Watch Party starts off.

Every build is signed with the same debug key (`.github/zidoo-debug.keystore`),
so a new APK installs over the old one and keeps your sign-in. The build keeps
Silo's application ID, so the official Silo TV app has to be uninstalled once
before the first install. To build the same APK locally:

    SILO_RELEASE_KEYSTORE=$PWD/.github/zidoo-debug.keystore \
    SILO_RELEASE_KEYSTORE_PASSWORD=android SILO_RELEASE_KEY_ALIAS=androiddebugkey \
    ./gradlew :androidTvApp:assembleRelease -PsiloReleaseChannel=sideload \
      -PsiloVersionName=1.0.0 -PsiloBuildNumber=23 -PsiloVersionCode=110000023

using the version of Silo's newest `vX.Y.Z-beta.B` tag, with versionCode
`100000000 + (X*10000 + Y*100 + Z)*1000 + B`. A lower versionCode than the
installed app's won't install over it.

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
push. The clash is almost always in one of the five Silo files listed below,
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
| Stream relay for the Zidoo player | `android-shared/.../player/external/ZidooStreamRelay.kt` |
| Capabilities the server plans for | `android-shared/.../player/external/ZidooPlayerCapabilities.kt`, `androidTvApp/.../player/external/ZidooStartParams.kt` |
| Interface size on low-density boxes | `androidTvApp/.../player/external/TvInterfaceScale.kt` |
| Unit tests for all of the above | `android-shared/src/androidUnitTest/.../player/external/` |
| One hand-off, from server session to final save | `androidTvApp/.../player/external/ZidooHandoffCoordinator.kt` |
| Keeps Silo alive while the Zidoo player plays | `androidTvApp/.../player/external/ZidooHandoffService.kt` |
| Screen behind the Zidoo player | `androidTvApp/.../player/external/TvExternalPlayerScreen.kt` |
| Route switch, settings group, wiring | `androidTvApp/.../player/external/TvExternalPlayerRoute.kt`, `TvExternalPlayerSettingsGroup.kt`, `ExternalPlayerTvModule.kt`, `PlexToZidooAvailability.kt` |
| Build and release workflow, debug key | `.github/workflows/zidoo-tv-apk.yml`, `.github/zidoo-debug.keystore` |

Edits to Silo's own files, each marked `[zidoo-fork]` (`git grep "\[zidoo-fork\]"`):

| File | Change |
| --- | --- |
| `androidTvApp/.../tv/MainTvActivity.kt` | `attachBaseContext` applies `TvInterfaceScale.overrideConfiguration(...)` |
| `androidTvApp/src/androidMain/AndroidManifest.xml` | `<queries>` for PlexToZidoo; the foreground service |
| `androidTvApp/.../tv/di/AndroidTvModule.kt` | `includes(externalPlayerTvModule)`; `isCasting = ZidooHandoffCoordinator::isHandingOffNow` on the profile tracker, so Zidoo playback counts as using the profile |
| `androidTvApp/.../tv/ui/navigation/TvAppNavigation.kt` | `handOffToZidooIfEnabled(...)` in the `TvRoute.Player` destination, just before Silo's player is composed; its `onPlayNextEpisode` opens the next episode's `TvRoute.Player` in place of this one |
| `androidTvApp/.../tv/ui/screens/settings/TvSettingsScreen.kt` | `item { TvExternalPlayerSettingsGroup() }` |

## How it works

A hand-off starts a normal Silo playback session (asking for original
quality), because that session is how Silo records progress. The session is
planned for the Zidoo player: Silo's detected capabilities, widened to every
common audio codec with no channel limit, the Blu-ray and DVD codecs and
containers, and the server's `client_managed_dynamic_range_v1` claim (the
player presents HDR and Dolby Vision itself). Without that, the server would
plan for Silo's own player and the TV's audio input, and convert for example
a 5.1 DTS track for a TV that takes only stereo PCM.

Silo then opens PlexToZidoo in its ZDMC mode with the file's server path and a
stream address on the box itself, `http://127.0.0.1:<port>/<random>/video.mkv`
(with the file's own extension). Behind that address is a small relay in
Silo that forwards each request to the session's stream on your server,
signed with Silo's current access token, the way Silo's own player signs
its requests. The token never appears in the address, so PlexToZidoo's debug
screen and the Zidoo player's history don't hold it, and a token the app
renews during a long film is used from the next request. PlexToZidoo plays
the share location when one of its substitution rules matches the path, and
the relay's address otherwise. (On a box where the relay cannot start, Silo
gives the Zidoo player the server's stream address with `token=` instead, as
Plex links carry `X-Plex-Token`.)

Silo polls `http://127.0.0.1:9529/ZidooVideoPlay/getPlayStatus` once a
second, seeks with `ZidooVideoPlay/seekTo` to skip markers, reports progress
to the session, and stops the session and the relay when the Zidoo player
closes. While the episode plays, Silo looks up the next one the way its own
player does, so Up Next is ready when the Zidoo player closes; choosing it
opens a new hand-off. A foreground service keeps Silo's process alive in the meantime,
because Zidoo firmware only lets a couple of apps stay in the background.

## Known limits

- Up Next appears once the Zidoo player has closed, not over the credits
  while they play.
- The interface size is set when Silo opens. If the box changes its interface
  resolution while Silo is open, reopen Silo.
- A multi-part film (CD1/CD2 files) plays only the part you start in.
- Subtitles embedded in the file always work. Sidecar subtitle files next to
  the video reach the Zidoo player only when it plays from the share through a
  path substitution, not when it streams from Silo. Subtitles Silo downloaded
  on the server are not available to it.
- If Silo's process is killed during playback despite the foreground service,
  the resume point is the last one saved, at most about ten seconds earlier,
  and a film streaming from Silo stops, because the relay lives in Silo. A
  film playing from the share through a path substitution carries on.
- The server can still convert a file the Zidoo player could play: when
  Silo's own playback settings cap the bitrate, for a codec outside the list
  above (AV1 or VP9 on a box whose decoder Silo didn't detect), or when the
  server's administrator settings force it. There is then no original-file
  stream to send, so the file plays only through a PlexToZidoo path
  substitution, and Silo says so. The transcode the server starts is never
  used; its throttling pauses it after about five minutes of output, but it
  holds one of your server's transcode slots until playback ends.
- Streaming goes through Silo on the box, which copies the film's bytes from
  your server to the Zidoo player. A Zidoo handles a 4K remux's bitrate
  easily, but it is one more hop than playing from the share.
