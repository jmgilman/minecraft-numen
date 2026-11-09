package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.apache.commons.lang3.tuple.Triple;
import org.cyclops.integratedcrafting.gametest.GameTestHelpersIntegratedCrafting;
import org.cyclops.integratedcrafting.part.PartTypeInterfaceCrafting;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * {@code plugins/kubejs/scripts/numen_integrateddynamics.js}:脚本写的 {@code id.network} 经网络里的一格读、取、存 Integrated Dynamics 的物品网络,
 * 并经 Integrated Crafting 的合成接口下合成单。用的就是要装上服务器的那一份脚本,整批开始前由 {@link KubeJsScripts#install} 装上。
 * 网络用 Integrated Crafting 自带的 GameTest 助手搭:一格线缆、朝东的物品接口对着一只箱子(网络的存储),上面一格合成接口对着工作台。
 */
public class IntegratedDynamicsScriptGameTests {

    private static final String BATCH = "numen_kubejs_integrateddynamics";
    private static final String SCRIPT = "numen_integrateddynamics.js";
    /** 线缆;箱子在它东边,工作台在它西边。 */
    private static final BlockPos CABLE = new BlockPos(8, 2, 8);
    /** Integrated Dynamics 的用例等网络建好的刻数。 */
    private static final int NETWORK_INIT_TICKS = 20;

    @BeforeBatch(batch = BATCH)
    public static void prepareIntegratedDynamicsBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
        if (ApiRegistry.function("id.network.cancel") == null) {
            KubeJsScripts.install(level.getServer(), SCRIPT);
        }
    }

    private static ChestBlockEntity storage(GameTestHelper helper) {
        return helper.getBlockEntity(CABLE.east());
    }

    private static int stored(GameTestHelper helper, Item item) {
        return storage(helper).countItem(item);
    }

    /** 等网络建好、脚本的函数登记上。 */
    private static Steps ready(GameTestHelper helper) {
        return steps(helper).thenIdle(NETWORK_INIT_TICKS)
                .thenWaitUntil(() -> helper.assertTrue(ApiRegistry.function("id.network.cancel") != null,
                        "the Integrated Dynamics script's functions were not registered after /reload"));
    }

    private static Steps program(Steps steps, GameTestHelper helper, NumenPlayer her, String code,
                                 Consumer<ToolRun> check) {
        AtomicReference<ToolRun> run = new AtomicReference<>();
        return steps.thenExecute(() -> run.set(lua(her, code)))
                .thenWaitUntil(() -> helper.assertTrue(run.get().done(), "the program has not finished"))
                .thenExecute(() -> check.accept(run.get()));
    }

    private static String node(GameTestHelper helper) {
        return "local n = " + xyz(helper.absolutePos(CABLE)) + "\n";
    }

    /**
     * 箱子里 100 个铁锭、10 块泥土:清单两行、铁锭在前;数到 100;取 70 个(一次最多 64,分两次);她背的 20 块圆石全放进去;再放回 6 个铁锭。
     * 网络最后数到 36 个铁锭、20 块圆石,她背着 64 个铁锭。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void items_count_extract_and_insert_go_through_the_network(GameTestHelper helper) {
        GameTestHelpersIntegratedCrafting.createBasicNetwork(helper, CABLE);
        storage(helper).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
        storage(helper).setItem(1, new ItemStack(Items.IRON_INGOT, 36));
        storage(helper).setItem(2, new ItemStack(Items.DIRT, 10));
        NumenPlayer her = spawnAt(helper, "gametest_id_mover", new BlockPos(8, 2, 6), false);
        her.getInventory().add(new ItemStack(Items.COBBLESTONE, 20));

        program(ready(helper), helper, her, node(helper)
                + "local list = id.network.items(n)\n"
                + "local counted = id.network.count(n, \"minecraft:iron_ingot\")\n"
                + "local took = id.network.extract(n, \"minecraft:iron_ingot\", {count = 70})\n"
                + "local put = id.network.insert(n, \"minecraft:cobblestone\")\n"
                + "local back = id.network.insert(n, \"minecraft:iron_ingot\", {count = 6})\n"
                + "return table.concat({list[1], #list, counted, took, put, back, id.network.count(n, \"minecraft:iron_ingot\"),"
                + " id.network.count(n, \"minecraft:cobblestone\")}, \"/\")", run -> {
            String receipt = String.valueOf(run.receipt());
            helper.assertTrue(run.ranToTheEnd() && receipt.contains("minecraft:iron_ingot x100/2/100/70/20/6/36/20"),
                    "the network round trip went wrong: " + receipt);
            helper.assertTrue(her.getInventory().countItem(Items.IRON_INGOT) == 64
                            && her.getInventory().countItem(Items.COBBLESTONE) == 0
                            && stored(helper, Items.IRON_INGOT) == 36 && stored(helper, Items.COBBLESTONE) == 20,
                    "she carries " + her.getInventory().countItem(Items.IRON_INGOT) + " iron; the chest holds "
                            + stored(helper, Items.IRON_INGOT) + " iron and " + stored(helper, Items.COBBLESTONE) + " cobblestone");
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }

    /**
     * 合成接口上有箱子的配方、网络里有 64 块橡木板:craftable 列出箱子;下单做 2 只,单子在 jobs 里;做好的 2 只箱子进了网络(用掉 16 块木板),
     * 她再把它们取出来。
     */
    @GameTest(template = "floor16", timeoutTicks = 1200, batch = BATCH)
    public static void craft_starts_a_job_whose_result_lands_in_the_network(GameTestHelper helper) {
        GameTestHelpersIntegratedCrafting.INetworkPositions<PartTypeInterfaceCrafting.State> network =
                GameTestHelpersIntegratedCrafting.createBasicNetwork(helper, CABLE);
        storage(helper).setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        network.interfaceRecipeAdders().get(0).accept(Triple.of(0, RecipeType.CRAFTING,
                ResourceLocation.fromNamespaceAndPath("minecraft", "chest")));
        NumenPlayer her = spawnAt(helper, "gametest_id_crafter", new BlockPos(8, 2, 6), false);

        Steps steps = ready(helper).thenWaitUntil(() -> helper.assertTrue(network.interfaceStates().get(0).isRecipeSlotValid(0),
                "the chest recipe on the crafting interface is not valid"));
        steps = program(steps, helper, her, node(helper)
                + "local list = id.network.craftable(n)\n"
                + "local job = id.network.craft(n, \"minecraft:chest\", {count = 2})\n"
                + "return table.concat({list[1], #list, job >= 0 and \"job\" or \"none\", #id.network.jobs(n)}, \"/\")", run -> {
            String receipt = String.valueOf(run.receipt());
            helper.assertTrue(run.ranToTheEnd() && receipt.contains("minecraft:chest x1/1/job/1"),
                    "starting the crafting job went wrong: " + receipt);
        });
        steps = steps.thenWaitUntil(() -> helper.assertTrue(stored(helper, Items.CHEST) == 2,
                "the network holds " + stored(helper, Items.CHEST) + " crafted chests"));
        program(steps, helper, her, node(helper) + "return id.network.extract(n, \"minecraft:chest\")", run -> {
            helper.assertTrue(run.ranToTheEnd() && her.getInventory().countItem(Items.CHEST) == 2
                            && stored(helper, Items.OAK_PLANKS) == 48,
                    "she carries " + her.getInventory().countItem(Items.CHEST) + " chests; the network holds "
                            + stored(helper, Items.OAK_PLANKS) + " planks: " + run.receipt());
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }

    /** 只有 3 块木板,不够做一只箱子,也没有木板的配方:no_material,说出缺的是橡木板;一张单子也没下。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void craft_names_what_is_missing(GameTestHelper helper) {
        GameTestHelpersIntegratedCrafting.INetworkPositions<PartTypeInterfaceCrafting.State> network =
                GameTestHelpersIntegratedCrafting.createBasicNetwork(helper, CABLE);
        storage(helper).setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        network.interfaceRecipeAdders().get(0).accept(Triple.of(0, RecipeType.CRAFTING,
                ResourceLocation.fromNamespaceAndPath("minecraft", "chest")));
        NumenPlayer her = spawnAt(helper, "gametest_id_short", new BlockPos(8, 2, 6), false);

        Steps steps = ready(helper).thenWaitUntil(() -> helper.assertTrue(network.interfaceStates().get(0).isRecipeSlotValid(0),
                "the chest recipe on the crafting interface is not valid"));
        program(steps, helper, her, node(helper) + "return id.network.craft(n, \"minecraft:chest\")", run -> {
            String outcome = String.valueOf(run.outcome());
            helper.assertTrue(!run.succeeded() && "no_material".equals(run.kind()) && outcome.contains("missing 5 minecraft:oak_planks")
                            && network.interfaceStates().get(0).getCraftingJobHandler().getPendingCraftingJobs().isEmpty()
                            && network.interfaceStates().get(0).getCraftingJobHandler().getProcessingCraftingJobs().isEmpty(),
                    "a craft without enough planks was not refused with what is missing: kind " + run.kind() + ", " + outcome);
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }

    /** 够不着的网络方块:out_of_reach,提示的下一步是走过去。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = BATCH)
    public static void a_network_block_out_of_reach_says_walk_there(GameTestHelper helper) {
        GameTestHelpersIntegratedCrafting.createBasicNetwork(helper, new BlockPos(13, 2, 13));
        NumenPlayer her = spawnAt(helper, "gametest_id_far", new BlockPos(1, 2, 1), false);

        program(ready(helper), helper, her, "return id.network.items(" + xyz(helper.absolutePos(new BlockPos(13, 2, 13))) + ")", run -> {
            String hint = String.valueOf(run.hint());
            helper.assertTrue(!run.succeeded() && "out_of_reach".equals(run.kind()) && hint.startsWith("numen.move.walk("),
                    "a far network block was not reported as out of reach with a walk hint: kind " + run.kind()
                            + ", hint " + hint + ", " + run.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        }).thenSucceed();
    }
}
