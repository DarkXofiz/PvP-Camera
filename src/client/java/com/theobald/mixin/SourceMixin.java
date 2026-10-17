package com.theobald.mixin;

import com.theobald.audio.AudioTap;
import com.theobald.audio.AudioVoice;
import com.theobald.audio.PcmHolder;
import net.minecraft.client.sound.Source;
import net.minecraft.client.sound.StaticSound;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors what the game tells OpenAL about each sound so the recorder can mix the same sounds. */
@Mixin(Source.class)
public abstract class SourceMixin {
    @Unique
    private AudioVoice pvpcam$voice;

    @Unique
    private AudioVoice pvpcam$v() {
        if (pvpcam$voice == null) pvpcam$voice = new AudioVoice();
        return pvpcam$voice;
    }

    @Inject(method = "setPitch", at = @At("HEAD"))
    private void pvpcam$pitch(float pitch, CallbackInfo ci) {
        pvpcam$v().pitch = pitch;
    }

    @Inject(method = "setVolume", at = @At("HEAD"))
    private void pvpcam$volume(float volume, CallbackInfo ci) {
        pvpcam$v().volume = volume;
    }

    @Inject(method = "setLooping", at = @At("HEAD"))
    private void pvpcam$looping(boolean looping, CallbackInfo ci) {
        pvpcam$v().looping = looping;
    }

    @Inject(method = "setRelative", at = @At("HEAD"))
    private void pvpcam$relative(boolean relative, CallbackInfo ci) {
        pvpcam$v().relative = relative;
    }

    @Inject(method = "disableAttenuation", at = @At("HEAD"))
    private void pvpcam$noAttenuation(CallbackInfo ci) {
        pvpcam$v().attenuate = false;
    }

    @Inject(method = "setAttenuation", at = @At("HEAD"))
    private void pvpcam$attenuation(float attenuation, CallbackInfo ci) {
        AudioVoice v = pvpcam$v();
        v.attenuate = true;
        v.maxDistance = attenuation;
    }

    @Inject(method = "setPosition", at = @At("HEAD"))
    private void pvpcam$position(Vec3d pos, CallbackInfo ci) {
        pvpcam$v().pos = pos;
    }

    @Inject(method = "setBuffer", at = @At("HEAD"))
    private void pvpcam$buffer(StaticSound sound, CallbackInfo ci) {
        if (sound instanceof PcmHolder h) {
            pvpcam$v().setPcm(h.pvpcam$getPcm(), h.pvpcam$getFormat());
        }
    }

    @Inject(method = "play", at = @At("HEAD"))
    private void pvpcam$play(CallbackInfo ci) {
        if (AudioTap.isActive()) AudioTap.onPlay(pvpcam$v());
    }

    @Inject(method = "pause", at = @At("HEAD"))
    private void pvpcam$pause(CallbackInfo ci) {
        pvpcam$v().paused = true;
    }

    @Inject(method = "resume", at = @At("HEAD"))
    private void pvpcam$resume(CallbackInfo ci) {
        pvpcam$v().paused = false;
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void pvpcam$stop(CallbackInfo ci) {
        pvpcam$v().stopped = true;
    }
}
