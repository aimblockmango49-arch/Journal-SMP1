package dev.journalsmp.mixin;

import dev.journalsmp.JournalItems;
import dev.journalsmp.JournalKind;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobTargetMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void journalsmp$ignoreVanilteHolder(@Nullable LivingEntity target, CallbackInfo callback) {
        Mob mob = (Mob) (Object) this;
        if (mob instanceof Enemy && target instanceof ServerPlayer player
                && JournalItems.kindOf(player.getOffhandItem()) == JournalKind.VANILTE) {
            callback.cancel();
        }
    }
}
