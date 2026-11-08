package com.dwinovo.numen.plugins.refinedstorage;

import com.dwinovo.numen.api.NumenPlugins;

import java.nio.file.Path;

/**
 * Refined Storage 联动:同伴经一个网络方块读、取、存整个存储网络。
 *
 * <p>网格的物品列表是 Refined Storage 直接发给玩家客户端的,假玩家没有客户端,开了网格也看不到一件东西;网络里的盘也不对外暴露成容器。
 * 所以这里不走界面,经 Refined Storage 的公开 API 直接找到方块所在的网络,在服务端主线程上读写它。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。装没装 Refined Storage 由 {@code Builtin} 那道闸判断,判断为真才调
 * {@link #install};不在场时这个包的类一次都不会被加载。
 */
public final class NumenRefinedStorage {

    private NumenRefinedStorage() {}

    /** 由 {@code Builtin} 在确认 Refined Storage 在场后调用。读写网络发生在服务端,所以直接登记,不放进 onClient。 */
    public static void install(Path skillsRoot) {
        NumenPlugins.register(RsNetworkApi.NAMESPACE, numen -> {
            RsNetworkApi.install(numen);
            if (skillsRoot != null) numen.bundleSkills(skillsRoot);
        });
    }
}
