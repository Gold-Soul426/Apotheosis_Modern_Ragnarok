package mod.chloeprime.apotheosismodernragnarok.common.eventhandlers;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import dev.shadowsoffire.attributeslib.AttributesLib;
import dev.shadowsoffire.attributeslib.api.ALObjects;
import dev.shadowsoffire.attributeslib.packet.CritParticleMessage;
import dev.shadowsoffire.placebo.network.PacketDistro;
import mod.chloeprime.apotheosismodernragnarok.common.ModContent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static dev.shadowsoffire.attributeslib.api.ALObjects.Attributes.CRIT_CHANCE;

/**
 * 修复暴击暴伤判定对子弹的 2 段伤害分别判定的问题
 * 同时为 KubeJS 提供“枪械神化暴击已触发”的可读上下文。
 */
@Mod.EventBusSubscriber
public class GunCritFix {
    private GunCritFix() {
    }

    public static final AttributeModifier AM = new AttributeModifier(
            UUID.fromString("23814f79-3a57-4e69-8fd2-590bfc23f0e4"),
            "Gun Crit Fix", -2000000000, AttributeModifier.Operation.ADDITION
    );

    public static final Supplier<Multimap<Attribute, AttributeModifier>> AM_TABLE = Suppliers.memoize(
            () -> ImmutableMultimap.of(CRIT_CHANCE.get(), AM)
    );

    private static final Map<Integer, Deque<CritContext>> KUBEJS_GUN_CRIT_CONTEXT = new HashMap<>();

    public static record CritContext(int critCount, double critChance, float critDamage, float multiplier, float baseDamage) {
    }

    private static void pushCritContext(LivingEntity victim, CritContext ctx) {
        KUBEJS_GUN_CRIT_CONTEXT.computeIfAbsent(victim.getId(), id -> new ArrayDeque<>()).push(ctx);
    }

    public static @Nullable CritContext popCritContext(LivingEntity victim) {
        Deque<CritContext> stack = KUBEJS_GUN_CRIT_CONTEXT.get(victim.getId());
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        CritContext ctx = stack.pop();
        if (stack.isEmpty()) {
            KUBEJS_GUN_CRIT_CONTEXT.remove(victim.getId());
        }
        return ctx;
    }

    private static void clearCritContext(@Nullable Entity victim) {
        if (victim instanceof LivingEntity living) {
            KUBEJS_GUN_CRIT_CONTEXT.remove(living.getId());
        }
    }

    @SubscribeEvent
    public static void preHurt(EntityHurtByGunEvent.Pre event) {
        if (event.getLogicalSide().isClient()) {
            return;
        }

        var shooter = event.getAttacker();
        var victim = event.getHurtEntity();

        // 先清掉上一次未消费的旧上下文，避免串线
        clearCritContext(victim);

        if (shooter == null || victim == null || shooter.getAttributeValue(CRIT_CHANCE.get()) <= 0) {
            return;
        }

        rollCrit(event, shooter, victim);
        shooter.getAttributes().addTransientAttributeModifiers(AM_TABLE.get());
    }

    private static void rollCrit(EntityHurtByGunEvent.Pre event, LivingEntity attacker, Entity victim) {
        double critChance = attacker.getAttributeValue(ALObjects.Attributes.CRIT_CHANCE.get());
        float critDmg = (float) attacker.getAttributeValue(ALObjects.Attributes.CRIT_DAMAGE.get());

        double originalCritChance = critChance;
        float originalCritDmg = critDmg;

        RandomSource rand = event.getHurtEntity() instanceof LivingEntity le
                ? le.getRandom()
                : attacker.getRandom();

        float critMult = 1.0F;
        int critCount = 0;

        // Roll for crits. Each overcrit reduces the effectiveness by 15%
        // We stop rolling when crit chance fails or the crit damage would reduce the total damage dealt.
        while (rand.nextFloat() <= critChance && critDmg > 1.0F) {
            critChance--;
            critMult *= critDmg;
            critDmg *= 0.85F;
            critCount++;
        }

        float baseDamage = event.getBaseAmount();
        event.setBaseAmount(baseDamage * critMult);

        if (critMult > 1.0F) {
            if (victim instanceof LivingEntity livingVictim) {
                pushCritContext(livingVictim, new CritContext(
                        critCount,
                        originalCritChance,
                        originalCritDmg,
                        critMult,
                        baseDamage
                ));
            }

            if (!attacker.level().isClientSide) {
                criticalFeedback(attacker, victim);
            }
        }
    }

    public static void criticalFeedback(LivingEntity attacker, Entity victim) {
        if (attacker.level().isClientSide) {
            return;
        }

        PacketDistro.sendToTracking(
                AttributesLib.CHANNEL,
                new CritParticleMessage(victim.getId()),
                (ServerLevel) attacker.level(),
                victim.blockPosition()
        );

        // 播放暴击音效
        var rand = attacker.getRandom();
        var snd = ModContent.Sounds.CRITICAL_HIT.get();
        var sndPitch = Mth.lerp(rand.nextFloat(), 0.8F, 1.25F);
        victim.playSound(snd, 1, sndPitch);

        if (attacker instanceof Player player && player.position().distanceToSqr(victim.position()) > 4 * 4) {
            player.playNotifySound(snd, victim.getSoundSource(), 1, sndPitch);
        }
    }

    @SubscribeEvent
    public static void postHurt(EntityHurtByGunEvent.Post event) {
        cleanup(event.getAttacker());
    }

    @SubscribeEvent
    public static void postKill(EntityKillByGunEvent event) {
        cleanup(event.getAttacker());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void clearCritContextAfterDamage(LivingDamageEvent event) {
        clearCritContext(event.getEntity());
    }

    private static void cleanup(@Nullable LivingEntity shooter) {
        if (shooter == null || shooter.level().isClientSide) {
            return;
        }
        shooter.getAttributes().removeAttributeModifiers(AM_TABLE.get());
    }
}
