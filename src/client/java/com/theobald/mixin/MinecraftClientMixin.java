package com.theobald.mixin;

import com.theobald.recording.VideoRecorder;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void pvpcam$beginFrame(boolean tick, CallbackInfo ci) {
        VideoRecorder.beginFrame();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void pvpcam$endFrame(boolean tick, CallbackInfo ci) {
        VideoRecorder.endFrame();
    }
}
