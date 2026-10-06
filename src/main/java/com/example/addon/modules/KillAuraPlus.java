package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;

import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import net.minecraft.registry.Registries;

import java.util.HashSet;
import java.util.Set;

public class KillAuraPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCrits = settings.createGroup("Crits");

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Кого бить.")
        .defaultValue(allAttackable())
        .onlyAttackable()
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Радиус атаки.")
        .defaultValue(4.0)
        .min(1.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Поворачиваться к цели перед ударом.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Не бить, пока ешь или используешь предмет.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyCrits = sgCrits.add(new BoolSetting.Builder()
        .name("only-crits")
        .description("Бить только в падении, чтобы удар был критическим.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stopSprint = sgCrits.add(new BoolSetting.Builder()
        .name("stop-sprint")
        .description("Перед критом на миг отключать спринт (в спринте крита не бывает).")
        .defaultValue(true)
        .visible(onlyCrits::get)
        .build()
    );

    private final Setting<Boolean> critFallback = sgCrits.add(new BoolSetting.Builder()
        .name("close-fallback")
        .description("Если цель очень близко (почти вплотную), бить без крита.")
        .defaultValue(false)
        .visible(onlyCrits::get)
        .build()
    );

    private final Setting<Double> fallbackDistance = sgCrits.add(new DoubleSetting.Builder()
        .name("fallback-distance")
        .description("Дистанция, на которой бьём без крита.")
        .defaultValue(1.5)
        .min(0.5)
        .sliderMax(3.0)
        .visible(() -> onlyCrits.get() && critFallback.get())
        .build()
    );

    private static Set<EntityType<?>> allAttackable() {
        Set<EntityType<?>> set = new HashSet<>();
        for (EntityType<?> type : Registries.ENTITY_TYPE) {
            if (EntityUtils.isAttackable(type)) set.add(type);
        }
        return set;
    }

    public KillAuraPlus() {
        super(AddonTemplate.CATEGORY, "kill-aura-plus", "Kill Aura с настройкой критов в падении.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (!mc.player.isAlive()) return;
        if (pauseOnUse.get() && mc.player.isUsingItem()) return;
        if (mc.player.getAttackCooldownProgress(0.5f) < 1f) return;

        Entity target = null;
        double best = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
            if (!entities.get().contains(entity.getType())) continue;

            double dist = mc.player.distanceTo(entity);
            if (dist > range.get() || dist >= best) continue;

            best = dist;
            target = entity;
        }

        if (target == null) return;

        if (onlyCrits.get() && !canCrit()) {
            boolean tooClose = critFallback.get() && best <= fallbackDistance.get();
            if (!tooClose) return;
        }

        final Entity t = target;
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(t), Rotations.getPitch(t), () -> attack(t));
        } else {
            attack(t);
        }
    }

    // Условия ванильного крита: падение, не на земле, не в воде, не на лестнице,
    // нет слепоты, не на транспорте и не в спринте.
    private boolean canCrit() {
        return mc.player.fallDistance > 0
            && !mc.player.isOnGround()
            && !mc.player.isClimbing()
            && !mc.player.isTouchingWater()
            && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS)
            && !mc.player.hasVehicle()
            && (stopSprint.get() || !mc.player.isSprinting());
    }

    private void attack(Entity entity) {
        boolean unsprint = onlyCrits.get() && stopSprint.get() && mc.player.isSprinting() && canCrit();

        if (unsprint) {
            mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        }

        mc.interactionManager.attackEntity(mc.player, entity);
        mc.player.swingHand(Hand.MAIN_HAND);

        if (unsprint) {
            mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
        }
    }
}
