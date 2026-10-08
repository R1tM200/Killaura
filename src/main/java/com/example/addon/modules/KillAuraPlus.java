package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class KillAuraPlus extends Module {
    public enum Weapon { Sword, Axe, Mace, Trident, All, Any }
    public enum RotationMode { Always, OnHit, None }
    public enum ShieldMode { Ignore, Break, None }
    public enum EntityAge { Baby, Adult, Both }
    public enum AimPoint { Nearest, Head, Body, Feet }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgCrits = settings.createGroup("Crits");

    // ---------- General (как в обычной Kill Aura) ----------

    private final Setting<Weapon> weapon = sgGeneral.add(new EnumSetting.Builder<Weapon>()
        .name("weapon")
        .description("Only attacks an entity when a specified weapon is in your hand.")
        .defaultValue(Weapon.All)
        .build()
    );

    private final Setting<RotationMode> rotation = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotate")
        .description("Determines when you should rotate towards the target.")
        .defaultValue(RotationMode.Always)
        .build()
    );

    private final Setting<Boolean> silentAim = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-aim")
        .description("Бить уже после того, как на сервер ушёл поворот к цели (техника Baritone). Камера у тебя не двигается. Не работает при rotate = None.")
        .defaultValue(true)
        .build()
    );

    private final Setting<AimPoint> aimPoint = sgGeneral.add(new EnumSetting.Builder<AimPoint>()
        .name("aim-point")
        .description("Куда целиться: Nearest = ближайшая к камере точка цели, либо голова / тело / ноги.")
        .defaultValue(AimPoint.Nearest)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to your selected weapon when attacking the target.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Switches to your previous slot when done attacking the target.")
        .defaultValue(false)
        .visible(autoSwitch::get)
        .build()
    );

    private final Setting<ShieldMode> shieldMode = sgGeneral.add(new EnumSetting.Builder<ShieldMode>()
        .name("shield-mode")
        .description("Will try and use an axe to break target shields.")
        .defaultValue(ShieldMode.Break)
        .visible(() -> autoSwitch.get() && weapon.get() != Weapon.Axe)
        .build()
    );

    private final Setting<Boolean> onlyOnClick = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-click")
        .description("Only attacks when holding left click.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyOnLook = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-look")
        .description("Only attacks when looking at an entity.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseOnCombat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Freezes Baritone temporarily until you are finished attacking the entity.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> restoreSlot = sgGeneral.add(new BoolSetting.Builder()
        .name("restore-slot")
        .description("Если сервер сам сменил тебе слот хотбара (например, зачарование Confusion), вернуть слот, который выбрал ты. Смена колесиком или цифрой не затрагивается.")
        .defaultValue(true)
        .build()
    );

    // ---------- Targeting ----------

    private final Setting<Set<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to attack.")
        .onlyAttackable()
        .defaultValue(allAttackable())
        .build()
    );

    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("How to filter targets within range.")
        .defaultValue(SortPriority.ClosestAngle)
        .build()
    );

    private final Setting<Integer> maxTargets = sgTargeting.add(new IntSetting.Builder()
        .name("max-targets")
        .description("How many entities to target at once.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 5)
        .visible(() -> !onlyOnLook.get())
        .build()
    );

    private final Setting<Double> range = sgTargeting.add(new DoubleSetting.Builder()
        .name("range")
        .description("The maximum range the entity can be to attack it.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> wallsRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("The maximum range the entity can be attacked through walls.")
        .defaultValue(3.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> fov = sgTargeting.add(new IntSetting.Builder()
        .name("fov")
        .description("Угол обзора, в котором бьём цели (360 = вокруг, 90 = только то, что перед тобой).")
        .defaultValue(360)
        .min(1)
        .sliderRange(30, 360)
        .max(360)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Не бить друзей из списка друзей Meteor.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> hitReach = sgTargeting.add(new DoubleSetting.Builder()
        .name("hit-reach")
        .description("Макс. дистанция самого удара (от глаз до ближайшей точки хитбокса цели). У обычного сервера это 3 блока. Всё, что дальше, но в пределах range, модуль только наводит, но не бьёт.")
        .defaultValue(3.0)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<EntityAge> mobAgeFilter = sgTargeting.add(new EnumSetting.Builder<EntityAge>()
        .name("mob-age-filter")
        .description("Determines the age of the mobs to target (baby, adult, or both).")
        .defaultValue(EntityAge.Adult)
        .build()
    );

    private final Setting<Boolean> ignoreNamed = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-named")
        .description("Whether or not to attack mobs with a name.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignorePassive = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-passive")
        .description("Will only attack sometimes passive mobs if they are targeting you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreTamed = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-tamed")
        .description("Will avoid attacking mobs you tamed.")
        .defaultValue(false)
        .build()
    );

    // ---------- Timing ----------

    private final Setting<Boolean> pauseOnLag = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-lag")
        .description("Pauses if the server is lagging.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnUse = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Does not attack while using an item.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseOnCA = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-CA")
        .description("Does not attack while CA is placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> tpsSync = sgTiming.add(new BoolSetting.Builder()
        .name("TPS-sync")
        .description("Tries to sync attack delay with the server's TPS.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> customDelay = sgTiming.add(new BoolSetting.Builder()
        .name("custom-delay")
        .description("Use a custom delay instead of the vanilla cooldown.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> hitDelay = sgTiming.add(new IntSetting.Builder()
        .name("hit-delay")
        .description("How fast you hit the entity in ticks.")
        .defaultValue(11)
        .min(0)
        .sliderMax(60)
        .visible(customDelay::get)
        .build()
    );

    private final Setting<Integer> switchDelay = sgTiming.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("How many ticks to wait before hitting an entity after switching hotbar slots.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10)
        .build()
    );

    // ---------- Crits (добавлено) ----------

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

    private final Setting<Boolean> noCritsInWater = sgCrits.add(new BoolSetting.Builder()
        .name("no-crits-in-water")
        .description("В воде и под водой не ждать крита, а бить обычными ударами.")
        .defaultValue(true)
        .visible(onlyCrits::get)
        .build()
    );

    private final Setting<Integer> fallDelay = sgCrits.add(new IntSetting.Builder()
        .name("fall-delay")
        .description("Сколько тиков ждать после начала падения перед ударом (1 тик = 0.05 с).")
        .defaultValue(0)
        .min(0)
        .sliderMax(10)
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

    private final Setting<Boolean> debug = sgCrits.add(new BoolSetting.Builder()
        .name("debug")
        .description("Писать в чат состояние игрока, когда цель в радиусе.")
        .defaultValue(false)
        .build()
    );

    private final List<Entity> targets = new ArrayList<>();
    private int previousSlot = -1;
    private int trackedSlot = -1;
    private int slotBeforeForce = -1;
    private volatile boolean forcedSlotChange = false;
    private int hitTimer, switchTimer, debugTicks, fallingTicks;
    private boolean wasPathing = false;
    private boolean swapped = false;
    private boolean attacking = false;

    public KillAuraPlus() {
        super(AddonTemplate.CATEGORY, "kill-aura-plus", "Kill Aura с настройкой критов в падении.");
    }

    private static Set<EntityType<?>> allAttackable() {
        Set<EntityType<?>> set = new HashSet<>();
        for (EntityType<?> type : Registries.ENTITY_TYPE) {
            if (EntityUtils.isAttackable(type)) set.add(type);
        }
        return set;
    }

    @Override
    public void onActivate() {
        previousSlot = -1;
        swapped = false;
        forcedSlotChange = false;
        trackedSlot = mc.player != null ? mc.player.getInventory().selectedSlot : -1;
    }

    @Override
    public void onDeactivate() {
        targets.clear();
        stopAttacking();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        trackSelectedSlot();

        if (!mc.player.isOnGround() && mc.player.getVelocity().y < 0) fallingTicks++;
        else fallingTicks = 0;

        if (!mc.player.isAlive() || PlayerUtils.getGameMode() == GameMode.SPECTATOR) {
            stopAttacking();
            return;
        }
        if (pauseOnUse.get() && (mc.interactionManager.isBreakingBlock() || mc.player.isUsingItem())) {
            stopAttacking();
            return;
        }
        if (onlyOnClick.get() && !mc.options.attackKey.isPressed()) {
            stopAttacking();
            return;
        }
        if (TickRate.INSTANCE.getTimeSinceLastTick() >= 1f && pauseOnLag.get()) {
            stopAttacking();
            return;
        }
        if (pauseOnCA.get() && Modules.get().get(CrystalAura.class).isActive() && Modules.get().get(CrystalAura.class).kaTimer > 0) {
            stopAttacking();
            return;
        }

        if (onlyOnLook.get()) {
            Entity targeted = mc.targetedEntity;
            if (targeted == null || !entityCheck(targeted)) {
                stopAttacking();
                return;
            }
            targets.clear();
            targets.add(targeted);
        } else {
            targets.clear();
            TargetUtils.getList(targets, this::entityCheck, priority.get(), maxTargets.get());
        }

        if (targets.isEmpty()) {
            if (debug.get() && ++debugTicks >= 5) {
                debugTicks = 0;
                debugNoTarget();
            }
            stopAttacking();
            return;
        }

        Entity primary = targets.getFirst();

        if (autoSwitch.get()) {
            Predicate<ItemStack> predicate = switch (weapon.get()) {
                case Axe -> stack -> stack.getItem() instanceof AxeItem;
                case Sword -> stack -> stack.getItem() instanceof SwordItem;
                case Mace -> stack -> stack.getItem() instanceof MaceItem;
                case Trident -> stack -> stack.getItem() instanceof TridentItem;
                case All -> stack -> stack.getItem() instanceof AxeItem
                    || stack.getItem() instanceof SwordItem
                    || stack.getItem() instanceof MaceItem
                    || stack.getItem() instanceof TridentItem;
                default -> o -> true;
            };
            FindItemResult weaponResult = InvUtils.findInHotbar(predicate);

            if (shouldShieldBreak()) {
                FindItemResult axeResult = InvUtils.findInHotbar(itemStack -> itemStack.getItem() instanceof AxeItem);
                if (axeResult.found()) weaponResult = axeResult;
            }

            if (!swapped) {
                previousSlot = mc.player.getInventory().selectedSlot;
                swapped = true;
            }

            InvUtils.swap(weaponResult.slot(), false);
        }

        if (!itemInHand()) {
            stopAttacking();
            return;
        }

        attacking = true;

        if (pauseOnCombat.get() && PathManagers.get().isPathing() && !wasPathing) {
            PathManagers.get().pause();
            wasPathing = true;
        }

        if (debug.get() && ++debugTicks >= 5) {
            debugTicks = 0;
            info(String.format(
                "цель=%s дист=%.1f падение=%.2f vy=%.2f земля=%s спринт=%s кд=%.2f крит=%s тиков_падения=%d досяг=%.2f",
                primary.getType().getUntranslatedName(), (double) mc.player.distanceTo(primary),
                mc.player.fallDistance, mc.player.getVelocity().y,
                mc.player.isOnGround(), mc.player.isSprinting(),
                mc.player.getAttackCooldownProgress(0.5f), canCrit(), fallingTicks, reachDistance(primary)
            ));
        }

        if (reachDistance(primary) <= hitReach.get() && delayCheck() && critReady(primary)) {
            attackAll(primary);
        } else if (rotation.get() == RotationMode.Always) {
            // Серверный взгляд на цель каждый тик (экран не двигается)
            Rotations.rotate(aimYaw(primary), aimPitch(primary));
        }
    }

    // Сервер сам сменил слот: запоминаем слот, который выбрал игрок до этого
    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof UpdateSelectedSlotS2CPacket) {
            if (!forcedSlotChange) slotBeforeForce = trackedSlot;
            forcedSlotChange = true;
        }
    }

    // Смена колесиком/цифрой приходит без пакета от сервера, её просто принимаем.
    // Смену от сервера (forcedSlotChange) откатываем, если включено restore-slot.
    private void trackSelectedSlot() {
        int current = mc.player.getInventory().selectedSlot;

        if (forcedSlotChange) {
            forcedSlotChange = false;
            if (restoreSlot.get() && slotBeforeForce >= 0 && slotBeforeForce <= 8 && slotBeforeForce != current) {
                InvUtils.swap(slotBeforeForce, false);
                current = slotBeforeForce;
                // Сервер при смене предмета в руке сам сбросил твой кулдаун удара.
                // Клиент это не заметит (мы вернули слот до его тика), поэтому сбрасываем
                // кулдаун у себя, чтобы не бить недозаряженным ударом.
                mc.player.resetLastAttackedTicks();
            }
        }

        trackedSlot = current;
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (event.packet instanceof UpdateSelectedSlotC2SPacket) {
            switchTimer = switchDelay.get();
        }
    }

    private void stopAttacking() {
        if (!attacking) return;

        attacking = false;
        if (wasPathing) {
            PathManagers.get().resume();
            wasPathing = false;
        }
        if (swapBack.get() && swapped) {
            InvUtils.swap(previousSlot, false);
            swapped = false;
        }
    }

    private boolean shouldShieldBreak() {
        for (Entity target : targets) {
            if (target instanceof PlayerEntity player) {
                if (player.blockedByShield(mc.world.getDamageSources().playerAttack(mc.player))
                    && shieldMode.get() == ShieldMode.Break) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean entityCheck(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.cameraEntity)) return false;
        if ((entity instanceof LivingEntity livingEntity && livingEntity.isDead()) || !entity.isAlive()) return false;

        Box hitbox = entity.getBoundingBox();
        if (!PlayerUtils.isWithin(
            MathHelper.clamp(mc.player.getX(), hitbox.minX, hitbox.maxX),
            MathHelper.clamp(mc.player.getY(), hitbox.minY, hitbox.maxY),
            MathHelper.clamp(mc.player.getZ(), hitbox.minZ, hitbox.maxZ),
            range.get())) return false;

        if (!inFov(entity)) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreNamed.get() && entity.hasCustomName()) return false;
        if (!PlayerUtils.canSeeEntity(entity) && !PlayerUtils.isWithin(entity, wallsRange.get())) return false;

        if (ignoreTamed.get()) {
            if (entity instanceof Tameable tameable
                && tameable.getOwnerUuid() != null
                && tameable.getOwnerUuid().equals(mc.player.getUuid())) return false;
        }

        if (ignorePassive.get()) {
            if (entity instanceof EndermanEntity enderman && !enderman.isAngry()) return false;
            if (entity instanceof ZombifiedPiglinEntity piglin && !piglin.isAttacking()) return false;
            if (entity instanceof WolfEntity wolf && !wolf.isAttacking()) return false;
        }

        if (entity instanceof PlayerEntity player) {
            if (player.isCreative()) return false;
            if (ignoreFriends.get() && !Friends.get().shouldAttack(player)) return false;
            if (shieldMode.get() == ShieldMode.Ignore
                && player.blockedByShield(mc.world.getDamageSources().playerAttack(mc.player))) return false;
        }

        if (entity instanceof AnimalEntity animal) {
            return switch (mobAgeFilter.get()) {
                case Baby -> animal.isBaby();
                case Adult -> !animal.isBaby();
                case Both -> true;
            };
        }

        return true;
    }

    // Так же, как считает сервер: от глаз до ближайшей точки хитбокса цели
    private double reachDistance(Entity entity) {
        Box box = entity.getBoundingBox();
        Vec3d eye = mc.player.getEyePos();
        Vec3d nearest = new Vec3d(
            MathHelper.clamp(eye.x, box.minX, box.maxX),
            MathHelper.clamp(eye.y, box.minY, box.maxY),
            MathHelper.clamp(eye.z, box.minZ, box.maxZ)
        );
        return nearest.distanceTo(eye);
    }

    private Vec3d aimPos(Entity entity) {
        Box box = entity.getBoundingBox();
        Vec3d center = box.getCenter();

        switch (aimPoint.get()) {
            case Head -> {
                return new Vec3d(center.x, entity.getEyeY(), center.z);
            }
            case Body -> {
                return center;
            }
            case Feet -> {
                return new Vec3d(center.x, box.minY, center.z);
            }
            default -> {
                // Ближайшая к камере (глазам) точка хитбокса цели
                Vec3d eye = mc.player.getEyePos();
                Vec3d nearest = new Vec3d(
                    MathHelper.clamp(eye.x, box.minX, box.maxX),
                    MathHelper.clamp(eye.y, box.minY, box.maxY),
                    MathHelper.clamp(eye.z, box.minZ, box.maxZ)
                );
                // Если глаза внутри хитбокса, целимся в центр
                return nearest.squaredDistanceTo(eye) < 1.0E-6 ? center : nearest;
            }
        }
    }

    private double aimYaw(Entity entity) {
        return Rotations.getYaw(aimPos(entity));
    }

    private double aimPitch(Entity entity) {
        return Rotations.getPitch(aimPos(entity));
    }

    private void debugNoTarget() {
        Entity nearest = null;
        double best = 8;
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player || !(entity instanceof LivingEntity living) || !living.isAlive()) continue;
            if (!entities.get().contains(entity.getType())) continue;
            double dist = mc.player.distanceTo(entity);
            if (dist < best) {
                best = dist;
                nearest = entity;
            }
        }
        if (nearest == null) return;

        info(String.format(
            "нет цели: %s дист=%.1f в_радиусе=%s fov=%s вижу=%s",
            nearest.getType().getUntranslatedName(), best,
            PlayerUtils.isWithin(nearest, range.get()), inFov(nearest), PlayerUtils.canSeeEntity(nearest)
        ));
    }

    private boolean inFov(Entity entity) {
        if (fov.get() >= 360) return true;

        Vec3d look = mc.player.getRotationVec(1f);
        Vec3d eye = mc.player.getEyePos();
        Box box = entity.getBoundingBox();
        Vec3d center = box.getCenter();

        // Берём самый маленький угол до ног, центра и головы цели,
        // чтобы цель не выпадала из FOV, когда смотришь ей в голову
        double best = 180;
        for (double y : new double[]{box.minY, center.y, box.maxY}) {
            Vec3d to = new Vec3d(center.x, y, center.z).subtract(eye).normalize();
            double angle = Math.toDegrees(Math.acos(MathHelper.clamp(look.dotProduct(to), -1.0, 1.0)));
            best = Math.min(best, angle);
        }

        return best <= fov.get() / 2.0;
    }

    private boolean delayCheck() {
        if (switchTimer > 0) {
            switchTimer--;
            return false;
        }

        float delay = customDelay.get() ? hitDelay.get() : 0.5f;
        if (tpsSync.get()) delay /= (TickRate.INSTANCE.getTickRate() / 20);

        if (customDelay.get()) {
            if (hitTimer < delay) {
                hitTimer++;
                return false;
            } else {
                return true;
            }
        } else {
            return mc.player.getAttackCooldownProgress(delay) >= 1;
        }
    }

    // Условия ванильного крита: падение, не на земле, не в воде, не на лестнице,
    // нет слепоты, не на транспорте и не в спринте (спринт мы снимаем сами).
    private boolean canCrit() {
        return (mc.player.fallDistance > 0 || mc.player.getVelocity().y < -0.1)
            && !mc.player.isOnGround()
            && !mc.player.isClimbing()
            && !mc.player.isTouchingWater()
            && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS)
            && !mc.player.hasVehicle()
            && (stopSprint.get() || !mc.player.isSprinting());
    }

    private boolean critReady(Entity primary) {
        if (!onlyCrits.get()) return true;
        if (noCritsInWater.get() && (mc.player.isTouchingWater() || mc.player.isSubmergedInWater())) return true;
        if (canCrit() && fallingTicks >= fallDelay.get()) return true;
        return critFallback.get() && mc.player.distanceTo(primary) <= fallbackDistance.get();
    }

    private void attackAll(Entity primary) {
        if (silentAim.get() && rotation.get() != RotationMode.None) {
            // Как в Baritone: на время тика подменяем угол, пакет движения уходит с нужным
            // поворотом, и только после него бьём. Камера у тебя при этом не двигается.
            final List<Entity> snapshot = new ArrayList<>(targets);
            Rotations.rotate(aimYaw(primary), aimPitch(primary), () -> {
                for (Entity target : snapshot) {
                    if (reachDistance(target) <= hitReach.get()) hit(target);
                }
            });
        } else {
            for (Entity target : targets) {
                if (rotation.get() == RotationMode.OnHit) {
                    Rotations.rotate(aimYaw(target), aimPitch(target));
                }
                if (reachDistance(target) <= hitReach.get()) hit(target);
            }
        }
    }

    private void hit(Entity target) {
        boolean unsprint = onlyCrits.get() && stopSprint.get() && mc.player.isSprinting() && canCrit();
        if (unsprint) {
            mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        }

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);

        if (unsprint) {
            mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
        }

        hitTimer = 0;
    }

    private boolean itemInHand() {
        if (shouldShieldBreak()) return mc.player.getMainHandStack().getItem() instanceof AxeItem;

        return switch (weapon.get()) {
            case Axe -> mc.player.getMainHandStack().getItem() instanceof AxeItem;
            case Sword -> mc.player.getMainHandStack().getItem() instanceof SwordItem;
            case Mace -> mc.player.getMainHandStack().getItem() instanceof MaceItem;
            case Trident -> mc.player.getMainHandStack().getItem() instanceof TridentItem;
            case All -> mc.player.getMainHandStack().getItem() instanceof AxeItem
                || mc.player.getMainHandStack().getItem() instanceof SwordItem
                || mc.player.getMainHandStack().getItem() instanceof MaceItem
                || mc.player.getMainHandStack().getItem() instanceof TridentItem;
            default -> true;
        };
    }

    public Entity getTarget() {
        if (!targets.isEmpty()) return targets.getFirst();
        return null;
    }

    @Override
    public String getInfoString() {
        if (!targets.isEmpty()) return EntityUtils.getName(getTarget());
        return null;
    }
}
