package org.telegram.messenger;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class TTSManagerTest {
    @Test
    public void decodesAudioFromInteractionSteps() throws Exception {
        assertArrayEquals(new byte[]{1, 2, 3}, TTSManager.decodeAudioResponse(
                "{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"data\":\"AQID\"}]}]}"
        ));
    }
}
