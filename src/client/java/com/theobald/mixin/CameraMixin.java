package com.theobald.mixin;

import com.theobald.FightCameraClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
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

    /** Own-fight mode: place the camera beside the fight without touching the player's own yaw/pitch. */
    @Inject(method = "update", at = @At("TAIL"))
    private void pvpcam$placeCamera(BlockView area, Entity focusedEntity, boolean thirdPerson,
                                    boolean inverseView, float tickDelta, CallbackInfo ci) {
        if (!FightCameraClient.active || !FightCameraClient.selfMode) return;
        Vec3d pos = FightCameraClient.getInterpolated(tickDelta);
        float yaw = FightCameraClient.lerpAngle(FightCameraClient.lastTargetYaw,
                FightCameraClient.currentTargetYaw, tickDelta);
        this.setRotation(yaw, FightCameraClient.computePitch(pos));
        this.setPos(pos);
    }
}
