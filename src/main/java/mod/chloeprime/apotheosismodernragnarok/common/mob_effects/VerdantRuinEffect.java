package mod.chloeprime.apotheosismodernragnarok.common.mob_effects;

import com.google.common.collect.MapMaker; // 【新增导入】Guava 并发 Map 构建器
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.item.IGun;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import mod.chloeprime.apotheosismodernragnarok.ApotheosisModernRagnarok;
import mod.chloeprime.apotheosismodernragnarok.common.ModContent;
import mod.chloeprime.apotheosismodernragnarok.common.affix.category.ExtraLootCategories;
import mod.chloeprime.gunsmithlib.api.common.GunAttributes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.awt.*;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
// import java.util.WeakHashMap; // 【移除】致命的非线程安全类

/**
 * 提升爆头倍率，
 * 但效果等级 >= 5 且未爆头命中时受到大量伤害并清空该 buff
 */
public class VerdantRuinEffect extends MobEffect {
    public static final int DEFAULT_MAX_LEVEL = 10;
    public static final UUID MODIFIER_UUID = UUID.fromString("7c692dce-b734-41e7-993d-362390b013a8");
    public static final ResourceKey<DamageType> DAMAGE_TYPE = ResourceKey.create(Registries.DAMAGE_TYPE, ApotheosisModernRagnarok.loc("verdant_ruin"));

    public VerdantRuinEffect(MobEffectCategory category, Color color) {
        super(category, color.getRGB());
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static VerdantRuinEffect create() {
        return (VerdantRuinEffect) new VerdantRuinEffect(MobEffectCategory.BENEFICIAL, new Color(45, 141, 137, 255))
                .addAttributeModifier(GunAttributes.HEADSHOT_MULTIPLIER.get(), MODIFIER_UUID.toString(), 0.15F, AttributeModifier.Operation.ADDITION);
    }

    @SubscribeEvent
    public final void onGunShoot(GunShootEvent event) {
        var shooter = event.getShooter();
        if (shooter == null || shooter.getEffect(this) == null) {
            return;
        }
        if (LootCategory.forItem(event.getGunItemStack()) != ExtraLootCategories.BOLT_ACTION) {
            shooter.removeEffect(this);
        }
    }

    @SubscribeEvent
    public final void onGunshotPost(EntityHurtByGunEvent.Post event) {
        onGunshotPost(event.getAttacker(), event.getBullet(), event.isHeadShot());
    }

    @SubscribeEvent
    public final void onGunshotKill(EntityKillByGunEvent event) {
        onGunshotPost(event.getAttacker(), event.getBullet(), event.isHeadShot());
    }

    @SubscribeEvent
    public final void onGunHitBlock(EntityLeaveLevelEvent event) {
        // 只有实体被 discard 时才进行后续计算
        if (event.getEntity().getRemovalReason() != Entity.RemovalReason.DISCARDED) {
            return;
        }
        if (event.getEntity() instanceof Projectile bullet && bullet.getOwner() instanceof LivingEntity shooter) {
            if (shooter.getEffect(this) == null) {
                return;
            }
            if (IGun.mainHandHoldGun(shooter) && !PROCESSED_BULLETS.contains(bullet)) {
                // 打中方块或者射向"浩瀚星辰"时，如果这颗子弹没有爆过头，那么照样视作失误
                onGunshotPost(shooter, bullet, false);
            }
        }
    }

    // MC1.21.1: Replace with data attachment
    // 【核心修改点 1】：使用 Guava 的 MapMaker 替换原生 WeakHashMap。
    // weakKeys() 保证实体卸载时不会内存泄漏（且底层契约基于 == 比较，完美符合子弹实体的判断逻辑）。
    // concurrencyLevel(4) 为高射速并发场景提供底层的分段锁支持。
    private static final Set<Entity> PROCESSED_BULLETS = Collections.newSetFromMap(
            new MapMaker().weakKeys().concurrencyLevel(4).makeMap()
    );

    public void onGunshotPost(@Nullable LivingEntity shooter, Entity bullet, boolean isHeadshot) {
        // 基础判空
        if (shooter == null) {
            return;
        }

        // 【核心修改点 2】：逻辑前置。
        // 在进行任何集合操作前，先检查射击者是否拥有该 Buff！
        // 这意味着服务器中 99% 的普通射击（无 Buff 状态）都会在这里被直接拦截，
        // 从而彻底杜绝了无效子弹涌入 Map 造成的性能浪费和死锁风险。
        var instance = shooter.getEffect(this);
        if (instance == null) {
            return;
        }

        // 确认玩家拥有 Buff 后，再将子弹加入安全的并发集合中记录
        if (bullet != null) {
            PROCESSED_BULLETS.add(bullet);
        }
        
        // 爆头免除惩罚
        if (isHeadshot) {
            return;
        }

        // --- 以下原作者的惩罚逻辑保持完全不变 ---
        // 没有爆头时移除本效果
        shooter.removeEffect(this);
        
        // 惩罚性伤害
        var level = instance.getAmplifier() + 1;
        if (level < DEFAULT_MAX_LEVEL / 2) {
            return;
        }
        var penalty = shooter.getMaxHealth() * level / DEFAULT_MAX_LEVEL;
        shooter.hurt(shooter.damageSources().source(DAMAGE_TYPE, null, null), penalty);
        shooter.level().playSound(null, shooter, ModContent.Sounds.ARMOR_CRACK.get(), shooter.getSoundSource(), 1, 1);
    }
}
