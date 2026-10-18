package com.theobald.mixin;

import com.theobald.FightCameraClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean thirdPerson;

    @Shadow
    protected abstract void setPos(Vec3d pos);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("HEAD"))
    private void disableThirdPerson(CallbackInfo ci) {
        this.thirdPerson = false; // Force third person to be disabled
    }

    /** Follow-me mode: use the vanilla chase camera, only with our own distance behind the player. */
    @ModifyConstant(method = "update", constant = @Constant(doubleValue = 4.0))
    private double pvpcam$followDistance(double original) {
        if (FightCameraClient.active && FightCameraClient.selfMode) {
            return FightCameraClient.followDistance();
        }
        return original;
    }
}
