package mod.chloeprime.apotheosismodernragnarok.client;

import mod.chloeprime.apotheosismodernragnarok.common.affix.content.MagicalShotAffix;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Supplier;

@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public class ClientCoremodHooks {
    private ClientCoremodHooks() {
    }

    public static ResourceLocation adjustGunSound(ItemStack weapon, Supplier<ResourceLocation> fallback) {
        return MagicalShotAffix.getSoundFor(weapon)
                .map(holder -> holder instanceof Holder.Reference<SoundEvent> ref
                        ? ref.key().location()
                        : holder.get().getLocation())
                .orElseGet(fallback);
    }
}
