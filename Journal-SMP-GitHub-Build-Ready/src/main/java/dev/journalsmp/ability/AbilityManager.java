package dev.journalsmp.ability;

import dev.journalsmp.JournalItems;
import dev.journalsmp.JournalKind;
import dev.journalsmp.data.JournalSavedData;
import dev.journalsmp.network.JumpscarePayload;
import dev.journalsmp.particle.JournalParticles;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AbilityManager {
    private static final long SECOND = 1_000L;
    private static final Map<UUID, PlayerState> PLAYER_STATES = new HashMap<>();
    private static final Map<UUID, TimedUltimate> ACTIVE_ULTIMATES = new HashMap<>();
    private static final Map<UUID, Long> ACTION_LOCKS = new HashMap<>();
    private static final Set<UUID> APPLYING_BONUS_DAMAGE = new HashSet<>();
    private static long serverTick;

    private AbilityManager() {
    }

    public static void activate(ServerPlayer player, JournalKind kind) {
        if (player.getOffhandItem().isEmpty() || JournalItems.kindOf(player.getOffhandItem()) != kind) {
            tell(player, "That journal must be in your off-hand.", ChatFormatting.RED);
            return;
        }
        if (isActionLocked(player)) {
            tell(player, "You cannot act while Bankruptcy is affecting you.", ChatFormatting.RED);
            return;
        }
        if (!player.isAlive() || player.isSpectator()) {
            tell(player, "You cannot activate this ultimate right now.", ChatFormatting.RED);
            return;
        }
        if (kind != JournalKind.EINO) {
            long remaining = cooldownRemaining(player, kind);
            if (remaining > 0L) {
                tell(player, kind.ultimateName() + " is on cooldown for " + formatDuration(remaining) + ".", ChatFormatting.RED);
                return;
            }
        }

        if (kind == JournalKind.EINO) {
            PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState())
                    .bankruptcyUntilMillis = System.currentTimeMillis() + 10L * SECOND;
            tell(player, "Bankruptcy armed: your next unblocked melee hit against a player disables their actions for 10 seconds.", ChatFormatting.GOLD);
            return;
        }

        ServerPlayer jumpscareTarget = null;
        if (kind == JournalKind.VANILTE) {
            jumpscareTarget = findJumpscareTarget(player);
            if (jumpscareTarget == null) {
                tell(player, "Look directly at an opposing player within 32 blocks to use Jumpscare.", ChatFormatting.RED);
                return;
            }
        }

        long now = System.currentTimeMillis();
        switch (kind) {
            case ARBY -> {
                addEffect(player, MobEffects.HEALTH_BOOST, 60 * 20, 2);
                addEffect(player, MobEffects.REGENERATION, 10 * 20, 2);
                ACTIVE_ULTIMATES.put(player.getUUID(), new TimedUltimate(kind, now + 60L * SECOND, now + 10L * SECOND));
                spawn(player, JournalParticles.ARBY, 22);
            }
            case JOURNAL -> {
                addEffect(player, MobEffects.SPEED, 60 * 20, 3);
                ACTIVE_ULTIMATES.put(player.getUUID(), new TimedUltimate(kind, now + 60L * SECOND, 0L));
                spawn(player, JournalParticles.JOURNAL, 18);
            }
            case DEXZYN -> {
                MobEffectInstance currentStrength = player.getEffect(MobEffects.STRENGTH);
                boolean grantStrength = currentStrength == null || currentStrength.getAmplifier() < 3;
                if (grantStrength) {
                    addEffect(player, MobEffects.STRENGTH, 20 * 20, 3);
                }
                ACTIVE_ULTIMATES.put(player.getUUID(),
                        new TimedUltimate(kind, now + 20L * SECOND, 0L, grantStrength));
                spawnBerserkerBurst(player);
            }
            case ACE -> {
                addEffect(player, MobEffects.RESISTANCE, 10 * 20, 4);
                ACTIVE_ULTIMATES.put(player.getUUID(), new TimedUltimate(kind, now + 10L * SECOND, 0L));
                spawn(player, JournalParticles.ACE, 18);
            }
            case VANILTE -> {
                ServerPlayNetworking.send(jumpscareTarget, new JumpscarePayload(10_000));
                spawn(player, JournalParticles.VANILTE, 18);
            }
            case NILLI -> {
                ACTIVE_ULTIMATES.put(player.getUUID(), new TimedUltimate(kind, now + 30L * SECOND, 0L));
                spawn(player, JournalParticles.NILLI, 20);
            }
            default -> throw new IllegalStateException("Unhandled journal: " + kind);
        }
        JournalSavedData.get(player.level().getServer()).setCooldown(player.getUUID(), kind,
                now + kind.cooldownMillis());
        player.playSound(SoundEvents.ENCHANTMENT_TABLE_USE, 0.8F, 1.15F);
        if (kind != JournalKind.NILLI) {
            announce(player, kind);
        }
        tell(player, kind.ultimateName() + " activated.", ChatFormatting.GREEN);
    }

    public static void tickServer(MinecraftServer server) {
        serverTick++;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            tickPlayer(player);
        }
    }

    private static void tickPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        PlayerState state = PLAYER_STATES.computeIfAbsent(id, ignored -> new PlayerState());
        JournalKind held = JournalItems.kindOf(player.getOffhandItem());

        if (state.lastHeld != held) {
            if (state.lastHeld != null) {
                clearPassiveEffects(player, state, state.lastHeld);
            }
            if (held != null) {
                cancelUltimateIfJournalChanged(player, held);
            } else {
                cancelUltimateIfJournalChanged(player, null);
            }
            state.lastHeld = held;
            state.villageResistanceApplied = false;
            state.saturationGranted = false;
            state.stillSinceTick = serverTick;
            state.lastIdlePulseTick = serverTick;
            if (held == JournalKind.VANILTE) {
                clearHostileTargets(player);
            }
        }

        if (held == JournalKind.EINO) {
            ensureEffect(player, MobEffects.HERO_OF_THE_VILLAGE, 2, 45);
            if (serverTick % 20 == 0) {
                boolean nearbyVillager = !player.level().getEntitiesOfClass(
                        Villager.class,
                        player.getBoundingBox().inflate(5.0),
                        villager -> villager.distanceToSqr(player) <= 25.0
                ).isEmpty();
                if (nearbyVillager) {
                    ensureEffect(player, MobEffects.RESISTANCE, 1, 45);
                    state.villageResistanceApplied = true;
                } else if (state.villageResistanceApplied) {
                    removeIfAmplifier(player, MobEffects.RESISTANCE, 1);
                    state.villageResistanceApplied = false;
                }
            }
        } else if (held == JournalKind.ARBY) {
            ensureEffect(player, MobEffects.HEALTH_BOOST, 0, 45);
        } else if (held == JournalKind.JOURNAL) {
            ensureEffect(player, MobEffects.SPEED, 0, 45);
        } else if (held == JournalKind.ACE) {
            ensureEffect(player, MobEffects.HASTE, 1, 45);
        } else if (held == JournalKind.VANILTE) {
            tickDreadwalker(player, state);
        }

        tickIdleResistance(player, state, held);
        tickTimedUltimate(player);
        tickBerserkerAura(player);
        if (held != null && !ACTIVE_ULTIMATES.containsKey(id)
                && serverTick - state.lastAmbientTick >= 80) {
            spawn(player, particleFor(held), 1);
            state.lastAmbientTick = serverTick;
        }

        if (state.bankruptcyUntilMillis > 0L && System.currentTimeMillis() >= state.bankruptcyUntilMillis) {
            state.bankruptcyUntilMillis = 0L;
            tell(player, "Bankruptcy expired without a successful hit.", ChatFormatting.GRAY);
        }
        Long lockUntil = ACTION_LOCKS.get(id);
        if (lockUntil != null && System.currentTimeMillis() >= lockUntil) {
            ACTION_LOCKS.remove(id);
            tell(player, "Bankruptcy has ended. You can act again.", ChatFormatting.GREEN);
        }
    }

    private static void tickIdleResistance(ServerPlayer player, PlayerState state, JournalKind held) {
        Vec3 current = player.position();
        boolean dimensionChanged = state.lastDimension != player.level().dimension();
        if (dimensionChanged || state.lastPosition == null || current.distanceToSqr(state.lastPosition) > 0.0004) {
            state.lastPosition = current;
            state.stillSinceTick = serverTick;
            state.lastIdlePulseTick = serverTick;
            state.saturationGranted = false;
            state.lastDimension = player.level().dimension();
            if (dimensionChanged) {
                cancelUltimateIfJournalChanged(player, held);
            }
            return;
        }

        if (held == JournalKind.DEXZYN && serverTick - state.stillSinceTick >= 100
                && serverTick - state.lastIdlePulseTick >= 100) {
            addEffect(player, MobEffects.RESISTANCE, 3 * 20, 0);
            state.lastIdlePulseTick = serverTick;
            spawn(player, JournalParticles.DEXZYN, 5);
        } else if (held == JournalKind.NILLI && !state.saturationGranted
                && serverTick - state.stillSinceTick >= 100) {
            addEffect(player, MobEffects.SATURATION, 3 * 20, 0);
            state.saturationGranted = true;
            spawn(player, JournalParticles.NILLI, 4);
        }
    }

    private static void tickDreadwalker(ServerPlayer player, PlayerState state) {
        long timeOfDay = Math.floorMod(player.level().getDayTime(), 24_000L);
        boolean nighttime = timeOfDay >= 13_000L && timeOfDay < 23_000L;
        MobEffectInstance current = player.getEffect(MobEffects.SPEED);

        if (!nighttime) {
            if (state.dreadwalkerApplied) {
                removeIfAmplifier(player, MobEffects.SPEED, 1);
                state.dreadwalkerApplied = false;
            }
            return;
        }

        if (current != null && current.getAmplifier() > 1) {
            state.dreadwalkerApplied = false;
            return;
        }
        if (current == null || current.getAmplifier() < 1) {
            addEffect(player, MobEffects.SPEED, 45, 1);
            state.dreadwalkerApplied = true;
        } else if (state.dreadwalkerApplied && current.getDuration() < 25) {
            addEffect(player, MobEffects.SPEED, 45, 1);
        }
    }

    private static void clearHostileTargets(ServerPlayer protectedPlayer) {
        AABB nearby = protectedPlayer.getBoundingBox().inflate(64.0);
        for (Mob mob : protectedPlayer.level().getEntitiesOfClass(Mob.class, nearby,
                entity -> entity instanceof Enemy && entity.getTarget() == protectedPlayer)) {
            mob.setTarget(null);
        }
    }

    private static ServerPlayer findJumpscareTarget(ServerPlayer caster) {
        Vec3 start = caster.getEyePosition();
        Vec3 end = start.add(caster.getLookAngle().scale(32.0));
        AABB searchArea = caster.getBoundingBox().expandTowards(end.subtract(start)).inflate(1.0);
        return caster.level().getEntitiesOfClass(ServerPlayer.class, searchArea,
                        candidate -> candidate != caster && candidate.isAlive() && !candidate.isSpectator()
                                && !caster.isAlliedTo(candidate) && caster.hasLineOfSight(candidate)
                                && candidate.getBoundingBox().inflate(0.3).clip(start, end).isPresent())
                .stream()
                .min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(caster)))
                .orElse(null);
    }

    private static void spawnBerserkerBurst(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, player.getX(), player.getY() + 1.0,
                    player.getZ(), 12, 0.45, 0.55, 0.45, 0.0);
            level.sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 1.0,
                    player.getZ(), 18, 0.4, 0.5, 0.4, 0.025);
        }
    }

    private static void tickBerserkerAura(ServerPlayer player) {
        TimedUltimate active = ACTIVE_ULTIMATES.get(player.getUUID());
        if (active == null || active.kind != JournalKind.DEXZYN || serverTick % 12 != 0
                || JournalItems.kindOf(player.getOffhandItem()) != JournalKind.DEXZYN) {
            return;
        }
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, player.getX(), player.getY() + 1.0,
                    player.getZ(), 1, 0.22, 0.35, 0.22, 0.0);
            level.sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 0.6,
                    player.getZ(), 1, 0.24, 0.4, 0.24, 0.006);
        }
    }

    private static SimpleParticleType particleFor(JournalKind kind) {
        return switch (kind) {
            case EINO -> JournalParticles.EINO;
            case ARBY -> JournalParticles.ARBY;
            case JOURNAL -> JournalParticles.JOURNAL;
            case DEXZYN -> JournalParticles.DEXZYN;
            case ACE -> JournalParticles.ACE;
            case VANILTE -> JournalParticles.VANILTE;
            case NILLI -> JournalParticles.NILLI;
        };
    }

    private static void tickTimedUltimate(ServerPlayer player) {
        TimedUltimate active = ACTIVE_ULTIMATES.get(player.getUUID());
        if (active == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (JournalItems.kindOf(player.getOffhandItem()) != active.kind || now >= active.expiresAtMillis) {
            removeUltimateEffects(player, active);
            ACTIVE_ULTIMATES.remove(player.getUUID());
            if (now < active.expiresAtMillis) {
                tell(player, active.kind.ultimateName() + " ended because the journal left your off-hand.", ChatFormatting.GRAY);
            }
            return;
        }
        if (active.kind == JournalKind.ARBY && active.secondaryExpiresAtMillis > 0L
                && now >= active.secondaryExpiresAtMillis) {
            removeIfAmplifier(player, MobEffects.REGENERATION, 2);
            active.secondaryExpiresAtMillis = 0L;
        }
    }

    private static void cancelUltimateIfJournalChanged(ServerPlayer player, JournalKind held) {
        TimedUltimate active = ACTIVE_ULTIMATES.get(player.getUUID());
        if (active != null && active.kind != held) {
            removeUltimateEffects(player, active);
            ACTIVE_ULTIMATES.remove(player.getUUID());
        }
        PlayerState state = PLAYER_STATES.get(player.getUUID());
        if (state != null && state.bankruptcyUntilMillis > 0L && held != JournalKind.EINO) {
            state.bankruptcyUntilMillis = 0L;
            tell(player, "Bankruptcy was cancelled because Eino's Playbook left your off-hand.", ChatFormatting.GRAY);
        }
    }

    private static void removeUltimateEffects(ServerPlayer player, TimedUltimate active) {
        switch (active.kind) {
            case ARBY -> {
                removeIfAmplifier(player, MobEffects.HEALTH_BOOST, 2);
                removeIfAmplifier(player, MobEffects.REGENERATION, 2);
            }
            case JOURNAL -> removeIfAmplifier(player, MobEffects.SPEED, 3);
            case DEXZYN -> {
                if (active.grantedStrength) {
                    removeIfAmplifier(player, MobEffects.STRENGTH, 3);
                }
            }
            case ACE -> removeIfAmplifier(player, MobEffects.RESISTANCE, 4);
            default -> {
            }
        }
    }

    private static void clearPassiveEffects(ServerPlayer player, PlayerState state, JournalKind kind) {
        switch (kind) {
            case EINO -> {
                removeIfAmplifier(player, MobEffects.HERO_OF_THE_VILLAGE, 2);
                removeIfAmplifier(player, MobEffects.RESISTANCE, 1);
            }
            case ARBY -> removeIfAmplifier(player, MobEffects.HEALTH_BOOST, 0);
            case JOURNAL -> removeIfAmplifier(player, MobEffects.SPEED, 0);
            case ACE -> removeIfAmplifier(player, MobEffects.HASTE, 1);
            case DEXZYN -> {
            }
            case VANILTE -> {
                if (state.dreadwalkerApplied) {
                    removeIfAmplifier(player, MobEffects.SPEED, 1);
                    state.dreadwalkerApplied = false;
                }
            }
            case NILLI -> {
                state.saturationGranted = false;
            }
        }
    }

    private static void ensureEffect(ServerPlayer player, Holder<MobEffect> effect, int amplifier, int duration) {
        MobEffectInstance current = player.getEffect(effect);
        if (current == null || current.getAmplifier() != amplifier || current.getDuration() < 25) {
            player.addEffect(new MobEffectInstance(effect, duration, amplifier, false, false, true));
        }
    }

    private static void addEffect(ServerPlayer player, Holder<MobEffect> effect, int duration, int amplifier) {
        player.addEffect(new MobEffectInstance(effect, duration, amplifier, false, true, true));
    }

    private static void removeIfAmplifier(ServerPlayer player, Holder<MobEffect> effect, int amplifier) {
        MobEffectInstance current = player.getEffect(effect);
        if (current != null && current.getAmplifier() == amplifier) {
            player.removeEffect(effect);
        }
    }

    public static boolean isActionLocked(ServerPlayer player) {
        Long until = ACTION_LOCKS.get(player.getUUID());
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            ACTION_LOCKS.remove(player.getUUID());
            return false;
        }
        return true;
    }

    public static void markAttack(ServerPlayer attacker, InteractionHand hand, Entity target) {
        PlayerState state = PLAYER_STATES.computeIfAbsent(attacker.getUUID(), ignored -> new PlayerState());
        state.attackTick = serverTick;
        state.attackTarget = target.getUUID();
        state.featherAttack = hand == InteractionHand.MAIN_HAND
                && attacker.getMainHandItem().is(Items.FEATHER)
                && JournalItems.kindOf(attacker.getOffhandItem()) == JournalKind.NILLI;
        state.criticalAxeAttack = hand == InteractionHand.MAIN_HAND
                && attacker.getMainHandItem().is(ItemTags.AXES)
                && isCriticalStrikeCandidate(attacker, target);
    }

    private static boolean isCriticalStrikeCandidate(ServerPlayer attacker, Entity target) {
        return target instanceof LivingEntity living && living.isAlive()
                && attacker.getAttackStrengthScale(0.5F) > 0.9F
                && attacker.fallDistance > 0.0F
                && !attacker.onGround()
                && !attacker.onClimbable()
                && !attacker.isInWater()
                && !attacker.isMobilityRestricted()
                && !attacker.isPassenger()
                && !attacker.isSprinting();
    }

    public static void handleSuccessfulHit(ServerPlayer attacker, LivingEntity target, DamageSource source,
                                           boolean blocked, float damageTaken) {
        PlayerState state = PLAYER_STATES.get(attacker.getUUID());
        boolean featherAttack = false;
        boolean criticalAxeAttack = false;
        if (state != null && state.attackTick == serverTick) {
            if (target.getUUID().equals(state.attackTarget)) {
                featherAttack = state.featherAttack;
                criticalAxeAttack = state.criticalAxeAttack;
            }
            state.attackTick = -1L;
            state.attackTarget = null;
            state.featherAttack = false;
            state.criticalAxeAttack = false;
        }
        if (APPLYING_BONUS_DAMAGE.contains(attacker.getUUID())) {
            return;
        }
        if (blocked || damageTaken <= 0.0F) {
            return;
        }
        JournalKind held = JournalItems.kindOf(attacker.getOffhandItem());
        if (held == JournalKind.NILLI && featherAttack && attacker.getMainHandItem().is(Items.FEATHER)) {
            target.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 10 * 20, 0, false, true, true));
            spawn(attacker, JournalParticles.NILLI, 4);
        }
        if (held == JournalKind.EINO && target instanceof ServerPlayer victim && state != null
                && state.bankruptcyUntilMillis > System.currentTimeMillis()) {
            state.bankruptcyUntilMillis = 0L;
            long until = System.currentTimeMillis() + 10L * SECOND;
            ACTION_LOCKS.put(victim.getUUID(), until);
            JournalSavedData.get(attacker.level().getServer()).setCooldown(attacker.getUUID(), JournalKind.EINO,
                    System.currentTimeMillis() + JournalKind.EINO.cooldownMillis());
            victim.displayClientMessage(Component.literal("Bankruptcy: your actions are disabled for 10 seconds.")
                    .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), true);
            tell(attacker, "Bankruptcy disabled " + victim.getName().getString() + " for 10 seconds.", ChatFormatting.GREEN);
            announce(attacker, JournalKind.EINO);
            spawn(attacker, JournalParticles.EINO, 18);
        }

        if (held == JournalKind.JOURNAL && attacker.getMainHandItem().is(net.minecraft.tags.ItemTags.HOES)
                && target instanceof ServerPlayer victim) {
            victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 5 * 20, 0, false, true, true));
            spawn(victim, JournalParticles.JOURNAL, 6);
        }

        TimedUltimate active = ACTIVE_ULTIMATES.get(attacker.getUUID());
        if (held == JournalKind.NILLI && active != null && active.kind == JournalKind.NILLI
                && active.expiresAtMillis > System.currentTimeMillis()
                && criticalAxeAttack && source.getDirectEntity() == attacker) {
            applyIllegalEnchantments(attacker, target);
        }
    }

    private static void applyIllegalEnchantments(ServerPlayer attacker, LivingEntity target) {
        if (!target.isAlive() || target == attacker || !(target.level() instanceof ServerLevel level)) {
            return;
        }
        DamageSource source = attacker.damageSources().playerAttack(attacker);
        if (target.isInvulnerableTo(level, source)) {
            return;
        }

        LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
        if (lightning != null) {
            lightning.setPos(target.getX(), target.getY(), target.getZ());
            lightning.setVisualOnly(true);
            level.addFreshEntity(lightning);
        }

        APPLYING_BONUS_DAMAGE.add(attacker.getUUID());
        try {
            target.hurtServer(level, source, 4.0F);
        } finally {
            APPLYING_BONUS_DAMAGE.remove(attacker.getUUID());
        }
    }

    public static long cooldownRemaining(ServerPlayer player, JournalKind kind) {
        long until = JournalSavedData.get(player.level().getServer()).cooldownUntil(player.getUUID(), kind);
        return Math.max(0L, until - System.currentTimeMillis());
    }

    public static void showCooldown(ServerPlayer player, JournalKind kind) {
        long remaining = cooldownRemaining(player, kind);
        if (remaining <= 0) {
            tell(player, kind.ultimateName() + " is ready.", ChatFormatting.GREEN);
        } else {
            tell(player, kind.ultimateName() + " cooldown: " + formatDuration(remaining) + ".", ChatFormatting.YELLOW);
        }
    }

    public static boolean claimJournal(ServerPlayer player, JournalKind kind) {
        JournalSavedData data = JournalSavedData.get(player.level().getServer());
        if (!data.claim(kind, player.getUUID(), player.getName().getString())) {
            player.displayClientMessage(Component.translatable("ability.journalsmp.claimed")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        return true;
    }

    public static String passiveOne(JournalKind kind) {
        return switch (kind) {
            case EINO -> "Master Trader — Hero of the Village III";
            case ARBY -> "Big Appetite — Health Boost I";
            case JOURNAL -> "Speedrunner — Speed I";
            case DEXZYN -> "Idle Protection — still for 5 seconds to gain Resistance I for 3 seconds";
            case ACE -> "Master Builder — Haste II";
            case VANILTE -> "Dreadwalker — Speed II at night";
            case NILLI -> "Featherweight — feather hits inflict Slow Falling for 10 seconds";
        };
    }

    public static String passiveTwo(JournalKind kind) {
        return switch (kind) {
            case EINO -> "Village Guardian — Resistance II while a villager is within 5 blocks";
            case ARBY -> "Efficient Digestion — 50% less food exhaustion";
            case JOURNAL -> "Blindside — hoe hits on players inflict Blindness for 5 seconds";
            case DEXZYN -> "Sword Looting — 50% chance for one extra item per mob-drop stack on sword kills; disabled when the sword has Looting";
            case ACE -> "Extended Reach — place blocks up to one block farther away; containers, mining, and entity reach are unchanged";
            case VANILTE -> "Predator's Instinct — hostile mobs ignore you";
            case NILLI -> "Cosmic Calm — after 5 seconds still, gain Saturation once until you move";
        };
    }

    public static String cooldownText(JournalKind kind) {
        return formatDuration(kind.cooldownMillis());
    }

    private static String formatDuration(long milliseconds) {
        long totalSeconds = Math.max(0L, (milliseconds + 999L) / 1_000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes > 0 ? minutes + "m " + String.format("%02d", seconds) + "s" : seconds + "s";
    }

    private static void tell(ServerPlayer player, String message, ChatFormatting color) {
        player.displayClientMessage(Component.literal(message).withStyle(color), true);
    }

    private static void announce(ServerPlayer player, JournalKind kind) {
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            Component announcement = kind == JournalKind.NILLI
                    ? Component.literal("⚡ [ADMIN] PvP balance has been disabled.")
                            .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                    : Component.literal(player.getName().getString() + " activated " + kind.ultimateName() + "!")
                            .withStyle(ChatFormatting.GOLD);
            server.getPlayerList().broadcastSystemMessage(
                    announcement,
                    false);
        }
    }

    private static void spawn(ServerPlayer player, SimpleParticleType particle, int count) {
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(particle, player.getX(), player.getY() + 1.0, player.getZ(),
                    count, 0.45, 0.55, 0.45, 0.025);
        }
    }

    public static void cleanup(ServerPlayer player) {
        PLAYER_STATES.remove(player.getUUID());
        ACTION_LOCKS.remove(player.getUUID());
        TimedUltimate active = ACTIVE_ULTIMATES.remove(player.getUUID());
        if (active != null) {
            removeUltimateEffects(player, active);
        }
    }

    public static boolean isJournalItem(ItemStack stack, JournalKind kind) {
        return JournalItems.kindOf(stack) == kind;
    }

    private static final class PlayerState {
        private JournalKind lastHeld;
        private Vec3 lastPosition;
        private net.minecraft.resources.ResourceKey<Level> lastDimension;
        private long stillSinceTick;
        private long lastIdlePulseTick;
        private long lastAmbientTick;
        private long attackTick = -1L;
        private UUID attackTarget;
        private long bankruptcyUntilMillis;
        private boolean villageResistanceApplied;
        private boolean saturationGranted;
        private boolean dreadwalkerApplied;
        private boolean featherAttack;
        private boolean criticalAxeAttack;
    }

    private static final class TimedUltimate {
        private final JournalKind kind;
        private final long expiresAtMillis;
        private long secondaryExpiresAtMillis;
        private final boolean grantedStrength;

        private TimedUltimate(JournalKind kind, long expiresAtMillis, long secondaryExpiresAtMillis) {
            this(kind, expiresAtMillis, secondaryExpiresAtMillis, false);
        }

        private TimedUltimate(JournalKind kind, long expiresAtMillis, long secondaryExpiresAtMillis,
                              boolean grantedStrength) {
            this.kind = kind;
            this.expiresAtMillis = expiresAtMillis;
            this.secondaryExpiresAtMillis = secondaryExpiresAtMillis;
            this.grantedStrength = grantedStrength;
        }
    }
}
