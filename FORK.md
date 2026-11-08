# jmgilman/minecraft-numen

Personal fork of [Dwinovo/minecraft-numen](https://github.com/Dwinovo/minecraft-numen) for the Craftoria
server (Minecraft 1.21.1, NeoForge). The upstream README and docs are unchanged.

## Branches

- `jm/1.21.1` — what runs on Craftoria. Cut from upstream's unreleased `1.21.1` branch (`19c8710b0`), because
  that is where the server-side brain, the server's remote MCP endpoint and the KubeJS bridge live; the last
  release (`v0.1.4.1-1.21.1-beta`) has none of them. Rebase onto upstream when it moves.
- `jm/26.1.2`, `feat/refinedstorage-plugin` — earlier FTB Evolution 2 work, kept for reference.

## Changes on top of upstream

| Change | Why |
|---|---|
| `plugins/kubejs/scripts/numen_ae2.js` | `ae2.network.items / count / extract / insert`: an ME terminal shows its items only on a player's screen, so companions had no way to see or use the network. Deployed as a KubeJS server script, not in the jar. |
| `Ae2ScriptGameTests` + AE2/GuideME on the KubeJS GameTest run | Tests the deployed script against real AE2 headless. |
| `ScriptErrors` recognises `throw new ApiError(...)` from a script | Scripts can fail with real kinds (`out_of_reach`, `not_found`) instead of a generic `failed`. |

Script pitfalls found on the way (KubeJS's Rhino): a `const` declared in a loop body keeps its first value on
every pass (use `let`); `==` between two Java objects compares wrappers (compare ids); overloaded Java
methods may need the explicit `Class['method(sig)']` form; KubeJS binds names like `Direction` globally, so
the script lives inside a function.

## Dev loop

JDK 21 comes from mise (`mise install`). Secrets are read from the sibling `../minecraft` checkout
(`.calagopus-key`, `.ssh-key`). `mise tasks` lists:

| Task | What it does | Time |
|---|---|---|
| `mise run test:ae2` | Headless GameTests: NeoForge + Numen + KubeJS + AE2 + GuideME; installs the real script and `/reload`s it. | ~30 s |
| `mise run craftoria:script` | Uploads the script through the Calagopus panel API and runs `/reload` on the server. | seconds |
| `mise run craftoria:log` | Tail of the server's KubeJS log (script errors). | seconds |
| `mise run craftoria:cmd <command>` | Any console command through the panel. | seconds |
| `mise run jar` | Release jars into `build/release/` (`datagenAll releaseJars`, as upstream's publish workflow). | ~3 min |
| `mise run install:server` | Stops Craftoria, swaps the Numen jar (old one to `~agent/numen-backup`), starts it. | ~2 min |
| `mise run install:client` | Puts the jar into the Craftoria Prism instance. | — |

Script changes: `test:ae2`, then `craftoria:script`, then try it in game; no restart anywhere. Java changes
need `jar` and both installs.

## Server endpoint

`config/numen/server/mcp_server.json` on the server: enabled, `0.0.0.0:25570` (Craftoria's second panel
allocation). Plain HTTP on the LAN; tokens come from `/numen token create brain …` in game.
