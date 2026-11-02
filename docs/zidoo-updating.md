# Updating the Zidoo fork from Silo

This fork is Silo's Android TV app plus External Player support (playing in
the Zidoo player through PlexToZidoo). This page explains how to bring in new
Silo releases without losing that support, and what to ask Claude when you
want help. The feature itself is described in
[zidoo-external-player.md](zidoo-external-player.md).

**Most updates need nothing from you.** On the 1st of every month the
**Zidoo TV APK** workflow merges the newest Silo code, runs the External Player
tests, builds the APK and publishes it at the same Downloader address:

    https://github.com/Basche1490/silo-android/releases/latest/download/silo-tv-zidoo.apk

You only need to step in when a run fails.

## Rules that keep Zidoo support

- Work only in **Basche1490/silo-android**, on its **main** branch. Never push
  to Silo-Server/silo-android and never open a pull request there.
- **Never press "Discard commits"** when GitHub's **Sync fork** offers it. It
  replaces your branch with Silo's and deletes every Zidoo change.
- Keep every line marked `[zidoo-fork]` and every file listed under
  [What the fork adds](#what-the-fork-adds). When Silo changes the same lines,
  keep Silo's change *and* the `[zidoo-fork]` lines.
- Leave `.github/zidoo-debug.keystore` as it is. A different key makes the new
  APK refuse to install over the old one.
- Keep the application ID `org.siloserver.silo` and never ship a lower
  versionCode than the installed app. The workflow checks both.

## Getting updates

| You want to | Do this |
| --- | --- |
| Wait for the monthly build | Nothing. Install from the Downloader address when you like. |
| Build with the newest Silo code now | **Actions → Zidoo TV APK → Run workflow**. Takes about 15 minutes. |
| Bring the fork's own code up to date | **Sync fork → Update branch** on the fork's main page. This also starts a build. |
| Delete old releases | On the fork's **Releases** page, open an old release and press **Delete**. Never delete the one marked **Latest**: the Downloader address serves it. |

The workflow merges Silo into a temporary copy, so the APK has the newest
Silo code but the fork's branch only changes when you sync it or someone
pushes. The further the branch falls behind, the bigger the merge when it
finally happens, so bring the branch up to date every month or so: press
**Sync fork**, or ask Claude with the prompt under
[Option A](#option-a-ask-claude-to-do-it-recommended).

A clean build does not prove the Zidoo behaves as before. Silo sometimes
changes how playback works without breaking the build; this happened when
Silo retired Original quality. After each update, run the
[checks on the Zidoo](#after-an-update-check-on-the-zidoo).

## When a run fails

Open the failed run (**Actions → Zidoo TV APK**) and see which step failed:

| Failed step | What it means | What to do |
| --- | --- | --- |
| **Report a clash with upstream Silo** | Silo changed lines the fork also changed. The run summary lists the files, and an issue opens if the fork has issues turned on. | Resolve the clash (below). |
| **Run the External Player unit tests** or **Build the TV APK** | The merge worked, but Silo changed code the fork uses, for example renamed a function. | Adapt the fork code. Ask Claude (option A). |
| **Pick the version to stamp** | Silo changed how it tags releases. | Ask Claude to update that step. |
| **Check the APK is signed…** or **Check the APK's version…** | The APK would not install over the current one, so it was not released. | Ask Claude. Do not change the key. |

A failed run publishes nothing, so the APK on the Downloader address stays the
last good one.

**No run started at all.** If a push, **Sync fork** or the 1st of the month
passes without a new **Zidoo TV APK** run, GitHub Actions is probably off for
the fork. Open **Settings → Actions → General**, choose **Allow all actions and
reusable workflows** and save, then **Actions → Zidoo TV APK → Run workflow**.
Turning Actions on does not build earlier pushes by itself. GitHub pauses a
public repository's scheduled workflows after 60 days without activity; each
run resets that timer (its first step, **Keep the monthly schedule active**),
but if the **Actions** tab ever says the workflow was disabled, re-enable it
there.

## Fixing it

### Option A: ask Claude to do it (recommended)

Use this for a routine update as well as a failed run. It needs a Claude
that can work on GitHub for you: Claude Code on the web (claude.ai/code) or
in the desktop app, with **Basche1490/silo-android** selected as the
repository. Start a new session and paste the prompt below as it is. It
needs no earlier chat history: the guide it points to holds the rest.

```
Please update my Silo Android TV fork, github.com/Basche1490/silo-android, with the newest code from github.com/Silo-Server/silo-android (main branch).

Rules: only push to my fork's main branch, even if this session was set up with a different working branch. Never push to Silo-Server/silo-android and never open a pull request there. Don't change .github/zidoo-debug.keystore or the application ID.

My fork adds Zidoo external-player support: playing in the Zidoo player through PlexToZidoo, with Silo tracking resume progress, auto-skipping intro and credits, Up Next with a countdown, streaming through Silo's relay (with a Direct Stream Link option), and Fit Interface to TV for the Zidoo's screen density. I want the app to stay as close to stock Silo as possible, with the Zidoo support on top. First read docs/zidoo-updating.md and docs/zidoo-external-player.md in my fork; they list every file the fork adds, every [zidoo-fork] edit to Silo's own files, the Silo code the fork depends on, and the behaviour the fork must keep.

Please:
1. Merge Silo-Server/silo-android main into my fork's main. Resolve any conflicts keeping both Silo's change and every [zidoo-fork] edit. Afterwards every [zidoo-fork] edit the guide lists must still be there. Don't add new edits to Silo's files unless a feature needs one.
2. Check what Silo changed in the code the fork depends on. Adapt the fork's files so they still build and behave as before, including changes that build cleanly but alter playback, such as quality, bandwidth limits, network routing, sessions, Up Next or settings. Keep every point under "Behaviour the fork must keep"; where Silo changed how its own player does the same thing, follow Silo. Update the two docs if you add or change a [zidoo-fork] edit, a dependency or a behaviour.
3. Run the External Player unit tests. If your environment can't build Android, run the ones the guide's "Testing without the Android SDK" section describes, and say which ran. Push to main and wait for the "Zidoo TV APK" GitHub Action to pass. If no run starts within a few minutes, check whether GitHub Actions is turned off for my fork, tell me how to turn it on, and then start the workflow manually.
4. From the run's log, confirm the APK is a non-debuggable release build signed with my fork's key, and that its versionCode is no lower than the previous release's, so it installs over my current app. Confirm the new release is marked Latest.
5. Tell me in plain language what changed in Silo, what you changed in the fork, and what I should test on the Zidoo.
```

### Option B: fix it with a Claude chat that can't reach GitHub

Use this in a normal chat where Claude can't run commands or push. You make
the changes on GitHub's website and paste what Claude asks for. Paste:

```
I have a fork of the Silo Android TV app at github.com/Basche1490/silo-android that adds Zidoo external-player support. Every fork edit to Silo's own files is marked [zidoo-fork], and docs/zidoo-updating.md in the fork lists what the fork adds. Its "Zidoo TV APK" GitHub Action merges the newest code from github.com/Silo-Server/silo-android every month, and it just failed at this step: [paste the failed step's name]. The run summary says: [paste the summary or the error lines].

Walk me through fixing it step by step using only GitHub's website. I'll paste each conflicting section or error as you ask. For each conflict, tell me exactly what the result should be, keeping both Silo's change and every [zidoo-fork] line. Never have me press "Discard commits", push to Silo-Server/silo-android, or open a pull request there. Afterwards, tell me how to run the Zidoo TV APK action and confirm it passed.
```

On GitHub's website a clash is resolved roughly like this:

1. On the fork's main page press **Sync fork**. With a clash, GitHub offers
   to open a pull request instead of updating. Press **Open pull request**,
   never **Discard commits**.
2. Check the pull request goes **into Basche1490/silo-android main** (the
   "base repository"). If it says Silo-Server, change it before creating.
3. In the pull request press **Resolve conflicts**. For each conflict, edit
   the text so it keeps Silo's new lines and the `[zidoo-fork]` lines, then
   delete the `<<<<<<<`, `=======` and `>>>>>>>` markers.
4. Press **Mark as resolved**, then **Commit merge**, then **Merge pull
   request**. The merge starts a new Zidoo TV APK build.

If GitHub says the conflicts are too complex to resolve on the web, use option
A or C.

### Option C: fix it yourself with git

On a computer with git:

    git clone https://github.com/Basche1490/silo-android && cd silo-android
    git remote add upstream https://github.com/Silo-Server/silo-android.git
    git fetch upstream
    git merge upstream/main
    # Resolve each file git lists, keeping Silo's change and the [zidoo-fork] lines.
    git grep -n "\[zidoo-fork\]"    # should still list all the edits below
    git commit
    git push origin main

## What the fork adds

### New files

These exist only in the fork, so Silo's updates never clash with them. Keep
them all.

| Part | Files |
| --- | --- |
| PlexToZidoo launch, Zidoo control API, playback monitor, skips, Up Next end rule, track mapping, stream relay, capabilities, settings | `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/external/` |
| Their unit tests | `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/external/` |
| Hand-off, foreground service, screens, Up Next, settings group, interface scale, wiring | `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/external/` |
| Build and release workflow, signing key | `.github/workflows/zidoo-tv-apk.yml`, `.github/zidoo-debug.keystore` |
| Documentation | `docs/zidoo-external-player.md`, `docs/zidoo-updating.md` |

### Edits to Silo's own files

These are the only places a clash can happen. Each edit is marked
`[zidoo-fork]`; after an update, `git grep -n "\[zidoo-fork\]"` should still
find all of them.

**`androidTvApp/src/androidMain/AndroidManifest.xml`**, before `<application`:

```xml
<!-- [zidoo-fork] External Player: lets Settings see whether PlexToZidoo is installed. -->
<queries>
    <package android:name="com.hpn789.plextozidoo" />
</queries>
```

and inside `<application>`, with the other services:

```xml
<!-- [zidoo-fork] External Player: keeps Silo alive while the Zidoo player
     plays, so resume progress and marker skips keep working. -->
<service
    android:name=".ui.screens.player.external.ZidooHandoffService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback" />
```

**`androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/MainTvActivity.kt`**,
inside `class MainTvActivity`:

```kotlin
// [zidoo-fork] Zidoo players report a low screen density, which shrinks the
// whole interface; lay it out at Android TV's standard size instead.
override fun attachBaseContext(newBase: android.content.Context) {
    super.attachBaseContext(newBase)
    org.siloserver.silo.tv.ui.screens.player.external.TvInterfaceScale.overrideConfiguration(newBase)
        ?.let(::applyOverrideConfiguration)
}
```

**`androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/di/AndroidTvModule.kt`**,
inside `val androidTvModule = module { … }`:

```kotlin
// [zidoo-fork] Settings → External Player (Zidoo via PlexToZidoo).
includes(org.siloserver.silo.tv.ui.screens.player.external.externalPlayerTvModule)
```

and as an argument where the module builds `ProfileAwayTracker(…)`:

```kotlin
// [zidoo-fork] Playback in the Zidoo player is playback on another screen.
isCasting = org.siloserver.silo.tv.ui.screens.player.external.ZidooHandoffCoordinator::isHandingOffNow,
```

and as an argument where the module builds `PlaybackSessionManager(…)`:

```kotlin
// [zidoo-fork] A Zidoo hand-off ignores bandwidth limits learned from Silo's own player.
bufferingQualityMode = { org.siloserver.silo.tv.ui.screens.player.external.ZidooHandoffCoordinator.bufferingQualityModeNow() },
```

**`androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt`**,
two imports:

```kotlin
import org.siloserver.silo.tv.ui.screens.player.external.ZidooHandoffArgs // [zidoo-fork]
import org.siloserver.silo.tv.ui.screens.player.external.handOffToZidooIfEnabled // [zidoo-fork]
```

and, in the `TvRoute.Player` destination, a block just before Silo's own
player is composed (before the `TvPlayerScreen(` call and the playback-display
binding that precedes it). It passes the destination's values (content id,
file, quality, resume position, tracks, room, cast launch, shuffle,
auto-advance count) to `handOffToZidooIfEnabled`, returns from the destination
when it took over, and opens the next episode's `TvRoute.Player` for Up Next.
If a merge removed it, copy it back from the fork's version before the merge:
during a merge, `git show HEAD:androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt`
prints it, and on GitHub the file's **History** shows it.

**`androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/settings/TvSettingsScreen.kt`**,
one import:

```kotlin
import org.siloserver.silo.tv.ui.screens.player.external.TvExternalPlayerSettingsGroup // [zidoo-fork]
```

and one list item in the general settings pane, just before the Experimental
group:

```kotlin
// [zidoo-fork] Settings → External Player (Zidoo via PlexToZidoo).
item { TvExternalPlayerSettingsGroup() }
```

### Silo code the fork depends on

A clean merge can still break the fork if Silo renames or reshapes code the
fork calls. The build step then fails with errors in the fork's files. These
are the dependencies to check:

- **Playback start:** `TvVideoPlaybackStarter` (Koin name
  `tvVideoPlaybackStarter`), `VideoPlaybackStartRequest` (including
  `recoveryStartParams` and `resumePositionOverride`) and
  `VideoPlaybackStartResult.Ready` (session id, file and versions, stream URL
  and headers, delivery and play method, `sourceStartPositionSeconds`, intro
  and credits markers, series, season and episode numbers, access token).
- **Sessions and progress:** `PlaybackSessionLifecycle` (`reportPosition`,
  `stop`, `settlePendingPublicationIfCurrent`, `retireUnpublishedSession`,
  `sessionEndedEvents`) and `PlaybackSessionManager` (`getAccessToken`,
  `confirmVideoSessionPublication`, `rollbackUnpublishedVideoSession`,
  `abandonActiveVideoSessionIfCurrent`, and its `bufferingQualityMode`
  constructor parameter with `BufferingQualityMode.NEVER`).
- **Quality:** `PLAYBACK_QUALITY_AUTO` as the way to ask for the source, and
  the starter's `preferredQualityOverride` and `playbackQualityIntent`.
- **Streaming:** `resolvePlaybackStreamUrl`, the server accepting the access
  token as `Authorization: Bearer` (the relay) and as a `token` query
  parameter on the stream address (Direct Stream Link). The relay sends its
  requests through Silo's video player client (`PLAYER_TRANSPORT_OKHTTP_QUALIFIER`)
  and marks them for the local route as Silo's player does
  (`LocalRoutes.state`, `LocalRouteHeaders.MEDIA`, `httpOrigin`).
- **Capabilities:** `PlaybackCapabilityDetector` and the playback context
  models the server plans with (`ClientCodecCapabilities`,
  `ClientPlaybackContext`, the `original_http` delivery).
- **Up Next:** `CatalogRepository.getEpisodes` and `getSeasons`,
  `nextEpisodeAfter`, `PlayerSettingsStore.autoPlayNextFlow` and
  `passOutThresholdFlow`, and `TvRoute.Player`'s arguments. `ZidooEndWatch`
  follows the rule in `TvPlayerViewModel` that offers Up Next when playback
  crosses the credits start; if Silo changes that rule, follow it.
- **Profiles:** `ProfileAwayTracker`'s `isCasting` parameter and
  `onRemotePlaybackChanged`.
- **Screens and settings:** `TvDialogCard`, `TvPlayerChrome`, `TvPlayerType`,
  `SettingsGroup`, `SettingsToggleRow`, `SettingsValueRow`,
  `SettingsFooterText`, `TvSettingsPickerSheet` and `PickerOption`, and
  `PlayerSettingsStore.themeMusicEnabledFlow` and `setThemeMusicEnabled` for the
  theme music warning.
- **Build:** the `siloVersionName`, `siloBuildNumber`, `siloVersionCode` and
  `siloReleaseChannel` Gradle properties, the `SILO_RELEASE_KEYSTORE*`
  signing variables, Silo's versionCode formula and its `vX.Y.Z-beta.B` tags,
  which the workflow's **Pick the version to stamp** step reads.

### Behaviour the fork must keep

Each of these fixed a real problem on the Zidoo. A merge or an adaptation must
not undo them; the unit tests named in brackets guard the ones they can.

- **Hand-offs get the original file.** The session asks for Auto quality
  (`PLAYBACK_QUALITY_AUTO`; Silo retired Original and turns it into Auto), is
  planned for the Zidoo player's capabilities (`ZidooStartParams`,
  `ZidooPlayerCapabilities`), and sends `learned_limit: ignore` while a
  hand-off starts (`bufferingQualityModeNow`, the `[zidoo-fork]` edit in
  `AndroidTvModule.kt`), so a bandwidth limit Silo's own player learned on the
  box never turns a hand-off into a transcode.
- **The relay connects as Silo's own player does.** It uses the player
  transport client (`PLAYER_TRANSPORT_OKHTTP_QUALIFIER`) over HTTP/1.1, tries
  IPv4 before IPv6 (`Ipv4FirstDns`), and carries Silo's local-route marker at
  home. Android's `HttpURLConnection` failed away from home, most likely on a
  home server's unreachable IPv6 address. The relay reports what it saw on the
  "Couldn't play" card (`ZidooStreamRelayTest`).
- **Direct Stream Link stays a setting, off by default.** It gives the Zidoo
  player the stream address with `token=`, as Plex links carry their token.
  The relay is the default because it keeps the token out of the address and
  renews it during long films.
- **Up Next only at the real end.** It appears only when playback crossed into
  the end credits while playing, or stopped in the last half minute. Stopping
  partway, seeking into the credits, resuming inside them, and a credits marker
  in the first half of the episode (opening credits) never trigger it
  (`ZidooEndWatchTest`).
- **Silo waits long enough for the Zidoo player.** It waits up to five
  minutes (`PLAYER_START_TIMEOUT_MS`) for the player to report the video,
  counting from when PlexToZidoo opens. PlexToZidoo's Debug data screen waits
  for its PLAY button, and a 4K remux from a server across the internet takes
  a while to open. With the old one-minute wait, Silo gave up first: it shut
  the relay before the player asked for the video, or, by Direct Stream Link,
  stopped tracking a film that went on playing, so nothing was saved. A
  player that closes without playing still ends the wait within seconds.
- **Theme music stays off while the Zidoo player is used.** Once Silo has
  played any sound since it started, the Zidoo firmware force-stops Silo,
  keep-alive service included, about two seconds after the Zidoo player starts.
  The Hand-off Log showed it ("force-stopped … due to from pid …" by a system
  process) on every title after a theme song had played, however long ago, and
  never after Silo was opened again with Theme Music off. Releasing the theme's
  audio before the hand-off made no difference, so the fork doesn't change
  Silo's theme music code; Settings → External Player warns while Theme Music
  is on and offers to turn it off.
- **Resume lands on the resume point.** The Zidoo player can start a large
  file at 0:00; the monitor moves it to the resume point and saves nothing
  short of it (`ZidooPlaybackMonitorTest`).

### Testing without the Android SDK

Claude's cloud sessions may not be able to download the Android SDK. Most of
the External Player tests are plain Kotlin and can still run there, in a small
Gradle project outside the repository (Kotlin 2.1.20 and the Gradle in
`/opt/gradle-*` work):

1. Copy `ZidooStreamRelay.kt`, `ZidooEndWatch.kt`, `ZidooPlaybackMonitor.kt`,
   `ZidooSkipPlanner.kt` and `ZidooPlayerApi.kt` from
   `android-shared/src/androidMain/.../player/external/` into its
   `src/main/kotlin`, with a stub `android.util.Log` object that has a
   `w(tag, msg, tr)` function.
2. Copy `ZidooStreamRelayTest.kt`, `ZidooEndWatchTest.kt`,
   `ZidooPlaybackMonitorTest.kt` and `ZidooSkipPlannerTest.kt` into its
   `src/test/kotlin`.
3. Depend on `com.squareup.okhttp3:okhttp:4.12.0`,
   `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2`,
   `org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1` and
   `kotlin("test-junit")` (use the versions in `gradle/libs.versions.toml`),
   and run `gradle test`.

The tests that need Silo's own models (capabilities, PlexToZidoo launch,
settings) run only in the GitHub Action.

## After an update: check on the Zidoo

Install the new APK from the Downloader address, then check:

1. A movie opens in the Zidoo player and plays.
2. Stopping partway and resuming starts where you stopped. A large 4K remux
   may start at 0:00 for a few seconds and then jump there.
3. An episode with an intro skips it, if Skip Intros is on.
4. At the end of an episode, Up Next counts down and plays the next one.
5. Silo's menus are full size (Settings → Interface Size → Fit Interface to
   TV).
6. Settings → External Player still lists Play in Zidoo Player, the skip
   options, Up Next Countdown and Direct Stream Link.
7. Settings → Playback → Maximum Bitrate is set to no limit. A cap makes the
   server convert large files, which the Zidoo player can't be given.
8. With Direct Stream Link off, a 4K remux plays through the relay both at
   home and away from home. If it doesn't, the "Couldn't play" card says what
   the relay saw; Direct Stream Link on plays around it.
9. Stopping an episode partway returns to the show with no Up Next countdown.
10. With Skip Credits Automatically on, the credits are skipped at the end of
    an episode, not right after its intro.
11. Seeking more than an hour into a long film still works.
12. Settings → Theme Music is off, and Settings → External Player shows its
    Theme Music warning when you turn it on.
