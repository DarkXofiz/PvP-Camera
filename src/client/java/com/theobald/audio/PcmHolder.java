package com.theobald.audio;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

/** Implemented (via mixin) by StaticSound so the recorder can read the decoded PCM data. */
public interface PcmHolder {
    ByteBuffer pvpcam$getPcm();

    AudioFormat pvpcam$getFormat();
}
