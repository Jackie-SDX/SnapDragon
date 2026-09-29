# Changelog

## 1.2 — ThreeSeeds v1.2 (2026-09-29)

### Computer opponent (Vs Computer)
- New **Vs Computer** mode beside Pass & Play. The human always plays
  Player One; the engine plays Player Two. Pass & Play is unchanged.
- **6 difficulty tiers** — Beginner (depth 1, heavy noise), Easy
  (2), Medium (4), Hard (6), Expert (8), Master (12 / iterative until
  the 2500 ms budget). Difficulty changes decision quality only, never
  the rules and never artificial waiting.
- **5 personalities** — Balanced, Aggressive, Defensive, Positional,
  Experimental. Personalities re-weight the evaluation (threats,
  blocks, centre, mobility, taste variance); they never change search
  depth, so an Aggressive Master plays at Master strength with a
  racing style.
- **3 thinking speeds** — Instant, Natural (450 ms), Thoughtful
  (1100 ms) — a presentation beat on top of the real search, set
  separately from difficulty.
- Search: iterative-deepening negamax with alpha-beta, static move
  ordering, wall-clock deadline with completed-depth rollback, every
  move executed through the shared `GameEngine` (so the AI plays by
  exactly the rules a human's tap does, in both FREE and TAPATAN
  rulesets).
- UX safety: taps on the machine's seat are ignored, a visible
  "thinking" indicator shows during the search, undo rewinds the
  computer's answer *and* the human's move together (one ply if you
  undo mid-think), and a match restored after process death resumes
  with the computer's move.

### Themes
- **24 board themes** (was 1), all selectable and previewable in
  Settings: 10 free, 5 at 75 coins, 5 at 150, 4 at 250.
- Each theme reskins the gradient backdrop, board lines, nodes,
  surfaces, accents, and both players' seeds. Functional feedback
  colors (legal destination, invalid flash, winning line) stay fixed
  so their meaning never changes.
- **Animated motif layer** over every theme (petals, embers, stars,
  snow, bubbles, leaves, sparkles, rain, fireflies, bokeh, flowers,
  scales, confetti, geometric) with a 500 ms crossfade when the theme
  changes.
- **Dynamic themes**: hop through unlocked themes every 6 seconds
  (toggleable). Rotation only ever visits themes you own.
- The board renders through the current theme; portrait lock removed
  so the themed board follows rotation.

### Profile, stats, and economy
- Persistent profile (SharedPreferences): coins, wins, losses, draws,
  current win streak, best streak, difficulty, personality, think
  speed, equipped theme, unlocked themes, dynamic-themes toggle.
- **Coins start at 100.** Rewards per finished match:
  | Result | Reward |
  |---|---|
  | Vs Computer win | 10 + 6 × difficulty tier (10–40) + streak bonus |
  | Vs Computer draw | 8 |
  | Vs Computer loss | 2 |
  | Pass & Play — Player One wins | 8 |
  | Pass & Play — Player Two wins | 4 |
  | Pass & Play — draw | 5 |
- Streak bonus: +2 × current win streak on Vs Computer wins, capped
  at +16 (streak 8). The streak resets to 0 on a loss — and on a draw.
- Theme shop in Settings: tap a locked theme to buy it if affordable
  (the purchase also equips it); unaffordable taps explain the price.
- Statistics section in Settings: wins, losses, draws, win streak,
  best streak.
- The end-of-match overlay shows the coins just earned.

### Rules integrity (from the shared AI-gameplay review)
- A match's ruleset is **frozen when the match starts**. The
  Tapatan-style toggle in Settings is persisted immediately but
  applies to the *next* match — a mid-match toggle can never rewrite
  the rules under a game in progress, including across process death.
- The AI opponent is fully bound by `GameEngine.apply()` and the same
  `MoveValidator` path as a human tap: no engine bypass, no special
  cases, works identically under both rulesets.

### Changed
- Undo in Vs Computer removes both plies (the human's last move and
  the computer's answer) so you land back on your own decision.
- Back navigation: from Game/Settings, Back returns to the menu
  (system default on the menu).
- About dialog on the main menu: version, author, and source
  repository.
- Main menu shows your coins and record at a glance.
- App version bumped to 1.2 (versionCode 2).

### Not included (and why)
- Nearby/Wi-Fi/Bluetooth multiplayer, music tracks, notifications,
  Room/DataStore, puzzles, replay, tournament mode, a "Perfect" tier,
  and localization are **not** in this release: they were left out of
  scope rather than shipped half-done. The free-movement variant is a
  solved draw under perfect play, so a Perfect tier would be
  indistinguishable from Master-plus-repetition-awareness; music would
  require shipping audio assets the repo doesn't have.

---

## 1.1 — ThreeSeeds v1.1
- Free-movement (standard) vs Tapatan-style rulesets, toggleable.
- Selectable-seed movement flow with legal-destination highlighting and
  targeted "why didn't that work" coach messages.
- Visual overhaul: glossy disc/ring seeds, glowing board, gradient
  accents; undo/restart/pause overlays.
- 105 tests, all green.

## 1.0 — ThreeSeeds v1.0
- First playable release: Pass & Play, placement + movement phases,
  threefold-repetition draws, hints, accessibility layer (48dp targets,
  TalkBack labels, non-color-only player identity), process-death
  restore, sound/haptics toggles.

---

**Credits** — Created by Jackie (Jackie-SDX).
Source: https://github.com/Jackie-SDX/SnapDragon
