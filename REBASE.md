# Syncing Haven's termlib fork with upstream

This fork carries Haven-specific patches on top of `connectbot/termlib`.
The delta is no longer small (~100 commits: IME campaigns, gesture and
keyboard-reflow work, agent/MCP accessors), so upstream is pulled in by
**merge, not rebase** — one conflict pass instead of replaying every
fork commit across upstream's formatting sweeps and renderer rewrites.
Precedent merges: `9e93fc4` (0.0.35-SNAPSHOT era), `8175d78` (0.1.1-SNAPSHOT,
2026-07-11).

Upstream remote: `upstream` → `https://github.com/connectbot/termlib.git`
Haven branch: `feature/154-cf-access-diagnostics` on `origin`
             (despite the name, this is the general Haven-patch branch)

## Sync checklist

Run when upstream cuts a release tag, or roughly monthly.

```bash
cd /home/ian/Code/Haven/termlib

# 1. Pull latest upstream
git fetch upstream

# 2. Merge on a scratch branch — don't touch the pinned branch until green
git checkout -b merge-upstream feature/154-cf-access-diagnostics
git merge upstream/main

# 3. Resolve conflicts (see policy below), then
./gradlew :lib:testDebugUnitTest spotlessCheck

# 4. Fast-forward the real branch and push
git checkout feature/154-cf-access-diagnostics
git merge --ff-only merge-upstream && git branch -d merge-upstream
git push origin feature/154-cf-access-diagnostics

# 5. From Haven root: pin the submodule, regen dep verification, rebuild
cd /home/ian/Code/Haven
git add termlib
./gradlew --write-verification-metadata sha256 \
    :feature:terminal:testDebugUnitTest :app:assembleArm64Debug
git add gradle/verification-metadata.xml
git commit -m "Bump termlib: merge upstream <release>"
```

## Conflict-resolution policy (what worked for `8175d78`)

- `ImeInputView.kt` (+ test) and `Terminal.kt`: keep **ours wholesale**.
  Upstream evolves parallel fixes to the same IME bugs and its own
  gesture/renderer world; Haven's versions are device-verified
  (#96/#99/#110/#115/#206/#298). Porting upstream renderer perf work is
  a deliberate, separate task.
- `TerminalEmulator.kt`: union — Haven agent/MCP surface + upstream
  internals; re-express Haven damage tweaks via upstream's machinery.
- URL detection: upstream grid primary, Haven `UrlBlobDetector` as
  tap-time fallback (see `TerminalScreenState.getHyperlinkUrlAt`).
  Haven behavior is pinned by `HavenUrlRegressionTest`; divergences from
  upstream tests are marked with `// Haven fork:` comments.
- Build files: keep fork-minimal (no publish/dokka/metalava/kover/
  roborazzi/sonarqube); drop upstream tests that need those plugins;
  take upstream's version pins.

## What should live on this fork

The long-term goal is still to shrink the fork by upstreaming whatever
could benefit other termlib hosts (upstream has already absorbed the
scroll-position work, wrapped-URL detection concepts, and IME
robustness fixes in its own form). Haven-opinionated UX belongs in
Haven's `core/terminal-haven` wrapper where a public seam exists;
patches stay here only while upstream lacks the seam.

## If an upstream PR is rejected

Document the rejection in `/home/ian/Code/Haven/docs/termlib-rebase.md`
so a future maintainer can tell why a patch is still here.
