package org.telegram.messenger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TTSManagerTest {
    @Test
    public void decodesAudioFromInteractionSteps() throws Exception {
        assertArrayEquals(new byte[]{1, 2, 3}, TTSManager.decodeAudioResponse(
                "{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"data\":\"AQID\"}]}]}"
        ));
    }

    @Test
    public void wrapsPcmAsPlayableWav() {
        byte[] wav = TTSManager.wrapPcmInWav(new byte[]{1, 2, 3, 4});
        assertEquals("RIFF", new String(wav, 0, 4));
        assertEquals("WAVE", new String(wav, 8, 4));
        assertEquals("data", new String(wav, 36, 4));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, java.util.Arrays.copyOfRange(wav, 44, wav.length));
    }
}
