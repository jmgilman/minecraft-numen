// Numen ↔ Functional Storage: a companion lists drawers, and takes from and puts into a drawer or the drawers a
// storage controller links, the way a player clicks a drawer's face.
//
// A player's clicks pick the slot from where on the face they look, insert everything only on a second click within
// 300 ms, and take a stack only while sneaking; a drawer's window holds its upgrades, not its items. These functions go
// through the item handler Functional Storage gives every item drawer and controller (the one hoppers and pipes use),
// on the server thread. take, put and empty pass Numen's permission layer as `use_block` on the named block, and she
// must be within reach of it, as for a click.
//
// Install: kubejs/server_scripts/ on the server, then /reload. Needs Numen with its KubeJS plugin, and Functional Storage.

// KubeJS server scripts share one global scope: everything here lives inside this function so nothing collides with
// the pack's other scripts. Inside functions every binding is `let`: KubeJS's Rhino keeps the first value of a `const`
// declared in a loop body on every later pass.
;(function () {
// KubeJS's class filter keeps scripts out of net.neoforged.fml (ModList); its Platform binding answers instead.
if (!Platform.isLoaded('functionalstorage')) return

const ItemControllableDrawerTile = Java.loadClass('com.buuz135.functionalstorage.block.tile.ItemControllableDrawerTile')
const StorageControllerTile = Java.loadClass('com.buuz135.functionalstorage.block.tile.StorageControllerTile')
const StorageControllerExtensionTile =
  Java.loadClass('com.buuz135.functionalstorage.block.tile.StorageControllerExtensionTile')
const FluidDrawerTile = Java.loadClass('com.buuz135.functionalstorage.block.tile.FluidDrawerTile')
const BigInventoryHandler = Java.loadClass('com.buuz135.functionalstorage.inventory.BigInventoryHandler')
const CompactingInventoryHandler = Java.loadClass('com.buuz135.functionalstorage.inventory.CompactingInventoryHandler')
const CompactingUtil = Java.loadClass('com.buuz135.functionalstorage.util.CompactingUtil')
const ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack')
const BuiltInRegistries = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries')
const ServerLifecycleHooks = Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks')
const ApiError = Java.loadClass('com.dwinovo.numen.script.ApiError')
const ErrorKind = Java.loadClass('com.dwinovo.numen.script.ErrorKind')

let DRAWER = 'A drawer, or a storage controller (or controller extension) to reach every drawer linked to it.'
let AMOUNT = '"one", "stack" (one stack of that item) or "all".'
let DEFAULT_RADIUS = 16
let MAX_RADIUS = 64

function at(pos) {
  return pos.x + ',' + pos.y + ',' + pos.z
}

function literal(pos) {
  return '{x = ' + pos.x + ', y = ' + pos.y + ', z = ' + pos.z + '}'
}

// Rhino's == on two Java objects compares its wrappers, not the objects: compare items and blocks by registry id.
function idOf(item) {
  return String(BuiltInRegistries.ITEM.getKey(item))
}

function blockIdAt(level, pos) {
  return String(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()))
}

// Her body, for the functions that only look: they are not gated, so no deed hands it over.
function bodyOf(call) {
  let id = String(call.companion())
  let levels = ServerLifecycleHooks.getCurrentServer().getAllLevels().iterator()
  while (levels.hasNext()) {
    // KubeJS hands a java.util.UUID to scripts as a string and hides Entity.getUUID(), so compare the game profile's
    // id as a string. It also shadows Level.players() with a `players` property: ask for its getPlayers() list.
    let players = levels.next().getPlayers().iterator()
    while (players.hasNext()) {
      let body = players.next()
      if (String(body.getProfile().getId()) === id) return body
    }
  }
  throw new ApiError(ErrorKind.FAILED, 'your body is not in the world', null)
}

function reach(her, pos) {
  if (!her.canInteractWithBlock(pos, 0.0)) {
    throw new ApiError(ErrorKind.OUT_OF_REACH, 'the drawer at ' + at(pos)
      + ' is out of reach from where you stand; walk to it first, then call this again',
      'numen.move.walk(' + literal(pos) + ', 2)')
  }
}

// Every block entity in loaded chunks within radius of (x, y, z), nearest first.
function tilesNear(level, x, y, z, radius) {
  let found = []
  let r2 = radius * radius
  for (let cx = Math.floor(x - radius) >> 4; cx <= Math.floor(x + radius) >> 4; cx++) {
    for (let cz = Math.floor(z - radius) >> 4; cz <= Math.floor(z + radius) >> 4; cz++) {
      let chunk = level.getChunkSource().getChunkNow(cx, cz)
      if (chunk == null) continue
      let tiles = chunk.getBlockEntities().values().iterator()
      while (tiles.hasNext()) {
        let tile = tiles.next()
        let pos = tile.getBlockPos()
        let dx = pos.x + 0.5 - x
        let dy = pos.y + 0.5 - y
        let dz = pos.z + 0.5 - z
        let d2 = dx * dx + dy * dy + dz * dz
        if (d2 <= r2) found.push({ pos: pos, tile: tile, d2: d2 })
      }
    }
  }
  found.sort((a, b) => a.d2 - b.d2)
  return found
}

function isController(tile) {
  return tile instanceof StorageControllerTile || tile instanceof StorageControllerExtensionTile
}

// An item drawer: holds items itself (not a controller, its extension, a fluid drawer or an armory cabinet).
function isDrawer(tile) {
  return tile instanceof ItemControllableDrawerTile && !isController(tile) && tile.getStorage() != null
}

// The item drawers a controller links, in the order it fills them (priority, then nearest).
function linkedTo(level, controller) {
  let handlers = controller.getConnectedDrawers().getItemHandlers()
  let range = controller.getStorageMultiplier()
  let centre = controller.getBlockPos()
  let drawers = []
  for (let near of tilesNear(level, centre.x + 0.5, centre.y + 0.5, centre.z + 0.5, range * 1.75 + 1)) {
    if (!isDrawer(near.tile)) continue
    let index = handlers.indexOf(near.tile.getStorage())
    if (index >= 0) drawers.push({ pos: near.pos, tile: near.tile, index: index })
  }
  drawers.sort((a, b) => a.index - b.index)
  return drawers
}

// What a block gives access to: the drawer itself, or every drawer its controller links.
function storageAt(level, pos) {
  if (!level.isLoaded(pos)) {
    throw new ApiError(ErrorKind.NOT_FOUND, 'the block at ' + at(pos) + ' is not loaded', null)
  }
  let tile = level.getBlockEntity(pos)
  if (tile instanceof StorageControllerExtensionTile) {
    let controllerPos = tile.getControllerPos()
    let controller = controllerPos != null && level.isLoaded(controllerPos) ? level.getBlockEntity(controllerPos) : null
    if (!(controller instanceof StorageControllerTile)) {
      throw new ApiError(ErrorKind.NOT_FOUND, 'the controller extension at ' + at(pos)
        + ' is not linked to a storage controller', null)
    }
    return { name: 'the drawers the controller at ' + at(controllerPos) + ' links', controller: true,
      drawers: linkedTo(level, controller) }
  }
  if (tile instanceof StorageControllerTile) {
    return { name: 'the drawers the controller at ' + at(pos) + ' links', controller: true,
      drawers: linkedTo(level, tile) }
  }
  if (isDrawer(tile)) {
    return { name: 'the drawer at ' + at(pos), controller: false, drawers: [{ pos: pos, tile: tile }] }
  }
  if (tile instanceof FluidDrawerTile) {
    throw new ApiError(ErrorKind.NOT_FOUND, 'the drawer at ' + at(pos) + ' holds fluids, not items', null)
  }
  throw new ApiError(ErrorKind.NOT_FOUND, 'the block at ' + at(pos) + ' (' + blockIdAt(level, pos)
    + ') is not a Functional Storage drawer or storage controller', 'fs.drawers.nearby()')
}

// A void upgrade adds one last slot that holds nothing and destroys what goes in: never list it or put into it.
function realSlots(tile) {
  return tile.getStorage().getSlots() - (tile.isVoid() ? 1 : 0)
}

// The item a slot is set to: what it shows, or for an empty slot of a locked drawer the item it is locked to.
function typeIn(handler, slot) {
  let shown = handler.getStackInSlot(slot)
  if (!shown.isEmpty()) return shown.getItem()
  let kept = ItemStack.EMPTY
  if (handler instanceof BigInventoryHandler) kept = handler.getStoredStacks().get(slot).getStack()
  else if (handler instanceof CompactingInventoryHandler) kept = handler.getResultList().get(slot).getResult()
  return kept.isEmpty() ? null : kept.getItem()
}

// How many a slot holds; a creative drawer's never run out.
function amountIn(tile, handler, slot) {
  if (tile.isCreative()) return Infinity
  let shown = handler.getStackInSlot(slot)
  return shown.isEmpty() ? 0 : shown.getCount()
}

function count(n) {
  return n === Infinity ? 'endless' : String(n)
}

// Every slot set to item id, across the storage's drawers, in fill order.
function slotsOf(storage, id) {
  let slots = []
  for (let drawer of storage.drawers) {
    let handler = drawer.tile.getStorage()
    for (let slot = 0; slot < realSlots(drawer.tile); slot++) {
      let type = typeIn(handler, slot)
      if (type != null && idOf(type) === id) slots.push({ drawer: drawer, handler: handler, slot: slot })
    }
  }
  return slots
}

function stored(storage, id) {
  let total = 0
  for (let s of slotsOf(storage, id)) total += amountIn(s.drawer.tile, s.handler, s.slot)
  return total
}

// One line for a drawer: where, what block, its locks and upgrades, then each slot as "<id> x<count>/<capacity>".
function describe(level, drawer) {
  let tile = drawer.tile
  let handler = tile.getStorage()
  let flags = []
  if (handler instanceof CompactingInventoryHandler) flags.push('compacting, one stock')
  if (tile.isLocked()) flags.push('locked')
  if (tile.isVoid()) flags.push('void upgrade')
  if (tile.isCreative()) flags.push('creative')
  let head = at(drawer.pos) + ' ' + blockIdAt(level, drawer.pos) + (flags.length ? ' [' + flags.join(', ') + ']' : '') + ': '
  if (handler instanceof CompactingInventoryHandler && !handler.isSetup()) {
    return head + '(empty; the first item put in sets it up)'
  }
  let parts = []
  for (let slot = 0; slot < realSlots(tile); slot++) {
    let type = typeIn(handler, slot)
    if (type == null) {
      if (!(handler instanceof CompactingInventoryHandler)) parts.push('(empty)')
      continue
    }
    let n = amountIn(tile, handler, slot)
    parts.push(idOf(type) + ' x' + count(n) + (n === Infinity ? '' : '/' + handler.getSlotLimit(slot)))
  }
  // A compacting drawer's slots are one stock seen as its tiers (block = ingots = nuggets).
  return head + parts.join(handler instanceof CompactingInventoryHandler ? ' = ' : ', ')
}

function describeController(level, near) {
  let head = at(near.pos) + ' ' + blockIdAt(level, near.pos) + ': '
  try {
    let storage = storageAt(level, near.pos)
    return head + 'links ' + storage.drawers.length + ' item drawers'
  } catch (e) {
    return head + 'not linked to a storage controller'
  }
}

function radiusOf(call) {
  let radius = call.option('radius') == null ? DEFAULT_RADIUS : Number(call.option('radius'))
  if (!(radius >= 1 && radius <= MAX_RADIUS)) {
    throw new ApiError(ErrorKind.BAD_ARGUMENT, 'radius must be between 1 and ' + MAX_RADIUS, null)
  }
  return radius
}

// How many items a call moves: count, or amount ("one", "stack", "all"), or the function's default amount.
function wanted(call, item, fallback) {
  let amount = call.option('amount')
  let n = call.option('count')
  if (amount != null && n != null) {
    throw new ApiError(ErrorKind.BAD_ARGUMENT, 'give amount or count, not both', null)
  }
  if (n != null) {
    if (Number(n) < 1) throw new ApiError(ErrorKind.BAD_ARGUMENT, 'count must be at least 1', null)
    return Number(n)
  }
  let which = amount == null ? fallback : String(amount)
  if (which === 'one') return 1
  if (which === 'stack') return item.getDefaultMaxStackSize()
  if (which === 'all') return Number.MAX_SAFE_INTEGER
  throw new ApiError(ErrorKind.BAD_ARGUMENT, 'amount must be "one", "stack" or "all", not "' + which + '"', null)
}

// How many of sample her 36 main slots still take (her hands may take a little more, never less).
function roomFor(inventory, sample) {
  let room = 0
  let main = inventory.items
  for (let i = 0; i < main.size(); i++) {
    let stack = main.get(i)
    if (stack.isEmpty()) room += sample.getMaxStackSize()
    else if (ItemStack.isSameItemSameComponents(stack, sample)) room += Math.max(0, stack.getMaxStackSize() - stack.getCount())
  }
  return room
}

// Takes up to want from one slot into her inventory, a stack at a time and only what fits, so nothing is ever
// dropped. A drawer hands out any amount in one call, so each call asks for at most one stack.
function takeFrom(her, handler, slot, want) {
  let inventory = her.getInventory()
  let moved = 0
  while (moved < want) {
    let sample = handler.extractItem(slot, 1, true)
    if (sample.isEmpty()) return { moved: moved, full: false }
    let room = roomFor(inventory, sample)
    if (room <= 0) return { moved: moved, full: true }
    let got = handler.extractItem(slot, Math.min(want - moved, sample.getMaxStackSize(), room), false)
    if (got.isEmpty()) return { moved: moved, full: false }
    let n = got.getCount()
    inventory.add(got)
    if (!got.isEmpty()) {
      // The room was measured, so this does not happen; if it does, the rest goes back rather than being lost.
      n -= got.getCount()
      let back = handler.insertItem(slot, got, false)
      if (!back.isEmpty()) her.drop(back, false)
    }
    moved += n
  }
  return { moved: moved, full: false }
}

// Where put may go: the slots already set to the item; for a lone drawer with none, its first free slot, the one a
// player's click would set. A controller only fills drawers already set to the item, as its own right-click does.
function targetsFor(level, storage, id, sample) {
  let slots = slotsOf(storage, id)
  if (slots.length > 0) return { slots: slots }
  if (storage.controller) {
    return { slots: [], why: 'no drawer it links is set to ' + id + '; put it into a drawer directly first' }
  }
  let drawer = storage.drawers[0]
  let handler = drawer.tile.getStorage()
  if (drawer.tile.isLocked()) return { slots: [], why: 'the drawer is locked to the items it holds' }
  if (handler instanceof CompactingInventoryHandler) {
    if (handler.isSetup()) return { slots: [], why: 'the compacting drawer is set up for another item' }
    // A player's first click sets a compacting drawer up for the item (its block, ingot and nugget forms).
    let util = new CompactingUtil(level, handler.getResultList().size())
    util.setup(sample.copyWithCount(1))
    handler.setup(util)
    return { slots: slotsOf(storage, id), setUp: handler }
  }
  for (let slot = 0; slot < realSlots(drawer.tile); slot++) {
    if (typeIn(handler, slot) == null) return { slots: [{ drawer: drawer, handler: handler, slot: slot }] }
  }
  return { slots: [], why: 'every slot of the drawer holds another item' }
}

function takeFn(group) {
  group.fn('take', fn => fn
    .summary('Take items of one kind out of a drawer, or out of the drawers a storage controller links, into your inventory.')
    .param('drawer', 'pos', DRAWER)
    .param('item', 'item', 'The item to take.')
    .option('amount', 'string', AMOUNT, 'take one stack')
    .option('count', 'integer', 'Exactly how many to take, instead of amount.', 'use amount')
    .returns('integer')
    .pending()
    .example('fs.drawers.take({x = 12, y = 64, z = -30}, "minecraft:iron_ingot")')
    .example('fs.drawers.take({x = 12, y = 64, z = -30}, "minecraft:iron_ingot", {amount = "one"})')
    .example('fs.drawers.take({x = 12, y = 64, z = -30}, "minecraft:cobblestone", {amount = "all"})')
    .note('Takes only what fits in your inventory; the rest stays in the drawer. Walk within reach first.')
    .run(call => call.useBlock(call.arg('drawer'), (her, pos) => {
      reach(her, pos)
      let item = call.arg('item')
      let id = idOf(item)
      let want = wanted(call, item, 'stack')
      let storage = storageAt(her.serverLevel(), pos)
      let slots = slotsOf(storage, id).filter(s => amountIn(s.drawer.tile, s.handler, s.slot) > 0)
      if (slots.length === 0) {
        throw new ApiError(ErrorKind.NOT_FOUND, 'there is no ' + id + ' in ' + storage.name,
          'fs.drawers.contents(' + literal(pos) + ')')
      }
      let moved = 0
      let full = false
      for (let s of slots) {
        let result = takeFrom(her, s.handler, s.slot, want - moved)
        moved += result.moved
        full = result.full
        if (full || moved >= want) break
      }
      if (moved === 0 && full) {
        throw new ApiError(ErrorKind.FAILED, 'your inventory has no room for ' + id, null)
      }
      call.report('took ' + moved + ' ' + id + ' from ' + storage.name + '; you carry '
        + her.getInventory().countItem(item) + ', ' + count(stored(storage, id)) + ' left there'
        + (full ? '; your inventory is full' : ''))
      return moved
    })))
}

function putFn(group) {
  group.fn('put', fn => fn
    .summary('Put items of one kind from your inventory into a drawer, or into the drawers a storage controller links.')
    .param('drawer', 'pos', DRAWER)
    .param('item', 'item', 'The item to put in.')
    .option('amount', 'string', AMOUNT, 'put in every one you carry')
    .option('count', 'integer', 'Exactly how many to put in, instead of amount.', 'use amount')
    .returns('integer')
    .pending()
    .example('fs.drawers.put({x = 12, y = 64, z = -30}, "minecraft:cobblestone")')
    .example('fs.drawers.put({x = 12, y = 64, z = -30}, "minecraft:diamond", {amount = "one"})')
    .example('fs.drawers.put({x = 12, y = 64, z = -30}, "minecraft:oak_log", {amount = "stack"})')
    .note('A drawer already set to the item takes it first; an empty drawer takes it into its first free slot. '
      + 'A controller only fills drawers already set to the item. Only your 36 main slots are emptied, '
      + 'not what you wear or hold in your off hand. Walk within reach first.')
    .run(call => call.useBlock(call.arg('drawer'), (her, pos) => {
      reach(her, pos)
      let level = her.serverLevel()
      let item = call.arg('item')
      let id = idOf(item)
      let want = wanted(call, item, 'all')
      let storage = storageAt(level, pos)
      let inventory = her.getInventory()
      let main = inventory.items
      let sample = null
      for (let i = 0; i < main.size() && sample == null; i++) {
        if (!main.get(i).isEmpty() && idOf(main.get(i).getItem()) === id) sample = main.get(i)
      }
      if (sample == null) {
        throw new ApiError(ErrorKind.NO_MATERIAL, 'you carry no ' + id + ' in your inventory', null)
      }
      let targets = targetsFor(level, storage, id, sample)
      if (targets.slots.length === 0) {
        throw new ApiError(ErrorKind.FAILED, storage.name + ' cannot take ' + id + ': ' + targets.why, null)
      }
      let creative = storage.drawers.some(d => d.tile.isCreative())
      let before = creative ? 0 : stored(storage, id)
      let moved = 0
      for (let i = 0; i < main.size() && moved < want; i++) {
        let stack = main.get(i)
        if (stack.isEmpty() || idOf(stack.getItem()) !== id) continue
        for (let t of targets.slots) {
          let offer = Math.min(stack.getCount(), want - moved)
          if (offer <= 0) break
          let accepted = offer - t.handler.insertItem(t.slot, stack.copyWithCount(offer), false).getCount()
          if (accepted > 0) {
            stack.shrink(accepted)
            moved += accepted
          }
          if (stack.isEmpty()) break
        }
      }
      inventory.setChanged()
      if (moved === 0) {
        // A compacting drawer set up for this call goes back to empty, as it was.
        if (targets.setUp != null) targets.setUp.reset()
        throw new ApiError(ErrorKind.FAILED, 'there is no room for ' + id + ' in ' + storage.name, null)
      }
      let voided = creative ? 0 : moved - (stored(storage, id) - before)
      call.report('put ' + moved + ' ' + id + ' into ' + storage.name + '; you carry '
        + inventory.countItem(item) + ', ' + count(stored(storage, id)) + ' there now'
        + (voided > 0 ? '; ' + voided + ' did not fit and the void upgrade destroyed them' : ''))
      return moved
    })))
}

function emptyFn(group) {
  group.fn('empty', fn => fn
    .summary('Take everything out of a drawer, or out of the drawers a storage controller links, into your inventory.')
    .param('drawer', 'pos', DRAWER)
    .returns('integer')
    .pending()
    .example('fs.drawers.empty({x = 12, y = 64, z = -30})')
    .note('Stops when your inventory is full; the rest stays. A compacting drawer comes out in its largest form '
      + 'first (blocks before ingots before nuggets). Walk within reach first.')
    .run(call => call.useBlock(call.arg('drawer'), (her, pos) => {
      reach(her, pos)
      let storage = storageAt(her.serverLevel(), pos)
      let took = {}
      let order = []
      let total = 0
      let full = false
      for (let drawer of storage.drawers) {
        let handler = drawer.tile.getStorage()
        let slots = []
        for (let slot = 0; slot < realSlots(drawer.tile); slot++) slots.push(slot)
        if (handler instanceof CompactingInventoryHandler) {
          slots.sort((a, b) => handler.getResultList().get(b).getNeeded() - handler.getResultList().get(a).getNeeded())
        }
        for (let slot of slots) {
          let type = typeIn(handler, slot)
          if (type == null || amountIn(drawer.tile, handler, slot) === 0) continue
          let result = takeFrom(her, handler, slot, Number.MAX_SAFE_INTEGER)
          if (result.moved > 0) {
            let id = idOf(type)
            if (took[id] == null) {
              took[id] = 0
              order.push(id)
            }
            took[id] += result.moved
            total += result.moved
          }
          if (result.full) {
            full = true
            break
          }
        }
        if (full) break
      }
      if (total === 0 && full) throw new ApiError(ErrorKind.FAILED, 'your inventory is full', null)
      call.report((total === 0 ? 'there was nothing to take from ' : 'took '
        + order.map(id => took[id] + ' ' + id).join(', ') + ' from ') + storage.name
        + (full ? '; your inventory is full, the rest stays' : ''))
      return total
    })))
}

NumenEvents.register(event => {
  event.plugin('fs', plugin => {
    plugin.group('drawers', 'Functional Storage drawers and storage controllers: list, find, take out and put in '
      + 'without aiming at a slot.', group => {

      group.fn('nearby', fn => fn
        .summary('Every drawer and storage controller near you, nearest first, one line each with what it holds.')
        .option('radius', 'integer', 'How far to look, 1 to ' + MAX_RADIUS + ' blocks.', 'look ' + DEFAULT_RADIUS + ' blocks around you')
        .option('filter', 'string', 'Only drawers holding an item whose id contains this text, e.g. "iron".', 'list every drawer')
        .returns('string[]')
        .example('fs.drawers.nearby()')
        .example('fs.drawers.nearby({radius = 32, filter = "ingot"})')
        .note('Lines read "x,y,z <block> [flags]: <item> x<count>/<capacity>, ...". A compacting drawer joins its '
          + 'forms with " = ": they are one stock, not several. Only loaded chunks are searched.')
        .run(call => {
          let her = bodyOf(call)
          let level = her.serverLevel()
          let filter = call.option('filter') == null ? null : String(call.option('filter')).toLowerCase()
          let lines = []
          for (let near of tilesNear(level, her.getX(), her.getY(), her.getZ(), radiusOf(call))) {
            if (isController(near.tile)) {
              if (filter == null) lines.push(describeController(level, near))
              continue
            }
            if (!isDrawer(near.tile)) continue
            let handler = near.tile.getStorage()
            if (filter != null) {
              let holds = false
              for (let slot = 0; slot < realSlots(near.tile) && !holds; slot++) {
                let type = typeIn(handler, slot)
                holds = type != null && idOf(type).indexOf(filter) >= 0
              }
              if (!holds) continue
            }
            lines.push(describe(level, near))
          }
          return lines
        }))

      group.fn('find', fn => fn
        .summary('The drawers near you that hold an item, nearest first.')
        .param('item', 'item', 'The item to look for.')
        .option('radius', 'integer', 'How far to look, 1 to ' + MAX_RADIUS + ' blocks.', 'look ' + DEFAULT_RADIUS + ' blocks around you')
        .returns('pos[]')
        .example('local where = fs.drawers.find("minecraft:iron_ingot")[1]')
        .note('Only drawers that hold at least one; pass a position to fs.drawers.take after walking to it.')
        .run(call => {
          let her = bodyOf(call)
          let id = idOf(call.arg('item'))
          let found = []
          for (let near of tilesNear(her.serverLevel(), her.getX(), her.getY(), her.getZ(), radiusOf(call))) {
            if (!isDrawer(near.tile)) continue
            let storage = { drawers: [near] }
            if (slotsOf(storage, id).some(s => amountIn(near.tile, s.handler, s.slot) > 0)) found.push(near.pos)
          }
          return found
        }))

      group.fn('contents', fn => fn
        .summary('What one drawer holds, or each drawer a storage controller links, one line per drawer.')
        .param('drawer', 'pos', DRAWER)
        .returns('string[]')
        .example('fs.drawers.contents({x = 12, y = 64, z = -30})')
        .note('Lines read as in fs.drawers.nearby. Reads from any distance; taking and putting need you within reach.')
        .run(call => {
          let level = bodyOf(call).serverLevel()
          let storage = storageAt(level, call.arg('drawer'))
          return storage.drawers.map(d => describe(level, d))
        }))

      takeFn(group)
      putFn(group)
      emptyFn(group)
    })
  })
})
})()
