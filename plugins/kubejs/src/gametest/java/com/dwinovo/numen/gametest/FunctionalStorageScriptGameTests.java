package com.dwinovo.numen.gametest;

import com.buuz135.functionalstorage.block.tile.ControllableDrawerTile;
import com.buuz135.functionalstorage.block.tile.StorageControllerTile;
import com.buuz135.functionalstorage.item.LinkingToolItem;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * {@code plugins/kubejs/scripts/numen_functionalstorage.js}:脚本写的 {@code fs.drawers} 经抽屉的物品接口列出、取出、存入 Functional Storage
 * 的抽屉与存储控制器连着的抽屉。用的就是要装上服务器的那一份脚本,整批开始前由 {@link KubeJsScripts#install} 装上。
 * Functional Storage 与 Titanium 在这一次跑批的类路径上;抽屉里预先放的东西经同一个物品接口放进去。
 */
public class FunctionalStorageScriptGameTests {

    private static final String BATCH = "numen_kubejs_functionalstorage";
    /** 默认连"范围里全部"的用例单独一批:同一批里旁边用例的抽屉会落进控制器的范围。 */
    private static final String LINK_BATCH = "numen_kubejs_functionalstorage_link";
    private static final String SCRIPT = "numen_functionalstorage.js";

    /** 两批都要这份脚本;先跑的那一批装上,后跑的不再重载(重载的那几刻函数会短暂不在)。 */
    @BeforeBatch(batch = BATCH)
    public static void prepareFunctionalStorageBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
        if (ApiRegistry.function("fs.drawers.unlink") == null) {
            KubeJsScripts.install(level.getServer(), SCRIPT);
        }
    }

    @BeforeBatch(batch = LINK_BATCH)
    public static void prepareLinkBatch(ServerLevel level) {
        prepareFunctionalStorageBatch(level);
    }

    private static BlockPos place(GameTestHelper helper, int x, int y, int z, String id) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
        helper.getLevel().setBlockAndUpdate(pos,
                BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState());
        return pos;
    }

    private static IItemHandler items(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
    }

    /** 往抽屉的一格放 {@code count} 个,和漏斗、管道放进去一样。 */
    private static void fill(GameTestHelper helper, BlockPos pos, int slot, Item item, int count) {
        ItemStack rest = items(helper, pos).insertItem(slot, new ItemStack(item, count), false);
        if (!rest.isEmpty()) {
            throw new IllegalStateException("the drawer at " + pos + " did not take " + count + " " + item);
        }
    }

    private static int held(GameTestHelper helper, BlockPos pos, int slot) {
        return items(helper, pos).getStackInSlot(slot).getCount();
    }

    /** 这只抽屉连着的控制器;没连是 null。 */
    private static BlockPos controllerOf(GameTestHelper helper, BlockPos drawer) {
        return ((ControllableDrawerTile<?>) helper.getLevel().getBlockEntity(drawer)).getControllerPos();
    }

    /** 等到脚本的函数登记上(整批开始时装的脚本要几刻才重载完)。 */
    private static Steps registered(GameTestHelper helper) {
        return steps(helper).thenWaitUntil(() -> helper.assertTrue(ApiRegistry.function("fs.drawers.unlink") != null,
                "the Functional Storage script's functions were not registered after /reload"));
    }

    /** 跑一段程序,等它收场,再查。 */
    private static Steps program(Steps steps, GameTestHelper helper, NumenPlayer her, String code,
                                 Consumer<ToolRun> check) {
        AtomicReference<ToolRun> run = new AtomicReference<>();
        return steps.thenExecute(() -> run.set(lua(her, code)))
                .thenWaitUntil(() -> helper.assertTrue(run.get().done(), "the program has not finished"))
                .thenExecute(() -> check.accept(run.get()));
    }

    private static String receipt(ToolRun run) {
        return String.valueOf(run.receipt());
    }

    /**
     * 一只 2×2 抽屉的来回:200 个铁锭在第一格,取一个、取一组(64)、取剩下全部(135);她背的 100 块泥土放进去一块、一组、5 块、剩下全部(30)。
     * 铁锭取空后那一格(没上锁)空出来,泥土落进第一格空格;清单是"泥土 x100,三格空"。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void take_and_put_move_one_a_stack_or_all(GameTestHelper helper) {
        BlockPos drawer = place(helper, 8, 2, 7, "functionalstorage:oak_4");
        fill(helper, drawer, 0, Items.IRON_INGOT, 200);
        NumenPlayer her = spawnAt(helper, "gametest_fs_mover", new BlockPos(6, 2, 7), false);
        her.getInventory().add(new ItemStack(Items.DIRT, 64));
        her.getInventory().add(new ItemStack(Items.DIRT, 36));

        program(registered(helper), helper, her, "local d = " + xyz(drawer) + "\n"
                + "local a = fs.drawers.take(d, \"minecraft:iron_ingot\", {amount = \"one\"})\n"
                + "local b = fs.drawers.take(d, \"minecraft:iron_ingot\")\n"
                + "local c = fs.drawers.take(d, \"minecraft:iron_ingot\", {amount = \"all\"})\n"
                + "local p = fs.drawers.put(d, \"minecraft:dirt\", {amount = \"one\"})\n"
                + "local q = fs.drawers.put(d, \"minecraft:dirt\", {amount = \"stack\"})\n"
                + "local r = fs.drawers.put(d, \"minecraft:dirt\", {count = 5})\n"
                + "local s = fs.drawers.put(d, \"minecraft:dirt\")\n"
                + "return table.concat({a, b, c, p, q, r, s, fs.drawers.contents(d)[1]}, \"/\")", run -> {
            String receipt = receipt(run);
            helper.assertTrue(run.ranToTheEnd() && receipt.contains("1/64/135/1/64/5/30/")
                            && receipt.contains("functionalstorage:oak_4: minecraft:dirt x100/")
                            && receipt.contains(", (empty), (empty), (empty)"),
                    "taking and putting one, a stack and all went wrong: " + receipt);
            helper.assertTrue(her.getInventory().countItem(Items.IRON_INGOT) == 200
                            && her.getInventory().countItem(Items.DIRT) == 0 && held(helper, drawer, 0) == 100,
                    "she carries " + her.getInventory().countItem(Items.IRON_INGOT) + " iron and "
                            + her.getInventory().countItem(Items.DIRT) + " dirt; the drawer holds " + held(helper, drawer, 0));
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }

    /** 她只剩一格空位:取"全部"只取到放得下的 64 个,其余 436 个留在抽屉里,一个也不掉在地上。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = BATCH)
    public static void take_stops_when_her_inventory_is_full(GameTestHelper helper) {
        BlockPos drawer = place(helper, 8, 2, 7, "functionalstorage:oak_1");
        fill(helper, drawer, 0, Items.IRON_INGOT, 500);
        NumenPlayer her = spawnAt(helper, "gametest_fs_full", new BlockPos(6, 2, 7), false);
        for (int slot = 1; slot < 36; slot++) {
            her.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
        }

        program(registered(helper), helper, her,
                "return fs.drawers.take(" + xyz(drawer) + ", \"minecraft:iron_ingot\", {amount = \"all\"})", run -> {
            helper.assertTrue(run.ranToTheEnd() && her.getInventory().countItem(Items.IRON_INGOT) == 64
                            && held(helper, drawer, 0) == 436,
                    "with one free slot she carries " + her.getInventory().countItem(Items.IRON_INGOT)
                            + " iron, the drawer holds " + held(helper, drawer, 0) + ": " + receipt(run));
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            her.getBoundingBox().inflate(4)).isEmpty(), "items were dropped at her feet");
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }

    /**
     * 存储控制器连着三只抽屉(圆石 100、泥土 10、一只空的):经控制器放进 20 块圆石,进的是圆石那一只;取走全部泥土;清单一只一行。
     * 她背的沙子没有哪只抽屉设成沙子,控制器不往空抽屉里放:failed。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void a_controller_puts_into_and_takes_from_its_linked_drawers(GameTestHelper helper) {
        BlockPos controller = place(helper, 8, 2, 7, "functionalstorage:storage_controller");
        BlockPos cobble = place(helper, 8, 3, 7, "functionalstorage:oak_1");
        BlockPos dirt = place(helper, 9, 2, 7, "functionalstorage:oak_1");
        BlockPos spare = place(helper, 9, 3, 7, "functionalstorage:oak_1");
        fill(helper, cobble, 0, Items.COBBLESTONE, 100);
        fill(helper, dirt, 0, Items.DIRT, 10);
        StorageControllerTile<?> tile = (StorageControllerTile<?>) helper.getLevel().getBlockEntity(controller);
        tile.addConnectedDrawers(LinkingToolItem.ActionMode.ADD, cobble, dirt, spare);
        tile.getConnectedDrawers().setLevel(helper.getLevel());
        tile.getConnectedDrawers().rebuild();
        NumenPlayer her = spawnAt(helper, "gametest_fs_ctrl", new BlockPos(6, 2, 7), false);
        her.getInventory().add(new ItemStack(Items.COBBLESTONE, 20));
        her.getInventory().add(new ItemStack(Items.SAND, 5));

        Steps steps = program(registered(helper), helper, her, "local c = " + xyz(controller) + "\n"
                + "local put = fs.drawers.put(c, \"minecraft:cobblestone\")\n"
                + "local took = fs.drawers.take(c, \"minecraft:dirt\", {amount = \"all\"})\n"
                + "return table.concat({put, took, #fs.drawers.contents(c)}, \"/\")", run -> {
            helper.assertTrue(run.ranToTheEnd() && receipt(run).contains("20/10/3"),
                    "the controller round trip went wrong: " + receipt(run));
            helper.assertTrue(held(helper, cobble, 0) == 120 && held(helper, dirt, 0) == 0
                            && held(helper, spare, 0) == 0 && her.getInventory().countItem(Items.DIRT) == 10,
                    "the drawers hold " + held(helper, cobble, 0) + " cobblestone, " + held(helper, dirt, 0)
                            + " dirt and " + held(helper, spare, 0) + " in the spare one");
        });
        program(steps, helper, her, "return fs.drawers.put(" + xyz(controller) + ", \"minecraft:sand\")", run -> {
            helper.assertTrue(!run.succeeded() && "failed".equals(run.kind())
                            && her.getInventory().countItem(Items.SAND) == 5 && held(helper, spare, 0) == 0,
                    "sand no linked drawer is set to was not refused: kind " + run.kind() + ", " + run.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }

    /**
     * 带着连接工具的她把控制器范围里的抽屉连上:范围里两只空闲的连上(圆石、泥土),范围外(高 10 格)的一只不连,别的控制器连着的一只留给它;
     * 经控制器放进 20 块圆石进了圆石那一只;再只断开泥土那一只,控制器只剩一只。自成一批:默认连"范围里全部",旁边用例的抽屉不能进来。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = LINK_BATCH)
    public static void link_connects_free_drawers_in_range_and_unlink_releases_them(GameTestHelper helper) {
        BlockPos controller = place(helper, 8, 2, 7, "functionalstorage:storage_controller");
        BlockPos cobble = place(helper, 8, 3, 7, "functionalstorage:oak_1");
        BlockPos dirt = place(helper, 9, 2, 7, "functionalstorage:oak_1");
        BlockPos far = place(helper, 8, 12, 7, "functionalstorage:oak_1");
        BlockPos other = place(helper, 3, 2, 7, "functionalstorage:storage_controller");
        BlockPos taken = place(helper, 4, 2, 7, "functionalstorage:oak_1");
        fill(helper, cobble, 0, Items.COBBLESTONE, 100);
        fill(helper, dirt, 0, Items.DIRT, 10);
        StorageControllerTile<?> otherTile = (StorageControllerTile<?>) helper.getLevel().getBlockEntity(other);
        otherTile.getConnectedDrawers().setLevel(helper.getLevel());
        otherTile.addConnectedDrawers(LinkingToolItem.ActionMode.ADD, taken);
        NumenPlayer her = spawnAt(helper, "gametest_fs_link", new BlockPos(6, 2, 7), false);
        her.getInventory().add(new ItemStack(BuiltInRegistries.ITEM.get(
                ResourceLocation.parse("functionalstorage:linking_tool"))));
        her.getInventory().add(new ItemStack(Items.COBBLESTONE, 20));

        program(registered(helper), helper, her, "local c = " + xyz(controller) + "\n"
                + "local linked = fs.drawers.link(c)\n"
                + "local put = fs.drawers.put(c, \"minecraft:cobblestone\")\n"
                + "local unlinked = fs.drawers.unlink(c, {drawers = {" + xyz(dirt) + "}})\n"
                + "return table.concat({linked, put, unlinked, #fs.drawers.contents(c)}, \"/\")", run -> {
            String receipt = receipt(run);
            helper.assertTrue(run.ranToTheEnd() && receipt.contains("2/20/1/1")
                            && receipt.contains("1 belong to another controller"),
                    "linking and unlinking went wrong: " + receipt);
            helper.assertTrue(controllerOf(helper, cobble) != null && controllerOf(helper, cobble).equals(controller)
                            && controllerOf(helper, dirt) == null && controllerOf(helper, far) == null
                            && other.equals(controllerOf(helper, taken)) && held(helper, cobble, 0) == 120,
                    "the drawers are linked to " + controllerOf(helper, cobble) + ", " + controllerOf(helper, dirt)
                            + ", " + controllerOf(helper, far) + ", " + controllerOf(helper, taken));
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }

    /** 没带连接工具:no_material,提示的下一步是做一把,抽屉一只也没连上。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void linking_without_a_linking_tool_says_make_one(GameTestHelper helper) {
        BlockPos controller = place(helper, 8, 2, 7, "functionalstorage:storage_controller");
        BlockPos drawer = place(helper, 8, 3, 7, "functionalstorage:oak_1");
        NumenPlayer her = spawnAt(helper, "gametest_fs_notool", new BlockPos(6, 2, 7), false);

        program(registered(helper), helper, her, "return fs.drawers.link(" + xyz(controller) + ")", run -> {
            helper.assertTrue(!run.succeeded() && "no_material".equals(run.kind())
                            && String.valueOf(run.hint()).contains("functionalstorage:linking_tool")
                            && controllerOf(helper, drawer) == null,
                    "linking without the tool was not refused: kind " + run.kind() + ", hint " + run.hint());
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }

    /**
     * 附近两只放着紫水晶碎片的抽屉(近的 10 个、远的 3 个)和一只空抽屉:nearby 按过滤只列两只、近的在前;find 也是这两只、近的在前。
     * 用别的用例不用的物品,同一批里旁边的抽屉不进结果。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = BATCH)
    public static void nearby_lists_drawers_nearest_first_and_find_names_holders(GameTestHelper helper) {
        BlockPos near = place(helper, 4, 2, 2, "functionalstorage:oak_1");
        BlockPos far = place(helper, 10, 2, 2, "functionalstorage:oak_2");
        place(helper, 3, 2, 4, "functionalstorage:oak_1");
        fill(helper, near, 0, Items.AMETHYST_SHARD, 10);
        fill(helper, far, 1, Items.AMETHYST_SHARD, 3);
        NumenPlayer her = spawnAt(helper, "gametest_fs_scan", new BlockPos(2, 2, 2), false);

        program(registered(helper), helper, her,
                "local lines = fs.drawers.nearby({filter = \"amethyst\"})\n"
                        + "local found = fs.drawers.find(\"minecraft:amethyst_shard\")\n"
                        + "return table.concat({#lines, lines[1], #found, found[1].x, found[1].z, found[2].x}, \"|\")", run -> {
            String expected = "2|" + near.getX() + "," + near.getY() + "," + near.getZ()
                    + " functionalstorage:oak_1: minecraft:amethyst_shard x10/";
            String receipt = receipt(run);
            helper.assertTrue(run.ranToTheEnd() && receipt.contains(expected)
                            && receipt.contains("|2|" + near.getX() + "|" + near.getZ() + "|" + far.getX()),
                    "nearby or find went wrong, expected " + expected + "...: " + receipt);
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }

    /**
     * 一只没设过的压缩抽屉:放进 18 个铁锭就按铁设好(铁块、铁锭、铁粒同一份存量);清单是 2 块 = 18 锭 = 162 粒。empty 先取最大的那一档:
     * 她拿到 2 块铁块,抽屉空了。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = BATCH)
    public static void putting_into_an_empty_compacting_drawer_sets_it_up(GameTestHelper helper) {
        BlockPos drawer = place(helper, 8, 2, 7, "functionalstorage:compacting_drawer");
        NumenPlayer her = spawnAt(helper, "gametest_fs_compact", new BlockPos(6, 2, 7), false);
        her.getInventory().add(new ItemStack(Items.IRON_INGOT, 18));

        program(registered(helper), helper, her, "local d = " + xyz(drawer) + "\n"
                + "local put = fs.drawers.put(d, \"minecraft:iron_ingot\")\n"
                + "local line = fs.drawers.contents(d)[1]\n"
                + "local took = fs.drawers.empty(d)\n"
                + "return table.concat({put, took, line}, \"|\")", run -> {
            String receipt = receipt(run);
            helper.assertTrue(run.ranToTheEnd() && receipt.contains("18|2|") && receipt.contains("minecraft:iron_block x2/")
                            && receipt.contains("minecraft:iron_ingot x18/") && receipt.contains("minecraft:iron_nugget x162/"),
                    "the compacting drawer was not set up for iron: " + receipt);
            helper.assertTrue(her.getInventory().countItem(Items.IRON_BLOCK) == 2
                            && her.getInventory().countItem(Items.IRON_INGOT) == 0,
                    "after empty she carries " + her.getInventory().countItem(Items.IRON_BLOCK) + " iron blocks and "
                            + her.getInventory().countItem(Items.IRON_INGOT) + " ingots");
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }

    /** 装了虚空升级、只差 8 个就满的抽屉:她的 64 块圆石全收下,8 块进抽屉、56 块被销毁,回执说出来。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = BATCH)
    public static void a_void_drawer_destroys_the_overflow_and_says_so(GameTestHelper helper) {
        BlockPos drawer = place(helper, 8, 2, 7, "functionalstorage:oak_1");
        ((ControllableDrawerTile<?>) helper.getLevel().getBlockEntity(drawer)).getUtilityUpgrades().setStackInSlot(0,
                new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("functionalstorage:void_upgrade"))));
        int capacity = items(helper, drawer).getSlotLimit(0);
        fill(helper, drawer, 0, Items.COBBLESTONE, capacity - 8);
        NumenPlayer her = spawnAt(helper, "gametest_fs_void", new BlockPos(6, 2, 7), false);
        her.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));

        program(registered(helper), helper, her, "local d = " + xyz(drawer) + "\n"
                + "local put = fs.drawers.put(d, \"minecraft:cobblestone\")\n"
                + "return put .. \"|\" .. fs.drawers.contents(d)[1]", run -> {
            String receipt = receipt(run);
            helper.assertTrue(run.ranToTheEnd() && receipt.contains("64|") && receipt.contains("[void upgrade]")
                            && receipt.contains("56 did not fit and the void upgrade destroyed them"),
                    "the void upgrade's overflow was not reported: " + receipt);
            helper.assertTrue(held(helper, drawer, 0) == capacity && her.getInventory().countItem(Items.COBBLESTONE) == 0,
                    "the drawer holds " + held(helper, drawer, 0) + " of " + capacity);
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }

    /**
     * 上了锁的抽屉:取空 10 块泥土后那一格仍设成泥土(清单 x0、标着 locked),放回 4 块照收;她背的圆石它不收:failed,圆石还在她身上。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void a_locked_drawer_keeps_its_item_and_refuses_others(GameTestHelper helper) {
        BlockPos drawer = place(helper, 8, 2, 7, "functionalstorage:oak_1");
        fill(helper, drawer, 0, Items.DIRT, 10);
        ((ControllableDrawerTile<?>) helper.getLevel().getBlockEntity(drawer)).setLocked(true);
        NumenPlayer her = spawnAt(helper, "gametest_fs_locked", new BlockPos(6, 2, 7), false);
        her.getInventory().add(new ItemStack(Items.COBBLESTONE, 3));

        Steps steps = program(registered(helper), helper, her, "local d = " + xyz(drawer) + "\n"
                + "local took = fs.drawers.take(d, \"minecraft:dirt\", {amount = \"all\"})\n"
                + "local line = fs.drawers.contents(d)[1]\n"
                + "local back = fs.drawers.put(d, \"minecraft:dirt\", {count = 4})\n"
                + "return table.concat({took, back, line}, \"|\")", run -> {
            String receipt = receipt(run);
            helper.assertTrue(run.ranToTheEnd() && receipt.contains("10|4|") && receipt.contains("[locked]")
                            && receipt.contains("minecraft:dirt x0/"),
                    "the locked drawer did not keep dirt: " + receipt);
            helper.assertTrue(held(helper, drawer, 0) == 4, "the drawer holds " + held(helper, drawer, 0) + " dirt");
        });
        program(steps, helper, her, "return fs.drawers.put(" + xyz(drawer) + ", \"minecraft:cobblestone\")", run -> {
            helper.assertTrue(!run.succeeded() && "failed".equals(run.kind())
                            && her.getInventory().countItem(Items.COBBLESTONE) == 3,
                    "the locked drawer did not refuse cobblestone: kind " + run.kind() + ", " + run.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }

    /** 够不着的抽屉:out_of_reach,提示的下一步是走过去。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void a_drawer_out_of_reach_says_walk_there(GameTestHelper helper) {
        BlockPos drawer = place(helper, 14, 2, 14, "functionalstorage:oak_1");
        fill(helper, drawer, 0, Items.DIRT, 10);
        NumenPlayer her = spawnAt(helper, "gametest_fs_far", new BlockPos(1, 2, 1), false);

        program(registered(helper), helper, her, "return fs.drawers.take(" + xyz(drawer) + ", \"minecraft:dirt\")", run -> {
            String hint = String.valueOf(run.hint());
            helper.assertTrue(!run.succeeded() && "out_of_reach".equals(run.kind()) && hint.startsWith("numen.move.walk(")
                            && held(helper, drawer, 0) == 10,
                    "a far drawer was not reported as out of reach with a walk hint: kind " + run.kind() + ", hint "
                            + hint + ", " + run.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }
}
