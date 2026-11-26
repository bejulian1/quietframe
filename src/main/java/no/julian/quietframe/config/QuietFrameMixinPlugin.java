package no.julian.quietframe.config;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class QuietFrameMixinPlugin implements IMixinConfigPlugin {
    private boolean available;

    public static String conflict(Predicate<String> isLoaded) {
        if (isLoaded.test("dynamic_fps")) {
            return "dynamic_fps";
        }
        return isLoaded.test("fpsreducer") ? "fpsreducer" : null;
    }

    @Override
    public void onLoad(String mixinPackage) {
        available = conflict(FabricLoader.getInstance()::isModLoaded) == null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return available;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
