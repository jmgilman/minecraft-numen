package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.gametest.NumenBeforeBatch;
import com.dwinovo.numen.pathing.gametest.NumenTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * Refined Storage 联动:{@code rs.network} 经一个网络方块读、取、存整个网络。开发运行里装着 Refined Storage(core/neoforge 的
 * localRuntime),联动的闸门开着;方块按 id 取,本类不引用 Refined Storage 的类型。
 */
public class RefinedStorageGameTests {

    @NumenBeforeBatch(batch = "numen_refinedstorage")
    public static void prepareRefinedStorageBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 一个网络的来回:创造控制器供电,东边一台磁盘驱动器、北边一台网格(两台都朝北,正面不贴控制器——正面不接网络)。她把背着的 1k 盘放进
     * 驱动器,存进 40 块圆石,网络数到 40;取 15 块,网络剩 25、她背着 15;清单里排第一的就是圆石。
     */
    @NumenTest(template = "floor16", timeoutTicks = 2000, batch = "numen_refinedstorage")
    public static void insert_count_and_extract_go_through_the_network(GameTestHelper helper) {
        BlockPos controller = helper.absolutePos(new BlockPos(8, 2, 7));
        BlockPos drive = helper.absolutePos(new BlockPos(9, 2, 7));
        BlockPos grid = helper.absolutePos(new BlockPos(8, 2, 6));
        place(helper, controller, "refinedstorage:creative_controller");
        place(helper, drive, "refinedstorage:disk_drive");
        place(helper, grid, "refinedstorage:grid");
        NumenPlayer companion = spawnAt(helper, "gametest_storekeeper", new BlockPos(6, 2, 7), false);
        companion.getInventory().add(new ItemStack(item("refinedstorage:1k_storage_disk")));
        companion.getInventory().add(new ItemStack(Items.COBBLESTONE, 40));
        ToolRun run = lua(companion, "local drive = " + xyz(drive) + "\nlocal grid = " + xyz(grid) + "\n"
                // 新盘在背包里过一刻才分到存储(Refined Storage 在物品的背包刻里给它建盘),放进驱动器之前先等一下
                + "numen.time.wait(1)\n"
                + "local w = numen.use.block(drive)\nw:put(\"refinedstorage:1k_storage_disk\", 1)\nw:close()\n"
                + "numen.time.wait(1)\n"
                + "local put = rs.network.insert(grid, \"minecraft:cobblestone\")\n"
                + "local counted = rs.network.count(grid, \"minecraft:cobblestone\")\n"
                + "local took = rs.network.extract(grid, \"minecraft:cobblestone\", {count = 15})\n"
                + "local all = rs.network.items(grid)\n"
                + "return table.concat({put.moved, counted, took.moved, took.carrying, took.left, all[1].item, "
                + "all[1].count}, \"/\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.done(), "the program has not finished");
            helper.assertTrue(run.ranToTheEnd() && run.receipt().contains("40/40/15/15/25/minecraft:cobblestone/25"),
                    "the network round trip went wrong: " + run.receipt());
            helper.assertTrue(companion.getInventory().countItem(Items.COBBLESTONE) == 15,
                    "she carries " + companion.getInventory().countItem(Items.COBBLESTONE) + " cobblestone");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 没电的网络:普通控制器没接能源,网格前读清单是 failed,说的是没电——玩家站在没电的网格前也什么都看不到。 */
    @NumenTest(template = "floor16", timeoutTicks = 400, batch = "numen_refinedstorage")
    public static void an_unpowered_network_says_so(GameTestHelper helper) {
        BlockPos controller = helper.absolutePos(new BlockPos(8, 2, 7));
        BlockPos grid = helper.absolutePos(new BlockPos(8, 2, 6));
        place(helper, controller, "refinedstorage:controller");
        place(helper, grid, "refinedstorage:grid");
        NumenPlayer companion = spawnAt(helper, "gametest_dark_grid", new BlockPos(6, 2, 7), false);
        ToolRun run = lua(companion, "numen.time.wait(1)\nreturn rs.network.items(" + xyz(grid) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.done(), "the program has not finished");
            helper.assertTrue(!run.ranToTheEnd() && run.receipt().contains("has no power"),
                    "an unpowered network was not reported as such: " + run.receipt());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    private static void place(GameTestHelper helper, BlockPos pos, String id) {
        Block block = BuiltInRegistries.BLOCK.get(Identifier.parse(id)).orElseThrow().value();
        helper.getLevel().setBlockAndUpdate(pos, block.defaultBlockState());
    }

    private static net.minecraft.world.item.Item item(String id) {
        return BuiltInRegistries.ITEM.get(Identifier.parse(id)).orElseThrow().value();
    }
}
