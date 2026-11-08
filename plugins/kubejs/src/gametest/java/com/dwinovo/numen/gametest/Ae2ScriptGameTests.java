package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * {@code plugins/kubejs/scripts/numen_ae2.js}:脚本写的 {@code ae2.network} 经一个 ME 方块读、取、存整个网络。用的就是要装上服务器的那一份脚本,
 * 由 {@link KubeJsScripts#install} 拷进运行目录的 {@code kubejs/server_scripts} 再 {@code /reload}。AE2 在这一次跑批的运行时类路径上。
 * 网络是一张无需线缆的临时网:创造能量元件、ME 驱动器、ME 接口三格相邻。
 */
public class Ae2ScriptGameTests {

    private static final String BATCH = "numen_kubejs_ae2";

    @BeforeBatch(batch = BATCH)
    public static void prepareAe2Batch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    private static void place(GameTestHelper helper, BlockPos pos, String id) {
        helper.getLevel().setBlockAndUpdate(pos,
                BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState());
    }

    /**
     * 一个网络的来回:她把背着的 1k 元件放进驱动器,经接口存进 40 块圆石,网络数到 40;取 15 块,她背着 15;清单第一行是剩下的 25 块圆石。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void insert_count_and_extract_go_through_the_me_network(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos energy = helper.absolutePos(new BlockPos(8, 2, 7));
        BlockPos drive = helper.absolutePos(new BlockPos(9, 2, 7));
        BlockPos iface = helper.absolutePos(new BlockPos(8, 2, 6));
        place(helper, energy, "ae2:creative_energy_cell");
        place(helper, drive, "ae2:drive");
        place(helper, iface, "ae2:interface");
        NumenPlayer her = spawnAt(helper, "gametest_me_keeper", new BlockPos(6, 2, 7), false);
        her.getInventory().add(new ItemStack(BuiltInRegistries.ITEM.get(
                ResourceLocation.parse("ae2:item_storage_cell_1k"))));
        her.getInventory().add(new ItemStack(Items.COBBLESTONE, 40));
        AtomicReference<ToolRun> run = new AtomicReference<>();
        KubeJsScripts.install(server, "numen_ae2.js");

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(ApiRegistry.function("ae2.network.extract") != null,
                        "the AE2 script's functions were not registered after /reload"))
                .thenExecute(() -> run.set(lua(her, "local drive = " + xyz(drive) + "\nlocal node = " + xyz(iface) + "\n"
                        + "local w = numen.use.block(drive)\nw:put(\"ae2:item_storage_cell_1k\", 1)\nw:close()\n"
                        + "numen.time.wait(1)\n"
                        + "local put = ae2.network.insert(node, \"minecraft:cobblestone\")\n"
                        + "local counted = ae2.network.count(node, \"minecraft:cobblestone\")\n"
                        + "local took = ae2.network.extract(node, \"minecraft:cobblestone\", {count = 15})\n"
                        + "local first = ae2.network.items(node)[1]\n"
                        + "return table.concat({put, counted, took, first}, \"/\")")))
                .thenWaitUntil(() -> helper.assertTrue(run.get().done(), "the program has not finished"))
                .thenExecute(() -> {
                    String receipt = String.valueOf(run.get().receipt());
                    helper.assertTrue(run.get().ranToTheEnd() && receipt.contains("40/40/15/minecraft:cobblestone x25"),
                            "the ME network round trip went wrong: " + receipt);
                    helper.assertTrue(her.getInventory().countItem(Items.COBBLESTONE) == 15,
                            "she carries " + her.getInventory().countItem(Items.COBBLESTONE) + " cobblestone");
                    CompanionFactory.despawn(server, her);
                })
                .thenSucceed();
    }

    /** 够不着的 ME 方块:out_of_reach,提示的下一步是走过去。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void an_me_block_out_of_reach_says_walk_there(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos energy = helper.absolutePos(new BlockPos(14, 2, 14));
        BlockPos iface = helper.absolutePos(new BlockPos(14, 2, 13));
        place(helper, energy, "ae2:creative_energy_cell");
        place(helper, iface, "ae2:interface");
        NumenPlayer her = spawnAt(helper, "gametest_me_far", new BlockPos(1, 2, 1), false);
        AtomicReference<ToolRun> run = new AtomicReference<>();
        KubeJsScripts.install(server, "numen_ae2.js");

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(ApiRegistry.function("ae2.network.items") != null,
                        "the AE2 script's functions were not registered after /reload"))
                .thenExecute(() -> run.set(lua(her, "return ae2.network.items(" + xyz(iface) + ")")))
                .thenWaitUntil(() -> helper.assertTrue(run.get().done(), "the program has not finished"))
                .thenExecute(() -> {
                    String hint = String.valueOf(run.get().hint());
                    helper.assertTrue(!run.get().succeeded() && "out_of_reach".equals(run.get().kind())
                                    && hint.startsWith("numen.move.to("),
                            "a far ME block was not reported as out of reach with a walk hint: kind "
                                    + run.get().kind() + ", hint " + hint + ", " + run.get().outcome());
                    CompanionFactory.despawn(server, her);
                })
                .thenSucceed();
    }
}
