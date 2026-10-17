package com.theobald.mixin;

import com.theobald.audio.PcmHolder;
import net.minecraft.client.sound.StaticSound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

/** Keeps a reference to each sound's decoded PCM so it can be re-mixed into recordings. */
@Mixin(StaticSound.class)
public abstract class StaticSoundMixin implements PcmHolder {
    @Unique
    private ByteBuffer pvpcam$pcm;
    @Unique
    private AudioFormat pvpcam$format;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void pvpcam$keepPcm(ByteBuffer sample, AudioFormat format, CallbackInfo ci) {
        this.pvpcam$pcm = sample;
        this.pvpcam$format = format;
    }

    @Override
    public ByteBuffer pvpcam$getPcm() {
        return pvpcam$pcm;
    }

    @Override
    public AudioFormat pvpcam$getFormat() {
        return pvpcam$format;
    }
}
