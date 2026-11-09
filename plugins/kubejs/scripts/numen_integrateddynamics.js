// Numen ↔ Integrated Dynamics: a companion reads, takes from and puts into an Integrated Dynamics item network, and
// asks its crafting interfaces to craft, through any block of the network within reach (cable, interface, terminal).
//
// The network's items live in the inventories its Item Interfaces (Integrated Tunnels) face; a Storage Terminal shows
// them only on a player's screen. These functions read and move them through the network's item channel, as the
// terminal does on the server, and plan and start crafting jobs through Integrated Crafting as the terminal's crafting
// tab does. Each call goes through Numen's permission layer as `use_block` on the named block.
//
// Install: kubejs/server_scripts/ on the server, then /reload. Needs Numen with its KubeJS plugin, Integrated Dynamics
// and Integrated Tunnels; the crafting functions also need Integrated Crafting.

// KubeJS server scripts share one global scope: everything here lives inside this function so nothing collides with
// the pack's other scripts. Inside functions every binding is `let`: KubeJS's Rhino keeps the first value of a `const`
// declared in a loop body on every later pass.
;(function () {
if (!Platform.isLoaded('integrateddynamics') || !Platform.isLoaded('integratedtunnels')) return

const NetworkHelpers = Java.loadClass('org.cyclops.integrateddynamics.core.helper.NetworkHelpers')
const ItemNetworkCapability = Java.loadClass('org.cyclops.integratedtunnels.Capabilities$ItemNetwork')
const IngredientComponents = Java.loadClass('org.cyclops.commoncapabilities.IngredientComponents')
const ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack')
const BuiltInRegistries = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries')
const ApiError = Java.loadClass('com.dwinovo.numen.script.ApiError')
const ErrorKind = Java.loadClass('com.dwinovo.numen.script.ErrorKind')

let CRAFTING = Platform.isLoaded('integratedcrafting')
const CraftingHelpers = CRAFTING ? Java.loadClass('org.cyclops.integratedcrafting.core.CraftingHelpers') : null
const CraftingJobDependencyGraph = CRAFTING
  ? Java.loadClass('org.cyclops.integratedcrafting.api.crafting.CraftingJobDependencyGraph') : null
const FailedCraftingRecipeException = CRAFTING
  ? Java.loadClass('org.cyclops.integratedcrafting.api.crafting.FailedCraftingRecipeException') : null
const UnknownCraftingRecipeException = CRAFTING
  ? Java.loadClass('org.cyclops.integratedcrafting.api.crafting.UnknownCraftingRecipeException') : null
const RecursiveCraftingRecipeException = CRAFTING
  ? Java.loadClass('org.cyclops.integratedcrafting.api.crafting.RecursiveCraftingRecipeException') : null

let ITEMS = IngredientComponents.ITEMSTACK
// Every channel at once, as the terminal's "all channels" view.
let ALL_CHANNELS = -1
// The network moves at most 64 items per call on a channel.
let RATE = 64
let NODE = 'A block of the Integrated Dynamics network within your reach: a cable, an interface, a terminal, ...'

function at(pos) {
  return pos.x + ',' + pos.y + ',' + pos.z
}

function literal(pos) {
  return '{x = ' + pos.x + ', y = ' + pos.y + ', z = ' + pos.z + '}'
}

// Rhino's == on two Java objects compares its wrappers, not the objects: compare items by registry id.
function idOf(item) {
  return String(BuiltInRegistries.ITEM.getKey(item))
}

// The network the block at pos belongs to, checked the way a player at a terminal would reach it.
function networkAt(her, pos) {
  if (!her.canInteractWithBlock(pos, 0.0)) {
    throw new ApiError(ErrorKind.OUT_OF_REACH, 'the network block at ' + at(pos)
      + ' is out of reach from where you stand; walk to it first, then call this again',
      'numen.move.walk(' + literal(pos) + ', 2)')
  }
  let network = NetworkHelpers.getNetwork(her.serverLevel(), pos, null).orElse(null)
  if (network == null) {
    throw new ApiError(ErrorKind.NOT_FOUND, 'the block at ' + at(pos)
      + ' is not part of an Integrated Dynamics network (a cable, or a part on one)', null)
  }
  return network
}

function itemsOf(network, pos) {
  let items = network.getCapability(ItemNetworkCapability.NETWORK).orElse(null)
  if (items == null || !items.hasPositions()) {
    throw new ApiError(ErrorKind.NOT_FOUND, 'the network at ' + at(pos) + ' stores no items: it needs an Item '
      + 'Interface (Integrated Tunnels) on a cable, facing a chest or other inventory', null)
  }
  return items
}

function craftingOf(network, pos) {
  let crafting = CRAFTING ? CraftingHelpers.getCraftingNetwork(network).orElse(null) : null
  if (crafting == null) {
    throw new ApiError(ErrorKind.NOT_FOUND, 'the network at ' + at(pos) + ' cannot craft: it needs a Crafting '
      + 'Interface (Integrated Crafting) holding recipe cards, facing a crafting table or machine', null)
  }
  return crafting
}

// The inventories the network's interfaces face, best priority first, each with its storage and filter. The
// network's own index of them lags a moment behind changes, so listing and counting read the inventories directly.
function storagesOf(items) {
  let found = []
  let positions = items.getPositions(ALL_CHANNELS).iterator()
  while (positions.hasNext()) {
    let pos = positions.next()
    if (items.isPositionDisabled(pos)) continue
    let storage = items.getPositionedStorage(pos)
    if (storage != null) found.push({ pos: pos, storage: storage, filter: items.getPositionedStorageFilter(pos) })
  }
  return found
}

// Every stack the network stores, as the terminal shows them (filtered interfaces hide what they filter out).
function stacksOf(items) {
  let stacks = []
  for (let s of storagesOf(items)) {
    let it = s.storage.iterator()
    while (it.hasNext()) {
      let stack = it.next()
      if (stack.isEmpty() || (s.filter != null && !s.filter.testView(stack))) continue
      stacks.push(stack)
    }
  }
  return stacks
}

function stored(items, item) {
  let id = idOf(item)
  let total = 0
  for (let stack of stacksOf(items)) if (idOf(stack.getItem()) === id) total += stack.getCount()
  return total
}

// Each variant of one item the network holds (enchantments, damage, ... make separate stacks).
function variantsOf(items, item) {
  let id = idOf(item)
  let variants = []
  for (let stack of stacksOf(items)) {
    if (idOf(stack.getItem()) !== id) continue
    if (!variants.some(v => ItemStack.isSameItemSameComponents(v, stack))) variants.push(stack.copyWithCount(1))
  }
  return variants
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

// Take up to n of variant out of the network: through its item channel as the terminal does, or, while the channel's
// index has not yet caught up with a change, straight from the inventories that hold it.
function pull(items, variant, n) {
  let cond = ITEMS.getMatcher().getExactMatchNoQuantityCondition()
  let got = items.getChannel(ALL_CHANNELS).extract(variant.copyWithCount(n), cond, false)
  if (!got.isEmpty()) return got
  for (let s of storagesOf(items)) {
    if (s.filter != null && !s.filter.testExtraction(variant)) continue
    got = s.storage.extract(variant.copyWithCount(n), cond, false)
    if (!got.isEmpty()) return got
  }
  return got
}

function extractFn(group) {
  group.fn('extract', fn => fn
    .summary('Take items of one kind out of the Integrated Dynamics network into your inventory.')
    .param('node', 'pos', NODE)
    .param('item', 'item', 'The item to take out.')
    .option('count', 'integer', 'How many to take.', 'take one stack')
    .returns('integer')
    .pending()
    .example('id.network.extract({x = -389, y = 69, z = -869}, "minecraft:iron_ingot", {count = 32})')
    .note('Takes what is there, up to count, and only what fits in your inventory; the rest stays in the network.')
    .run(call => call.useBlock(call.arg('node'), (her, pos) => {
      let item = call.arg('item')
      let want = call.option('count') == null ? item.getDefaultMaxStackSize() : Number(call.option('count'))
      let items = itemsOf(networkAt(her, pos), pos)
      let inventory = her.getInventory()
      let moved = 0
      let full = false
      for (let variant of variantsOf(items, item)) {
        while (moved < want) {
          let room = roomFor(inventory, variant)
          if (room <= 0) {
            full = true
            break
          }
          let got = pull(items, variant, Math.min(want - moved, variant.getMaxStackSize(), room, RATE))
          if (got.isEmpty()) break
          let n = got.getCount()
          inventory.add(got)
          if (!got.isEmpty()) {
            // The room was measured, so this does not happen; if it does, the rest goes back rather than being lost.
            n -= got.getCount()
            let back = items.getChannel(ALL_CHANNELS).insert(got, false)
            if (!back.isEmpty()) her.drop(back, false)
          }
          moved += n
        }
        if (moved >= want || full) break
      }
      call.report('took ' + moved + ' ' + idOf(item) + ' out of the network; you carry '
        + inventory.countItem(item) + ', the network holds ' + stored(items, item)
        + (full ? '; your inventory is full' : ''))
      return moved
    })))
}

function insertFn(group) {
  group.fn('insert', fn => fn
    .summary('Put items of one kind from your inventory into the Integrated Dynamics network.')
    .param('node', 'pos', NODE)
    .param('item', 'item', 'The item to put in.')
    .option('count', 'integer', 'How many to put in.', 'put in every one you carry')
    .returns('integer')
    .pending()
    .example('id.network.insert({x = -389, y = 69, z = -869}, "minecraft:cobblestone")')
    .example('id.network.insert({x = -389, y = 69, z = -869}, "minecraft:oak_log", {count = 16})')
    .note('Puts in what the network has room for; the rest stays in your inventory. Only your 36 main slots are '
      + 'emptied, not what you wear or hold in your off hand.')
    .run(call => call.useBlock(call.arg('node'), (her, pos) => {
      let item = call.arg('item')
      let want = call.option('count') == null ? Number.MAX_SAFE_INTEGER : Number(call.option('count'))
      let items = itemsOf(networkAt(her, pos), pos)
      let channel = items.getChannel(ALL_CHANNELS)
      let inventory = her.getInventory()
      let main = inventory.items
      let moved = 0
      let full = false
      for (let slot = 0; slot < main.size() && moved < want && !full; slot++) {
        let stack = main.get(slot)
        if (stack.isEmpty() || idOf(stack.getItem()) !== idOf(item)) continue
        while (!stack.isEmpty() && moved < want) {
          let offer = Math.min(stack.getCount(), want - moved, RATE)
          let accepted = offer - channel.insert(stack.copyWithCount(offer), false).getCount()
          if (accepted <= 0) {
            full = true
            break
          }
          stack.shrink(accepted)
          moved += accepted
        }
      }
      inventory.setChanged()
      call.report('put ' + moved + ' ' + idOf(item) + ' into the network; you carry ' + inventory.countItem(item)
        + ', the network holds ' + stored(items, item) + (full ? '; the network has no room for more' : ''))
      return moved
    })))
}

// What one recipe makes: its first item output and how many one craft gives.
function outputOf(recipe) {
  let outputs = recipe.getOutput().getInstances(ITEMS)
  return outputs.isEmpty() ? null : outputs.get(0)
}

// The recipes on the network's crafting interfaces, each with the channel it is crafted on.
function recipesOf(crafting) {
  let found = []
  let channels = crafting.getChannels()
  for (let i = 0; i < channels.length; i++) {
    let channel = channels[i]
    let recipes = crafting.getRecipeIndex(channel).getRecipes().iterator()
    while (recipes.hasNext()) {
      let recipe = recipes.next()
      let output = outputOf(recipe)
      if (output != null) found.push({ channel: channel, recipe: recipe, output: output })
    }
  }
  return found
}

// "8 minecraft:oak_planks, 1 minecraft:stick": what a failed plan lacks, from Integrated Crafting's exception.
function missingOf(error) {
  let parts = []
  let missing = error.getMissingChildRecipes()
  for (let i = 0; i < missing.size(); i++) {
    let child = missing.get(i)
    let prototype = child.getIngredient().getPrototype()
    let name = prototype instanceof ItemStack ? idOf(prototype.getItem()) : String(prototype)
    parts.push(child.getQuantityMissing() + ' ' + name)
  }
  return parts.length > 0 ? parts.join(', ') : error.getQuantityMissing() + ' of its inputs'
}

// The Java exception behind a caught error, however Rhino wrapped it.
function javaError(e) {
  return e != null && e.javaException != null ? e.javaException : e
}

function statusOf(crafting, job) {
  let face = crafting.getCraftingJobInterface(job.getChannel(), job.getId())
  return face == null ? 'UNKNOWN' : String(face.getCraftingJobStatus(crafting, job.getChannel(), job.getId()))
}

function craftingFns(group) {
  group.fn('craftable', fn => fn
    .summary('What the network\'s crafting interfaces can craft, one line per item ("<id> x<made per craft>").')
    .param('node', 'pos', NODE)
    .option('filter', 'string', 'Only items whose id contains this text, e.g. "plank".', 'list every recipe')
    .returns('string[]')
    .pending()
    .example('id.network.craftable({x = -389, y = 69, z = -869})')
    .note('Only recipes someone put on a Crafting Interface as a recipe card; there is no crafting otherwise.')
    .run(call => call.useBlock(call.arg('node'), (her, pos) => {
      let crafting = craftingOf(networkAt(her, pos), pos)
      let filter = call.option('filter') == null ? '' : String(call.option('filter')).toLowerCase()
      let lines = []
      for (let r of recipesOf(crafting)) {
        let line = idOf(r.output.getItem()) + ' x' + r.output.getCount()
        if (line.indexOf(filter) >= 0 && lines.indexOf(line) < 0) lines.push(line)
      }
      return lines.sort()
    })))

  group.fn('craft', fn => fn
    .summary('Start an auto-crafting job: the network crafts an item from what it stores, crafting missing '
      + 'ingredients too when it has recipes for them. The result goes into the network.')
    .param('node', 'pos', NODE)
    .param('item', 'item', 'The item to craft.')
    .option('count', 'integer', 'How many to make; rounded up to whole crafts.', 'one craft')
    .returns('integer')
    .pending()
    .example('local job = id.network.craft({x = -389, y = 69, z = -869}, "minecraft:chest", {count = 4})')
    .note('Returns the job id; follow it with id.network.jobs and take the result with id.network.extract once it is '
      + 'done. Fails with what is missing when the network lacks ingredients and has no recipe for them.')
    .run(call => call.useBlock(call.arg('node'), (her, pos) => {
      let network = networkAt(her, pos)
      let crafting = craftingOf(network, pos)
      let item = call.arg('item')
      let id = idOf(item)
      let candidates = recipesOf(crafting).filter(r => idOf(r.output.getItem()) === id)
      if (candidates.length === 0) {
        throw new ApiError(ErrorKind.NOT_FOUND, 'no crafting interface on the network has a recipe for ' + id,
          'id.network.craftable(' + literal(pos) + ')')
      }
      let r = candidates[0]
      let wanted = call.option('count') == null ? r.output.getCount() : Number(call.option('count'))
      if (!(wanted >= 1)) throw new ApiError(ErrorKind.BAD_ARGUMENT, 'count must be at least 1', null)
      let crafts = Math.ceil(wanted / r.output.getCount())
      let graph = new CraftingJobDependencyGraph()
      let job
      try {
        job = CraftingHelpers.calculateCraftingJobs(network, r.channel, r.recipe, crafts, true,
          CraftingHelpers.getGlobalCraftingJobIdentifier(), graph, true)
      } catch (e) {
        let error = javaError(e)
        if (error instanceof FailedCraftingRecipeException || error instanceof UnknownCraftingRecipeException) {
          throw new ApiError(ErrorKind.NO_MATERIAL, 'the network cannot craft ' + (crafts * r.output.getCount())
            + ' ' + id + ': it is missing ' + missingOf(error), null)
        }
        if (error instanceof RecursiveCraftingRecipeException) {
          throw new ApiError(ErrorKind.FAILED, 'the recipes for ' + id + ' go in a circle', null)
        }
        throw e
      }
      try {
        CraftingHelpers.scheduleCraftingJobs(crafting, CraftingHelpers.getNetworkStorageGetter(network, r.channel, false),
          graph, true, null, false)
      } catch (e) {
        throw new ApiError(ErrorKind.FAILED, 'no crafting interface for ' + id + ' can take another job right now; '
          + 'try again when the running ones finish', 'id.network.jobs(' + literal(pos) + ')')
      }
      call.report('started crafting job #' + job.getId() + ': ' + (crafts * r.output.getCount()) + ' ' + id
        + ' in ' + crafts + ' crafts' + (graph.getCraftingJobs().size() > 1
          ? ', with ' + (graph.getCraftingJobs().size() - 1) + ' jobs for its ingredients' : ''))
      return job.getId()
    })))

  group.fn('jobs', fn => fn
    .summary('The network\'s running crafting jobs, one line each ("#<id> <item> x<crafts left>/<crafts> <status>").')
    .param('node', 'pos', NODE)
    .returns('string[]')
    .pending()
    .example('id.network.jobs({x = -389, y = 69, z = -869})')
    .note('A job leaves the list when it is done. PENDING_INGREDIENTS means it waits for items to arrive; '
      + 'INVALID_INPUTS that its machine refused them; PENDING_OUTPUT_STORAGE that the network has no room for the result.')
    .run(call => call.useBlock(call.arg('node'), (her, pos) => {
      let crafting = craftingOf(networkAt(her, pos), pos)
      let lines = []
      let jobs = crafting.getCraftingJobs(ALL_CHANNELS)
      while (jobs.hasNext()) {
        let job = jobs.next()
        let output = outputOf(job.getRecipe())
        lines.push('#' + job.getId() + ' ' + (output == null ? '?' : idOf(output.getItem())) + ' x' + job.getAmount()
          + '/' + job.getAmountTotal() + ' ' + statusOf(crafting, job)
          + (job.getDependentCraftingJobs().isEmpty() ? '' : ' (for another job)'))
      }
      return lines
    })))

  group.fn('cancel', fn => fn
    .summary('Cancel a crafting job, and the jobs crafting its ingredients.')
    .param('node', 'pos', NODE)
    .param('job', 'integer', 'The job id, as id.network.craft returned it or id.network.jobs lists it.')
    .returns('boolean')
    .pending()
    .example('id.network.cancel({x = -389, y = 69, z = -869}, 12)')
    .run(call => call.useBlock(call.arg('node'), (her, pos) => {
      let crafting = craftingOf(networkAt(her, pos), pos)
      let id = Number(call.arg('job'))
      let job = crafting.getCraftingJob(ALL_CHANNELS, id)
      if (job == null) {
        throw new ApiError(ErrorKind.NOT_FOUND, 'the network has no crafting job #' + id,
          'id.network.jobs(' + literal(pos) + ')')
      }
      let cancelled = crafting.cancelCraftingJob(job.getChannel(), id)
      call.report(cancelled ? 'cancelled crafting job #' + id : 'crafting job #' + id + ' could not be cancelled')
      return cancelled
    })))
}

NumenEvents.register(event => {
  event.plugin('id', plugin => {
    plugin.group('network', 'Integrated Dynamics item networks: list, count, take out, put in and auto-craft through '
      + 'a block of the network you can reach.', group => {

      group.fn('items', fn => fn
        .summary('Everything the network stores, one line per item ("<id> x<count>"), most first.')
        .param('node', 'pos', NODE)
        .option('filter', 'string', 'Only items whose id contains this text, e.g. "iron".', 'list every item')
        .returns('string[]')
        .pending()
        .example('id.network.items({x = -389, y = 69, z = -869})')
        .example('id.network.items({x = -389, y = 69, z = -869}, {filter = "ingot"})')
        .note('Use it instead of opening a Storage Terminal: a terminal\'s window shows you nothing.')
        .run(call => call.useBlock(call.arg('node'), (her, pos) => {
          let filter = call.option('filter') == null ? '' : String(call.option('filter')).toLowerCase()
          let totals = {}
          for (let stack of stacksOf(itemsOf(networkAt(her, pos), pos))) {
            let id = idOf(stack.getItem())
            if (id.indexOf(filter) < 0) continue
            totals[id] = (totals[id] || 0) + stack.getCount()
          }
          return Object.keys(totals)
            .sort((a, b) => totals[b] - totals[a] || (a < b ? -1 : 1))
            .map(id => id + ' x' + totals[id])
        })))

      group.fn('count', fn => fn
        .summary('How many of one item the network stores, all variants counted together.')
        .param('node', 'pos', NODE)
        .param('item', 'item', 'The item to count.')
        .returns('integer')
        .pending()
        .example('id.network.count({x = -389, y = 69, z = -869}, "minecraft:iron_ingot")')
        .run(call => call.useBlock(call.arg('node'), (her, pos) =>
          Math.min(stored(itemsOf(networkAt(her, pos), pos), call.arg('item')), 2147483647))))

      extractFn(group)
      insertFn(group)
      if (CRAFTING) craftingFns(group)
    })
  })
})
})()
