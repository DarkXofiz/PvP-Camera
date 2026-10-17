package com.theobald.mixin;

import com.theobald.FightCameraClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlayerEntity.class, priority = 1101)
public abstract class PlayerEntityMixin {

    @Inject(method = "tickMovement", at = @At("HEAD"), cancellable = true)
    private void disableMovement(CallbackInfo ci) {
        if (FightCameraClient.active && (Object) this == MinecraftClient.getInstance().player) {
            ci.cancel();
        }
    }
}
