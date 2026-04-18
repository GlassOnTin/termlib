# Rebasing Haven's termlib fork onto upstream

This fork carries a small set of Haven-specific patches on top of
`connectbot/termlib`. Goal is to keep the delta small enough that rebasing
onto upstream's latest takes under 30 minutes.

Upstream remote: `upstream` → `https://github.com/connectbot/termlib.git`
Haven branch: `fix-popScrollbackLine-0.0.22` on `origin`
             (despite the name, this is now the general Haven-patch branch)

## Monthly checklist

Run on the first Monday of each month, or when upstream cuts a release
tag.

```bash
cd /home/ian/Code/Haven/termlib

# 1. Pull latest upstream
git fetch upstream

# 2. Rebase — only Haven-only patches should replay
git checkout fix-popScrollbackLine-0.0.22
git rebase upstream/main

# 3. If conflicts: resolve, then
#    ./gradlew :lib:testDebugUnitTest
#    to make sure Robolectric tests still pass.

# 4. Push
git push origin fix-popScrollbackLine-0.0.22 --force-with-lease

# 5. From Haven root: pin the submodule to the new tip and rebuild
cd /home/ian/Code/Haven
git -C termlib rev-parse HEAD   # note the SHA
git add termlib
git commit -m "Bump termlib: rebased onto upstream <date/release>"
./gradlew :feature:terminal:testReleaseUnitTest :app:assembleDebug
```

If the rebase takes more than about 30 minutes the fork is drifting
again; open an issue to audit the patches and consider whether any
should be upstreamed.

## What should live on this fork

The long-term goal is 0–3 Haven-only patches. Anything else is a rebase
hazard and should either be upstreamed or pulled into Haven's own
module (`core/terminal-haven`).

Acceptable Haven-only patches:

- `rawKeyboardMode` / `allowStandardKeyboard` flags on `ImeInputView`
  until upstream accepts the factory seam (PR-H in Haven's realignment
  plan).
- `composingText: StateFlow<String>` on `ImeInputView` until upstream
  accepts it for compose-mode hosts (PR-G).

Everything else (scroll drift fixes, Copy/Paste viewport clamping, URL
wrap-boundary detector fixes, IME robustness fixes, public
`getSnapshotLineTexts()` accessor, paste-shortcut hook) belongs upstream
and should be offered as PRs to `connectbot/termlib` rather than
carried here.

## If an upstream PR is rejected

Document the rejection in `/home/ian/Code/Haven/docs/termlib-rebase.md`
so a future maintainer can tell why a patch is still here. If the fork
grows past 5 patches the base choice (staying on termlib at all) should
be re-evaluated.
