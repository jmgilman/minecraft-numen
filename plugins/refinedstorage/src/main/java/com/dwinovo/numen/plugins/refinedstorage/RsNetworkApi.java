package com.dwinovo.numen.plugins.refinedstorage;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import com.refinedmods.refinedstorage.api.network.security.SecurityNetworkComponent;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.common.security.BuiltinPermission;
import com.refinedmods.refinedstorage.common.security.PlayerSecurityActor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code rs.network}:经她够得着的一个网络方块(网格、控制器、线缆……)读、取、存整个 Refined Storage 网络,就像玩家站在网格前一样。
 *
 * <p>读是只读;取经权限层的 {@code take}(与从箱子里拿同一条规矩),再过网络自己的安全管理器(按她的身份,不按主人的);存只过安全管理器。
 * 取出来的放进她的背包,背包装不下的那部分放回网络,不掉在地上。每一次都当场做完,返回动了多少、她现在背着多少。
 */
public final class RsNetworkApi {

    /** 这个联动在她的 API 里的名字空间。 */
    static final String NAMESPACE = "rs";

    private RsNetworkApi() {}

    static void install(NumenApi numen) {
        numen.api("network", "Refined Storage networks: list, count, take out and put in through a network block "
                + "you can reach.", RsNetworkApi.class);
    }

    // ---- items ----

    /** 读哪个网络、只看哪些。 */
    public record Items(@Doc("A block of the network within your reach: a grid, controller, cable, disk drive, ...")
                        BlockPos node,
                        @Doc("Only items whose id contains this text, e.g. \"iron\".") @Omitted("list every item")
                        Optional<String> filter) {}

    /** 网络里的一种物品。 */
    public record Stored(@Doc("The item id.") String item,
                         @Doc("How many the network holds, all variants (enchantments, damage, ...) counted together.")
                         long count) {}

    @Fn("Everything the network stores, one entry per item, most first.")
    @Example("rs.network.items({x = -155, y = 99, z = 79})")
    @Example("for _, s in ipairs(rs.network.items({x = -155, y = 99, z = 79}, {filter = \"ingot\"})) do\n"
            + "  print(s.item, s.count)\nend")
    @Note("Instant and read-only. Use it instead of opening a grid: a grid's window shows you nothing.")
    @SeeAlso({"rs.network.count", "rs.network.extract"})
    public static List<Stored> items(ServerCall call, Items args) {
        StorageNetworkComponent storage = open(call, args.node()).getComponent(StorageNetworkComponent.class);
        String filter = args.filter().map(f -> f.toLowerCase(Locale.ROOT)).orElse("");
        Map<String, Long> byItem = new LinkedHashMap<>();
        for (ResourceAmount stored : storage.getAll()) {
            if (stored.resource() instanceof ItemResource item) {
                String id = idOf(item.toItemStack().getItem());
                if (id.contains(filter)) byItem.merge(id, stored.amount(), Long::sum);
            }
        }
        List<Stored> out = new ArrayList<>();
        byItem.forEach((id, count) -> out.add(new Stored(id, count)));
        out.sort(Comparator.comparingLong(Stored::count).reversed().thenComparing(Stored::item));
        return out;
    }

    // ---- count ----

    /** 数哪个网络里的哪一样。 */
    public record Count(@Doc("A block of the network within your reach.") BlockPos node,
                        @Doc("The item to count.") Item item) {}

    @Fn("How many of one item the network stores, all variants counted together.")
    @Example("rs.network.count({x = -155, y = 99, z = 79}, \"minecraft:iron_ingot\")")
    @Note("Instant and read-only.")
    @SeeAlso("rs.network.items")
    public static long count(ServerCall call, Count args) {
        return stored(open(call, args.node()).getComponent(StorageNetworkComponent.class), args.item());
    }

    // ---- extract ----

    /** 从哪个网络取哪一样、取几件。 */
    public record Take(@Doc("A block of the network within your reach.") BlockPos node,
                       @Doc("The item to take out.") Item item,
                       @Doc("How many to take.") @Omitted("take one stack")
                       Optional<Integer> count) {}

    /** 动了几件、现在背着几件、网络里还剩几件。 */
    public record Moved(@Doc("How many moved.") long moved,
                        @Doc("How many of the item you carry now.") int carrying,
                        @Doc("How many the network holds now.") long left) {}

    @Fn("Take items of one kind out of the network into your inventory.")
    @Example("rs.network.extract({x = -155, y = 99, z = 79}, \"minecraft:iron_ingot\", {count = 32})")
    @Note("Takes what is there, up to count, and only what fits in your inventory; the rest stays in the network.")
    @Note("Goes through your owner's permission rules like taking from a chest, and through the network's security "
            + "manager as yourself.")
    @SeeAlso({"rs.network.insert", "rs.network.count"})
    public static Pending<Moved> extract(ServerCall call, Take args) {
        Network network = open(call, args.node());
        guard(network, call.her(), BuiltinPermission.EXTRACT, "take items out of");
        BlockState state = call.her().level().getBlockState(args.node());
        return call.authorize(com.dwinovo.numen.permission.Action.take(args.node(), state, args.item()))
                .then(allowed -> {
                    // 主人答复回来的时候她可能走开了、网络可能断了:再认一次
                    Network still = open(call, args.node());
                    guard(still, call.her(), BuiltinPermission.EXTRACT, "take items out of");
                    return take(call.her(), still.getComponent(StorageNetworkComponent.class), args.item(),
                            args.count().orElse(args.item().getDefaultMaxStackSize()));
                });
    }

