---
name: refined_storage
description: Read, take from and put into a Refined Storage network. Use whenever items are kept in a storage network (grids, disk drives) instead of chests.
---

# Skill: refined_storage

A Refined Storage grid shows its items only on a player's screen: opening one with
`numen.use.block` gives you an empty window, and `numen.scan.container` on a grid or
disk drive does not list the network. Use the `rs.network` functions instead; each
takes any block of the network you can reach (grid, controller, cable, disk drive).

## Look first

```lua
local grid = numen.scan.blocks("refinedstorage:grid", {radius = 16})[1].nearest.pos
numen.move.to(grid, {arrive = "use"})
for _, s in ipairs(rs.network.items(grid, {filter = "ingot"})) do print(s.item, s.count) end
print(rs.network.count(grid, "minecraft:iron_ingot"))
```

## Take out and put back

```lua
local grid = numen.scan.blocks("refinedstorage:grid", {radius = 16})[1].nearest.pos
local r = rs.network.extract(grid, "minecraft:iron_ingot", {count = 32})
print(r.moved, r.carrying, r.left)
rs.network.insert(grid, "minecraft:cobblestone")
```

- `extract` takes only what fits in your inventory; the rest stays in the network.
- `insert` without a count puts in every one you carry.
- An unpowered network answers `failed`: the controller needs energy first.
- A `denied` answer comes from your owner's rules or the network's security manager.
  Say which, and do not try to reach the items another way.

## Crafting from the network

Fetch exactly what the recipe needs (`numen.inv.recipes` tells you), craft, then put
the leftovers and the products you do not need back with `rs.network.insert`, so the
network stays the one place your owner looks.
