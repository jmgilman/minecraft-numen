# jmgilman/minecraft-numen

Personal fork of [Dwinovo/minecraft-numen](https://github.com/Dwinovo/minecraft-numen) for the
FTB Evolution 2 server (Minecraft 26.1.2, NeoForge). The upstream README and docs are unchanged.

## Branches

- `jm/26.1.2` — our integration branch, cut from the upstream release tag
  `v0.1.4.1-26.1.2-beta` (the version the pack ships). Feature branches PR into it.
- Upstream is the `upstream` remote; rebase onto a newer release tag when one ships.

## Changes on top of upstream

| Change | Why |
|---|---|
| Backport of `cb930dfa1` (`AgentLoop.halt` releases the external call) | Upstream issue #139: an interrupted external-brain program left the companion refusing every `lua` call until a client restart. |
| `plugins/refinedstorage` (`rs.network.items / count / extract / insert`) | Refined Storage grids show their items only on a player's screen; companions had no way to see or use the network. |
| `-Pgametests=<selector>` on `runGameTestServer` | Run one batch of GameTests instead of all ~570. |

## Dev loop

JDK 25 comes from mise (`mise install`). Tasks (`mise tasks` lists them):

| Task | What it does | Time (warm Gradle) |
|---|---|---|
| `mise run test:unit` | Unit tests and the plugins' API lint reports; no Minecraft. | seconds to a minute |
| `mise run test:game` | Headless GameTest server with only NeoForge, Numen and Refined Storage; runs the Refined Storage tests by default. Pass a selector for others: `mise run test:game "numen:inventorygametests.*"`. | ~16 s |
| `mise run dev` | Dev client with the same three mods, for poking by hand. | — |
| `mise run jar` | Release jar in `core/neoforge/build/libs/` (runs datagen first, as upstream releases do). | ~3 min |
| `mise run install:client` | Builds the jar and swaps it into the Prism instance's `mods/` (the old jar is kept as `.bak`). | — |

Write the behaviour as a GameTest first (`core/neoforge/.../gametest/`; tests drive a real companion
with `lua(companion, "...")`), then iterate with `mise run test:game`. Only the final check needs the
full modpack.

The jar must be installed on both the client and the server.
