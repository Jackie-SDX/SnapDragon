# ThreeSeeds

A 2-player strategy game in the Three Men's Morris / Tapatan family:
each player has 3 seeds, places them on a 3x3 board, then sends them
to vacant points trying to line up all 3 in a row.

**Status: ThreeSeeds v1.4 — implemented, unit-tested (179 tests, all
green), built into an APK, and exercised end-to-end on an Android
emulator.**
Two local modes (Pass & Play, Vs Computer with 6 difficulty tiers and
5 personalities), **nearby multiplayer over Wi-Fi or Bluetooth**, 26
purchasable board themes with animated motifs (including procedural
dragon and fountain backdrops), a **dynamic vocal soundtrack** (three
free-licensed songs mixed by game intensity) with a Settings toggle,
3D press-physics buttons, seed/win-line/confetti animations with
reduce-motion support, first-run name registration, a coins/stats
economy, and an About/credits dialog. Physical-device and two-device
testing still pending — see "What's verified vs. what isn't below."

**Credits**: created by Jackie (Jackie-SDX) —
https://github.com/Jackie-SDX/SnapDragon. See `CHANGELOG.md` for the
full v1.4 release notes.

## Rules

1. Players alternate turns, Player One first.
2. **Placement phase**: each turn, place one unused seed on any empty point.
3. Once both players have placed all 3 seeds, the **movement phase** begins.
4. In the movement phase, move one of your own seeds to an empty point
   directly connected to it (see Board & movement below). You cannot
   move an opponent's seed, move onto an occupied point, or slide
   through another seed to reach a further point.
5. First player to align all 3 seeds on one of the 8 winning lines
   (3 rows, 3 columns, 2 diagonals) wins immediately.
6. If the same board position with the same player to move occurs for
   the 3rd time, the game is a draw (see Draws below).

## Controls

- **Placement phase**: tap any empty point to place a seed there.
- **Movement phase**: tap one of your own seeds to select it (legal
  destinations highlight); tap a highlighted point to move there; tap
  the same seed again to deselect; tap a different seed of yours to
  switch selection.
- **Undo**: in Pass & Play, steps back exactly one move (including
  undoing a win); in Vs Computer, steps back the computer's answer
  *and* your move together, or just your move if you undo while the
  computer is still thinking.
- **Restart**: clears the board after a confirmation dialog.
- **Pause**: overlay with Resume / Main Menu; game state isn't touched.
- **Play again** (on the Won/Draw screen): starts a new game without
  leaving the game screen.

## Nearby play

- **Host** on one device, **Join** from another over the same Wi-Fi
  network or a Bluetooth link (Nearby play → pick a transport →
  Host/Join). The host is Player One, the joiner Player Two.
- The link is a host-authoritative `|`-framed line protocol: the
  guest sends taps, the host validates them through the same
  `GameEngine` as local play and broadcasts authoritative `STATE`
  snapshots. Undo is disabled and guest-side restart controls are
  hidden — only the host runs the match.
- Nearby matches pay a flat seat-aware reward (win 8 / draw 5 /
  loss 4) and never extend the VS AI streak. Leaving mid-game shows
  "Connection lost"; both sides can offer a rematch.
- Discovery uses UDP broadcast on port 44771 (`TS3-PROBE`) with the
  game link on TCP 44772; Bluetooth uses RFCOMM with a fixed UUID.
  Both devices must be on the same network (Wi-Fi) or paired
  (Bluetooth).

## Vs Computer

- The human plays Player One, the engine plays Player Two. Taps on the
  computer's seat are ignored, and a "thinking" indicator shows while
  it searches.
- **Difficulty** (Beginner → Master) sets search depth and root noise;
  **personality** (Balanced/Aggressive/Defensive/Positional/
  Experimental) re-weights the evaluation only; **thinking time**
  (Instant/Natural/Thoughtful) is presentation pacing only. All three
  are configured in Settings and persisted.
- The search is iterative-deepening negamax with alpha-beta over the
  shared `GameEngine` — the computer plays through exactly the same
  validator path as a human tap, under either ruleset.
- Finished matches pay coins (more for beating higher tiers), grow or
  reset your win streak, and update the statistics shown in Settings.
  Coins buy themes; winning is never required to keep playing.
- Free-movement three men's morris is a solved draw under perfect
  play, so the top tiers are extremely hard to beat but can be held to
  a draw — deliberate losses only happen at the noisy lower tiers.

## Board & movement

Points are numbered:

```
0 1 2
3 4 5
6 7 8
```

Two movement rulesets are supported (`MovementRules` in the engine):

