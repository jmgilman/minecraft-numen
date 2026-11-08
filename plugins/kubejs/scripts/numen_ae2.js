// Numen ↔ Applied Energistics 2: a companion reads, takes from and puts into an ME network through any
// block of it within reach (terminal, interface, drive, cable, ...), the way a player uses a terminal.
//
// An ME terminal sends its item list only to a player's screen, so a companion that opens one sees
// nothing. These functions go through AE2's API instead, on the server thread, with the companion as
// the acting player (AE2's own checks apply) and AE2's energy costs charged as for a terminal.
// Each call goes through Numen's permission layer as `use_block` on the named block.
//
// Install: kubejs/server_scripts/ on the server, then /reload. Needs Numen with its KubeJS plugin.

// KubeJS server scripts share one global scope (and KubeJS binds names like Direction there): everything here
// lives inside this function so nothing collides with the pack's other scripts. Inside functions every binding is
// `let`: KubeJS's Rhino keeps the first value of a `const` declared in a loop body on every later pass.
;(function () {
const GridHelper = Java.loadClass('appeng.api.networking.GridHelper')
const StorageHelper = Java.loadClass('appeng.api.storage.StorageHelper')
const IActionSource = Java.loadClass('appeng.api.networking.security.IActionSource')
const AEItemKey = Java.loadClass('appeng.api.stacks.AEItemKey')
const Direction = Java.loadClass('net.minecraft.core.Direction')
const BuiltInRegistries = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries')
const ApiError = Java.loadClass('com.dwinovo.numen.script.ApiError')
const ErrorKind = Java.loadClass('com.dwinovo.numen.script.ErrorKind')

let NODE = 'A block of the ME network within your reach: a terminal, interface, drive, cable, ...'

function at(pos) {
  return pos.x + ',' + pos.y + ',' + pos.z
}

// Rhino's == on two Java objects compares its wrappers, not the objects: compare items by registry id.
function idOf(item) {
  return String(BuiltInRegistries.ITEM.getKey(item))
}

// KubeJS lets an ItemStack stand in for an ItemLike, so AEItemKey.of(stack) is ambiguous to Rhino: name the overload.
function keyOf(stack) {
  return AEItemKey['of(net.minecraft.world.item.ItemStack)'](stack)
}

// The powered grid the block at pos belongs to, checked the way a player at a terminal would find it.
function gridAt(her, pos) {
  if (!her.canInteractWithBlock(pos, 0.0)) {
    throw new ApiError(ErrorKind.OUT_OF_REACH, 'the ME block at ' + at(pos)
      + ' is out of reach from where you stand; walk to it first, then call this again',
      'numen.move.to({x = ' + pos.x + ', y = ' + pos.y + ', z = ' + pos.z + '}, {arrive = "use"})')
  }
  let host = GridHelper.getNodeHost(her.serverLevel(), pos)
  if (host == null) {
    throw new ApiError(ErrorKind.NOT_FOUND, 'the block at ' + at(pos) + ' is not part of an ME network', null)
  }
  // getGridNode(side) answers only for sides the node is exposed on for cable connections, which full blocks inside a
  // network may not be; AE2's own block entities hand out their node directly. A cable bus (terminals, buses on a
  // cable) has no main node: its cable answers for side null and each part for its own side.
  let node = typeof host.getMainNode === 'function' ? host.getMainNode().getNode() : null
  if (node == null) {
    let sides = [null]
    for (let d of Direction.values()) sides.push(d)
    for (let side of sides) {
      node = host.getGridNode(side)
      if (node != null) break
    }
  }
  if (node == null || node.getGrid() == null) {
    throw new ApiError(ErrorKind.NOT_FOUND, 'the block at ' + at(pos) + ' is not connected to an ME network yet', null)
  }
  let grid = node.getGrid()
  if (!grid.getEnergyService().isNetworkPowered()) {
    throw new ApiError(ErrorKind.FAILED, 'the ME network at ' + at(pos)
      + ' has no power — it needs energy before anything in it can be reached', null)
  }
  return grid
}

// Every stored variant of one item (enchantments, damage, ... make separate AE2 keys).
function keysOf(storage, item) {
  let keys = []
  // The live view, not getCachedInventory(): the cache refreshes once a tick and would miss what this call just moved.
  let it = storage.getInventory().getAvailableStacks().iterator()
  while (it.hasNext()) {
    let entry = it.next()
    let key = entry.getKey()
    if (AEItemKey.is(key) && idOf(key.getItem()) === idOf(item)) keys.push({ key: key, amount: entry.getLongValue() })
  }
  return keys
}

function stored(storage, item) {
  let total = 0
  for (let k of keysOf(storage, item)) total += k.amount
  return total
}

NumenEvents.register(event => {
  event.plugin('ae2', plugin => {
    plugin.group('network', 'Applied Energistics 2 ME networks: list, count, take out and put in through a block '
      + 'of the network you can reach.', group => {

      group.fn('items', fn => fn
        .summary('Everything the ME network stores, one line per item ("<id> x<count>"), most first.')
        .param('node', 'pos', NODE)
        .option('filter', 'string', 'Only items whose id contains this text, e.g. "iron".', 'list every item')
        .returns('string[]')
        .pending()
        .example('ae2.network.items({x = -155, y = 99, z = 79})')
        .example('ae2.network.items({x = -155, y = 99, z = 79}, {filter = "ingot"})')
        .note('Use it instead of opening a terminal: a terminal\'s window shows you nothing.')
        .run(call => call.useBlock(call.arg('node'), (her, pos) => {
          let filter = call.option('filter') == null ? '' : String(call.option('filter')).toLowerCase()
          let totals = {}
          let it = gridAt(her, pos).getStorageService().getInventory().getAvailableStacks().iterator()
          while (it.hasNext()) {
            let entry = it.next()
            if (!AEItemKey.is(entry.getKey())) continue
            let id = idOf(entry.getKey().getItem())
            if (id.indexOf(filter) < 0) continue
            totals[id] = (totals[id] || 0) + Number(entry.getLongValue())
          }
          return Object.keys(totals)
            .sort((a, b) => totals[b] - totals[a] || (a < b ? -1 : 1))
            .map(id => id + ' x' + totals[id])
        })))

      group.fn('count', fn => fn
        .summary('How many of one item the ME network stores, all variants counted together.')
        .param('node', 'pos', NODE)
        .param('item', 'item', 'The item to count.')
        .returns('integer')
        .pending()
        .example('ae2.network.count({x = -155, y = 99, z = 79}, "minecraft:iron_ingot")')
        .run(call => call.useBlock(call.arg('node'), (her, pos) =>
          Math.min(stored(gridAt(her, pos).getStorageService(), call.arg('item')), 2147483647))))

      group.fn('extract', fn => fn
        .summary('Take items of one kind out of the ME network into your inventory.')
        .param('node', 'pos', NODE)
        .param('item', 'item', 'The item to take out.')
        .option('count', 'integer', 'How many to take.', 'take one stack')
        .returns('integer')
        .pending()
        .example('ae2.network.extract({x = -155, y = 99, z = 79}, "minecraft:iron_ingot", {count = 32})')
        .note('Takes what is there, up to count, and only what fits in your inventory; the rest stays in the network.')
        .run(call => call.useBlock(call.arg('node'), (her, pos) => {
          let item = call.arg('item')
          let want = call.option('count') == null ? item.getDefaultMaxStackSize() : Number(call.option('count'))
          let grid = gridAt(her, pos)
          let storage = grid.getStorageService()
          let energy = grid.getEnergyService()
          let source = IActionSource.ofPlayer(her)
          let moved = 0
          for (let k of keysOf(storage, item)) {
            let remaining = Math.min(want - moved, k.amount)
            while (remaining > 0) {
              let chunk = Math.min(remaining, item.getDefaultMaxStackSize())
              let got = Number(StorageHelper.poweredExtraction(energy, storage.getInventory(), k.key, chunk, source))
              if (got <= 0) break
              let stack = k.key.toStack(got)
              her.getInventory().add(stack)
              let fitted = got - stack.getCount()
              moved += fitted
              remaining -= fitted
              if (!stack.isEmpty()) {
                // Inventory full: what did not fit goes straight back, nothing drops at her feet.
                StorageHelper.poweredInsert(energy, storage.getInventory(), k.key, stack.getCount(), source)
                remaining = 0
                k.full = true
              }
            }
            if (moved >= want || k.full) break
          }
          call.report('took ' + moved + ' ' + idOf(item) + ' out of the ME network; you carry '
            + her.getInventory().countItem(item) + ', the network holds ' + stored(storage, item))
          return moved
        })))

      group.fn('insert', fn => fn
        .summary('Put items of one kind from your inventory into the ME network.')
        .param('node', 'pos', NODE)
        .param('item', 'item', 'The item to put in.')
        .option('count', 'integer', 'How many to put in.', 'put in every one you carry')
        .returns('integer')
        .pending()
        .example('ae2.network.insert({x = -155, y = 99, z = 79}, "minecraft:cobblestone")')
        .example('ae2.network.insert({x = -155, y = 99, z = 79}, "minecraft:oak_log", {count = 16})')
        .note('Puts in what the network has room for; the rest stays in your inventory.')
        .run(call => call.useBlock(call.arg('node'), (her, pos) => {
          let item = call.arg('item')
          let want = call.option('count') == null ? Number.MAX_SAFE_INTEGER : Number(call.option('count'))
          let grid = gridAt(her, pos)
          let storage = grid.getStorageService()
          let energy = grid.getEnergyService()
          let source = IActionSource.ofPlayer(her)
          let inventory = her.getInventory()
          // Only the 36 main slots: what she wears and holds in her off hand is not put away.
          let main = inventory.items
          let moved = 0
          for (let slot = 0; slot < main.size() && moved < want; slot++) {
            let stack = main.get(slot)
            if (stack.isEmpty() || idOf(stack.getItem()) !== idOf(item)) continue
            let offer = Math.min(stack.getCount(), want - moved)
            let accepted = Number(StorageHelper.poweredInsert(energy, storage.getInventory(), keyOf(stack),
              offer, source))
            if (accepted > 0) {
              stack.shrink(accepted)
              inventory.setChanged()
              moved += accepted
            }
            if (accepted < offer) break // the network is full
          }
          call.report('put ' + moved + ' ' + idOf(item) + ' into the ME network; you carry '
            + inventory.countItem(item) + ', the network holds ' + stored(storage, item))
          return moved
        })))
    })
  })
})
})()
