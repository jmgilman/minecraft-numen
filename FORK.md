# jmgilman/minecraft-numen

Personal fork of [Dwinovo/minecraft-numen](https://github.com/Dwinovo/minecraft-numen) for the Craftoria
server (Minecraft 1.21.1, NeoForge). The upstream README and docs are unchanged.

## Branches

- `jm/1.21.1` — what runs on Craftoria. Based on upstream's unreleased `1.21.1` branch (last rebased onto
  `83239cee4` on 2026-11-08), because that is where the server-side brain, the server's remote MCP endpoint and
  the KubeJS bridge live; the last release (`v0.1.4.1-1.21.1-beta`) has none of them. Rebase onto upstream when
  it moves.
- `jm/26.1.2`, `feat/refinedstorage-plugin` — earlier FTB Evolution 2 work, kept for reference.

## Changes on top of upstream

| Change | Why |
|---|---|
| `plugins/kubejs/scripts/numen_ae2.js` | `ae2.network.items / count / extract / insert`: an ME terminal shows its items only on a player's screen, so companions had no way to see or use the network. Deployed as a KubeJS server script, not in the jar. |
| `Ae2ScriptGameTests` + AE2/GuideME on the KubeJS GameTest run | Tests the deployed script against real AE2 headless. |
| `plugins/kubejs/scripts/numen_functionalstorage.js` | `fs.drawers.nearby / find / contents / take / put / empty / link / unlink`: Functional Storage drawers take and give items through clicks aimed at a slot on their face (a stack only while sneaking, everything only on a double click), which a companion cannot do reliably. The script uses the drawers' item handler instead, on a drawer or through a storage controller, and links drawers to a controller as the Linking Tool does (she must carry one). Deployed as a KubeJS server script. |
| `FunctionalStorageScriptGameTests` + Functional Storage/Titanium on the KubeJS GameTest run | Tests the deployed script against the Functional Storage jar Craftoria ships (1.21.1-1.5.8 from CurseMaven, Titanium 1.21-4.0.50 from Modrinth). |
| `ScriptErrors` recognises `throw new ApiError(...)` from a script | Scripts can fail with real kinds (`out_of_reach`, `not_found`) instead of a generic `failed`. |
| `ServerLang` skips an unreadable lang file (or one bad value) with a warning | Server-side translation for players without the Numen client threw on malformed lang JSON, and a throw while sending a system message on the main tick crashes the server. Craftoria ships broken JSON in nine locales (de_de, es_ar, fr_fr, ko_kr, pl_pl, pt_br, ru_ru, tr_tr, zh_cn). Upstream `99983eb02` fixed only component-valued entries. Worth sending upstream. |

Script pitfalls found on the way (KubeJS's Rhino): a `const` declared in a loop body keeps its first value on
every pass (use `let`); `==` between two Java objects compares wrappers (compare ids); overloaded Java
methods may need the explicit `Class['method(sig)']` form; KubeJS binds names like `Direction` globally, so
the script lives inside a function.

## Dev loop

JDK 21 comes from mise (`mise install`). Secrets are read from the sibling `../minecraft` checkout
(`.calagopus-key`, `.ssh-key`). `mise tasks` lists:

| Task | What it does | Time |
|---|---|---|
| `mise run test:scripts` | Headless GameTests: NeoForge + Numen + KubeJS + AE2 + Functional Storage; installs the real scripts and `/reload`s them. | ~1 min |
| `mise run craftoria:script` | Uploads every `plugins/kubejs/scripts/*.js` through the Calagopus panel API and runs `/reload` on the server. | seconds |
| `mise run craftoria:log` | Tail of the server's KubeJS log (script errors). | seconds |
| `mise run craftoria:cmd <command>` | Any console command through the panel. | seconds |
| `mise run jar` | Release jars into `build/release/` (`datagenAll releaseJars`, as upstream's publish workflow). | ~3 min |
| `mise run install:server` | Stops Craftoria, swaps the Numen jar (old one to `~agent/numen-backup`), starts it. | ~2 min |
| `mise run install:client` | Puts the jar into the Craftoria Prism instance. | — |

Script changes: `test:scripts`, then `craftoria:script`, then try it in game; no restart anywhere. Java changes
need `jar` and both installs.

## Server endpoint

`config/numen/server/mcp_server.json` on the server: enabled, `0.0.0.0:25570` (Craftoria's second panel
allocation). Plain HTTP on the LAN; tokens come from `/numen token create brain …` in game.