- **FREE (default, the standard rule)** — "A piece may move to any
  vacant point on the board, not just an adjacent one" (Wikipedia,
  *Three men's morris*). This is what the app ships with.
- **TAPATAN (optional toggle in Settings)** — H. J. R. Murray's
  restricted variant: a piece may only slide along a drawn line to an
  immediately adjacent empty point.

The drawn lines (grid plus both diagonals through the center) are one
editable table in `AdjacencyGraph.kt`:

```
0───1───2
│ ╲ │ ╱ │
3───4───5
│ ╱ │ ╲ │
6───7───8
```

So the center (4) connects to all 8 other points; every corner and
edge-midpoint connects to 3 neighbors. Under TAPATAN these are the only
legal moves; the table also decides which connector lines `BoardCanvas`
draws, so the picture never drifts from what's actually legal.

## Draws

A "no legal moves" stalemate is **provably impossible** on this board:
blocking every neighbor of a 3-seed cluster always requires occupying
more cells than the opponent's 3 seeds can cover. Verified by
exhaustively checking every non-terminal 3-vs-3 board configuration —
twice, once for each adjacency model considered during development —
not just argued by hand.

Because neither player can ever be fully immobilized, two careful
players could otherwise shuffle forever — a forced draw under perfect
play, the same way tic-tac-toe is. So the game declares a draw on
**threefold repetition** (the same board + same player-to-move
recurring 3 times), the same mechanism chess uses for the same reason.
The threshold is a named constant (`GameState.REPETITIONS_FOR_DRAW`).

## Architecture

Two Gradle modules:

**`:engine`** — pure Kotlin, zero Android dependencies, plain-JVM
testable. A future UI (or a future networked opponent) only ever talks
to `GameEngine`; nothing else is allowed to mutate game state, which is
what makes server-side move validation possible later without
rewriting the rules.

| Class | Responsibility |
|---|---|
| `Position` | Type-safe wrapper around a 0-8 board index |
| `Player` | The two players, with `opponent()` |
| `GamePhase` | `PLACEMENT` / `MOVEMENT` / `WON` / `DRAW` — engine states only. Main Menu and Restart are app-navigation concerns |
| `AdjacencyGraph` | The board's connection table |
| `Board` | Immutable snapshot of the 9 cells |
| `Move` | Sealed type: `Place` or `Relocate` |
| `GameEvent` | What just happened (placed/moved/won/drawn/rejected) — the UI maps these to haptics/sound/animation directly |
| `GameState` | Immutable snapshot: board, turn, phase, seeds remaining, winner, move history |
| `WinDetector` | The 8 winning lines, defined as data |
| `MoveValidator` | Every legality rule, isolated from turn management |
| `GameEngine` | The single authoritative entry point: `apply()`, `undo()`, `reset()` |

**`:app`** — Android/Compose UI. Depends on `:engine`; never
implements a rule itself.

| Piece | Responsibility |
|---|---|
| `GameViewModel` | Holds a `GameEngine`, exposes `StateFlow<GameUiState>`; every tap ultimately becomes a `Move` sent to `GameEngine.apply()`. Owns the Vs Computer turn loop (seat split, think delay, search on a background dispatcher, stale-result guard), stats/coin recording, and the match-frozen ruleset |
| `GameUiState` | UI-only state (selection, highlighting, pause, mode, thinking flag) layered over the engine's `GameState` |
| `GameStateCodec` | Dependency-free `GameState` <-> `String` for surviving process death via `SavedStateHandle` |
| `AiPlayer` (engine) | Iterative-deepening negamax with alpha-beta; returns a `Move` the view model replays through `GameEngine` |
| `Economy` / `ProfileStore` | Pure reward/streak/shop math plus its persistent (SharedPreferences) and in-memory (tests) stores |
| `ThemeCatalog` / `ThemedBackground` | The 26-theme inventory and the crossfaded, motif-annotated backdrop every screen sits on |
| `BoardCanvas` | Renders the board on a `Canvas`, with a parallel layer of individually-labeled, >=48dp tappable targets so TalkBack sees 9 real elements, not one opaque picture |
| `SettingsRepository` | A handful of booleans in `SharedPreferences` — no DataStore dependency for something this small |
| `SoundEffects` | `ToneGenerator`-based move tones plus a bundled CC0 victory sting for the computer's win |
| `MusicPlayer` | Three bundled vocal tracks via `MediaPlayer` mixed by game intensity (`MusicMix` crossfade), lifecycle-aware, ducking, gated by the Settings music toggle |
| `net/` (`LinkSession`, `LineTransport`, `WifiLan`, `BluetoothLinks`) | The nearby-play stack: protocol sessions, framed transport, Wi-Fi LAN discovery/link, Bluetooth RFCOMM |
| `WelcomeScreen` | First-run name registration; the name feeds the menu summary and nearby-play identity |
| `SettingsStore` / `SoundPlayer` | Interfaces `GameViewModel` actually depends on, so tests can swap in fakes instead of needing a real `Context` or audio system |

