package com.jarvis.jarvisapk;

import com.sun.speech.freetts.Voice;
import com.sun.speech.freetts.VoiceManager;

public class TextToSpeech {
    private final Voice voice;

    public TextToSpeech() {
        System.setProperty("freetts.voices",
                "com.sun.speech.freetts.en.us.cmu_us_kal.KevinVoiceDirectory");
        VoiceManager vm = VoiceManager.getInstance();
        voice = vm.getVoice("kevin16");
        if (voice == null) {
            throw new IllegalStateException("Cannot find voice: kevin16");
        }
        voice.allocate();
    }

    public void speak(String text) {
        if (voice != null) {
            voice.speak(text);
        }
    }

    public void deallocate() {
        voice.deallocate();
    }
}