    private static Moved take(NumenPlayer her, StorageNetworkComponent storage, Item item, int want) {
        PlayerActor actor = new PlayerActor(her);
        long moved = 0;
        for (ResourceAmount stored : List.copyOf(storage.getAll())) {
            if (moved >= want) break;
            if (!(stored.resource() instanceof ItemResource resource) || resource.toItemStack().getItem() != item) {
                continue;
            }
            long remaining = Math.min(want - moved, stored.amount());
            while (remaining > 0) {
                int chunk = (int) Math.min(remaining, item.getDefaultMaxStackSize());
                long got = storage.extract(resource, chunk, com.refinedmods.refinedstorage.api.core.Action.EXECUTE,
                        actor);
                if (got == 0) break;
                ItemStack stack = resource.toItemStack(got);
                her.getInventory().add(stack);
                long fitted = got - stack.getCount();
                moved += fitted;
                remaining -= fitted;
                if (!stack.isEmpty()) {
                    // 背包满了:没装下的原样放回网络,不掉在脚下
                    storage.insert(resource, stack.getCount(), com.refinedmods.refinedstorage.api.core.Action.EXECUTE,
                            actor);
                    return new Moved(moved, her.getInventory().countItem(item), stored(storage, item));
                }
            }
        }
        return new Moved(moved, her.getInventory().countItem(item), stored(storage, item));
    }

    // ---- insert ----

    /** 往哪个网络存哪一样、存几件。 */
    public record Put(@Doc("A block of the network within your reach.") BlockPos node,
                      @Doc("The item to put in.") Item item,
                      @Doc("How many to put in.") @Omitted("put in every one you carry")
                      Optional<Integer> count) {}

    @Fn("Put items of one kind from your inventory into the network.")
    @Example("rs.network.insert({x = -155, y = 99, z = 79}, \"minecraft:cobblestone\")")
    @Example("rs.network.insert({x = -155, y = 99, z = 79}, \"minecraft:oak_log\", {count = 16})")
    @Note("Puts in what the network has room for; the rest stays in your inventory.")
    @Note("Goes through the network's security manager as yourself.")
    @SeeAlso({"rs.network.extract", "rs.network.items"})
    public static Moved insert(ServerCall call, Put args) {
        NumenPlayer her = call.her();
        Network network = open(call, args.node());
        guard(network, her, BuiltinPermission.INSERT, "put items into");
        StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        PlayerActor actor = new PlayerActor(her);
        Item item = args.item();
        long want = args.count().orElse(Integer.MAX_VALUE);
        long moved = 0;
        var inventory = her.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && moved < want; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.getItem() != item) continue;
            int offer = (int) Math.min(stack.getCount(), want - moved);
            long accepted = storage.insert(ItemResource.ofItemStack(stack), offer,
                    com.refinedmods.refinedstorage.api.core.Action.EXECUTE, actor);
            if (accepted > 0) {
                stack.shrink((int) accepted);
                inventory.setChanged();
                moved += accepted;
            }
            if (accepted < offer) break;   // 网络满了
        }
        return new Moved(moved, inventory.countItem(item), stored(storage, item));
    }

    // ---- 共用 ----

    /**
     * 她够得着的那一格所在的、有电的网络。够不着是 {@link ErrorKind#OUT_OF_REACH},下一步是走过去;不是网络方块、没连上网络是
     * {@link ErrorKind#NOT_FOUND};没电是 {@link ErrorKind#FAILED}——玩家站在没电的网格前也什么都看不到。
     */
    private static Network open(ServerCall call, BlockPos node) {
        NumenPlayer her = call.her();
        String at = node.getX() + "," + node.getY() + "," + node.getZ();
        if (!her.isWithinBlockInteractionRange(node, 0.0)) {
            throw new ApiError(ErrorKind.OUT_OF_REACH, "the network block at " + at
                    + " is out of reach from where you stand; walk to it first, then call this again",
                    Call.of("numen.move.to", node, Map.of("arrive", "use")));
        }
        NetworkNodeContainerProvider provider = her.level().getCapability(
                RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(), node, null);
        if (provider == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "the block at " + at + " (" + idOf(her.level().getBlockState(node)
                    .getBlock().asItem()) + ") is not part of a Refined Storage network", null);
        }
        for (InWorldNetworkNodeContainer container : provider.getContainers()) {
            NetworkNode networkNode = container.getNode();
            Network network = networkNode == null ? null : networkNode.getNetwork();
            if (network == null) continue;
            if (network.getComponent(EnergyNetworkComponent.class).getStored() <= 0) {
                throw new ApiError(ErrorKind.FAILED, "the network at " + at + " has no power — its controller needs "
                        + "energy before anything in it can be reached", null);
            }
            return network;
        }
        throw new ApiError(ErrorKind.NOT_FOUND, "the block at " + at + " is not connected to a network yet", null);
    }

    /** 网络的安全管理器按她自己的身份放不放行。 */
    private static void guard(Network network, NumenPlayer her, BuiltinPermission permission, String what) {
        if (!network.getComponent(SecurityNetworkComponent.class)
                .isAllowed(permission, new PlayerSecurityActor(her.getUUID()))) {
            throw new ApiError(ErrorKind.DENIED, "the network's security manager does not let you " + what
                    + " this network; your owner can allow you in its security card settings", null);
        }
    }

    private static long stored(StorageNetworkComponent storage, Item item) {
        long total = 0;
        for (ResourceAmount stored : storage.getAll()) {
            if (stored.resource() instanceof ItemResource resource && resource.toItemStack().getItem() == item) {
                total += stored.amount();
            }
        }
        return total;
    }

    private static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