Selection state (which seed is currently tapped) lives in `GameUiState`,
not `GameState` — it's local interaction detail, not something that
would ever need to sync in a networked game.

## Project structure

```
ThreeSeeds/
├── engine/
│   ├── src/main/kotlin/com/threeseeds/engine/    15 files: the rules + the AI
│   └── src/test/kotlin/com/threeseeds/engine/    14 files, 87 tests
├── app/
│   ├── src/main/kotlin/com/threeseeds/app/       UI, ViewModel, AI flow, profile, themes, codec, settings, audio, net
│   ├── src/main/res/                             strings, theme, vector adaptive icon, vocal soundtrack + AI-win sting
│   ├── src/test/kotlin/com/threeseeds/app/       14 files, 92 tests
│   └── proguard-rules.pro
├── CHANGELOG.md                                  release notes (v1.0 → v1.4)
├── keystore.properties.example                   copy to keystore.properties, fill in, never commit
├── settings.gradle.kts
└── build.gradle.kts
```

## Accessibility

- **Touch targets**: every board point has an invisible tap target of
  at least 48dp, independent of how small the drawn seed is.
- **Player identity isn't color-only**: Player One draws as a solid
  disc, Player Two as a ring — a real shape difference that survives
  grayscale or color-vision deficiency, not just a different hue.
- **Text scales with the system font-size setting** (`sp` units
  throughout, never fixed `dp` text sizes).
- **Screen readers**: each of the 9 points is a distinct, individually
  labeled element ("Point 5, Player One's seed, selected"), not one
  undifferentiated Canvas. This was the deciding factor against
  LibGDX during the framework choice — a raw OpenGL surface is opaque
  to TalkBack in a way a Canvas-plus-labeled-overlay isn't.

## Testing

179 tests total (87 engine + 92 app), all plain Kotlin/JVM — none
need the Android SDK, an emulator, or a device:

```
./gradlew test              # everything
./gradlew :engine:test      # just the rules
./gradlew :app:test         # ViewModel + save/restore codec
```

`GameViewModel` is tested against fakes (`FakeSettingsStore`,
`FakeSoundPlayer`) rather than real `SharedPreferences`/`ToneGenerator`,
which is why it depends on the `SettingsStore`/`SoundPlayer`
interfaces instead of the concrete Android-backed classes directly.

**Not covered by automated tests**: actual on-screen rendering,
real touch input, TalkBack behavior, and anything requiring two
physical devices (Compose UI/instrumented tests). Rendering, touch
input, theme contrast, music playback, and the host side of nearby
play were instead verified by scripted interaction on an API 35
emulator — see "What's verified vs. what isn't" below.

## Build instructions

**Prerequisites**: [Android Studio](https://developer.android.com/studio)
(free) — bundles the JDK, Gradle, and the Android SDK Manager.

**Configure**: open the project root folder in Android Studio (File →
Open). It generates the Gradle wrapper and syncs automatically —
this first sync needs internet, to download Gradle/Kotlin/AndroidX.

**Build a debug APK**:
```
./gradlew :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

**Install on a connected device** (with USB debugging enabled — see
below):
```
./gradlew :app:installDebug
```
Or just click Run in Android Studio with a device/emulator selected.

**Run all tests**:
```
./gradlew test
```

### Enabling your phone for installs

Settings → About phone → tap "Build number" 7 times (enables Developer
Options) → Settings → System → Developer options → enable USB
debugging → connect via USB → accept the RSA fingerprint prompt.

### Release signing

Real APK distribution needs a signed release build. Generate a
keystore once:

```
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias threeseeds
```

Copy `keystore.properties.example` to `keystore.properties` at the
project root, fill in the password/alias you just chose, and point
`storeFile` at wherever you put `release.jks`. `keystore.properties`
and `*.jks` are gitignored — this file is intentionally never part of
the zips shared in this chat.

**Build the signed release APK**:
```
./gradlew :app:assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk` — installable
on any device that trusts a self-signed key (i.e., any device, via
"install from unknown sources"; only the Play Store cares about a
specific signing identity).

**Reproducibility**: dependency versions are pinned exactly (no `+` or
`latest.release` ranges) in every `build.gradle.kts`, `isMinifyEnabled`
and `isShrinkResources` are both on for release, and `proguard-rules.pro`
is checked in — so the same source + the same Gradle/AGP/Kotlin
versions reliably produce an equivalent APK.

## What's verified vs. what isn't

- **`:engine`**: fully verified by 87 plain-JVM tests, including
  an exhaustive conformance sweep of every labelled 3-vs-3 board
  (1,680 boards × both players × all origins) against a reference model
  derived independently from the eight winning lines — checked under
  BOTH movement rulesets — plus the stalemate-impossibility re-proof.
- **`:app`**: compiles clean against AGP 9.1.1 / compileSdk 37, and
  every tap path is covered by player-level tests (`TapFlowTest`): each
  rule-legal destination is reachable by tapping seed → target, and a
  blocked tap never moves a seed.
- **Build**: `gradle :app:assembleDebug` produces an installable APK
  (published on the GitHub releases page of this repository).
- **Emulator (API 35)**: verified by scripted interaction and
  screenshot analysis — theme selection persists and renders, the
  dark-on-dark contrast bug is gone (blackish pixels 12,451 → 0),
  the welcome flow registers and persists a name, music starts,
  toggles, and pauses with the lifecycle (checked in `dumpsys
  audio`), and a full nearby-play host session ran against a
  scripted guest: handshake → seeded board → taps from both sides →
  state sync → disconnect handling.
- **Not verified**: physical devices, haptics/feel on real hardware,
  a real Bluetooth radio (the emulator has none), two-device LAN
  discovery, and the guest client on a second device (guest-side
  protocol behaviour is covered by JVM tests).

## Nearby multiplayer & future online play

Nearby (same-network / Bluetooth) multiplayer is implemented — see
the "Nearby play" section above. A true internet-wide mode is still
future work, but the engine was built for it:

- `GameEngine.apply(Move)` is the same call a server-authoritative
  session would make — the engine has no notion of "trusted" vs.
  "untrusted" input, it just validates.
- `Move` is a small sealed type (`Place`/`Relocate`) that serializes
  trivially — it's already the shape of a network message.
- `GameEvent` decouples "what happened" from "what the UI does about
  it," which is exactly the split a networked client needs: apply the
  authoritative event stream from the server, drive local
  haptics/sound/animation off it the same way the local UI already
  does.
- `GameState` is fully immutable and serializable (see
  `GameStateCodec` for a working example of encoding it compactly).

Global online play would still need: a matchmaking/room service
(nearby play already discovered the missing piece), server-side
reconnection handling, and (if wanted) accounts, match history, and
leaderboards — all additive on top of the existing host-authoritative
protocol, none of it requiring `:engine` to change.

## Known assumptions

- **Movement rules**: FREE (any vacant point) by default — the standard
  rule; TAPATAN (adjacent along drawn lines only) is a Settings toggle.
  Both are validated in `MoveValidator` against `MovementRules`.
- **Sliding**: under TAPATAN every move is a single hop along a line to
  an adjacent empty point — never through, or over, another seed. Under
  FREE the seed teleports to the chosen vacant point, as the standard
  rule allows.
- **Draws**: threefold repetition of (board, player-to-move), since a
  hard "no legal moves" stalemate cannot occur under these rules.
- **Undo**: unlimited, allowed even after a win/draw. If online play is
  added later, undo would need to become a request-and-consent flow
  instead of instant, since an opponent's client can't be trusted to
  just accept it.
- **Orientation**: locked to portrait (per the original brief's
  preference), but game state itself survives configuration changes
  and process death regardless, via `ViewModel` + `SavedStateHandle`.
- **Sound**: `ToneGenerator`-synthesized move tones (asset-free), a
  bundled CC0 evil-laugh sting when the computer wins, and a three-song
  vocal soundtrack ("Breves Dies Hominis" — public domain; "The Project"
  and "Heartbreak [DEMO]" — CC BY, OpenGameArt) crossfaded by game
  intensity — all credited in the About dialog.
- **Permissions**: `INTERNET` (nearby play's sockets) and the
  Bluetooth permissions split by API level (31+ `BLUETOOTH_CONNECT` /
  `BLUETOOTH_SCAN`, ≤30 `BLUETOOTH` + `BLUETOOTH_ADMIN`). No
  location permission — targetSdk 36 treats local-network access as
  implicit for the discovery sockets. No `VIBRATE` (Compose's
  haptic feedback API doesn't need it).
