package com.jarvis.jarvisapk;

import edu.cmu.sphinx.api.Configuration;
import edu.cmu.sphinx.api.LiveSpeechRecognizer;
import edu.cmu.sphinx.api.SpeechResult;

public class SpeechRecognizer {

    private LiveSpeechRecognizer recognizer;
    private volatile String lastResult = null;
    private volatile boolean listening = false;
    private Thread recognitionThread;

    public SpeechRecognizer() {
        try {
            Configuration configuration = new Configuration();
            configuration.setAcousticModelPath("resource:/edu/cmu/sphinx/models/en-us/en-us");
            configuration.setDictionaryPath("resource:/edu/cmu/sphinx/models/en-us/cmudict-en-us.dict");
            configuration.setLanguageModelPath("resource:/edu/cmu/sphinx/models/en-us/en-us.lm.bin");

            recognizer = new LiveSpeechRecognizer(configuration);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Start listening asynchronously, only if not already listening
    public synchronized void startListening() {
        if (recognizer == null || listening) {
            return;
        }
        listening = true;
        lastResult = null;

        recognitionThread = new Thread(() -> {
            try {
                recognizer.startRecognition(true);
                SpeechResult result = recognizer.getResult(); // Waits for speech phrase complete
                if (result != null) {
                    lastResult = result.getHypothesis();
                }
            } catch (IllegalStateException ise) {
                // Recognizer was in wrong state; can log or ignore if expected
                System.err.println("Recognizer state error in startListening: " + ise.getMessage());
            } catch (Exception e) {
                e.printStackTrace();
            }
            // Do NOT call stopRecognition here; that is done in stopListening()
        });
        recognitionThread.setDaemon(true);
        recognitionThread.start();
    }

    // Stop listening, wait for thread to finish, returns recognized text
    public synchronized String stopListening() {
        if (recognizer == null || !listening) {
            return null;
        }
        listening = false;
        try {
            // Stop recognition only if currently recognizing
            recognizer.stopRecognition();

            if (recognitionThread != null && recognitionThread.isAlive()) {
                recognitionThread.join(1000); // Wait for thread to finish or timeout after 1s
            }
        } catch (IllegalStateException ise) {
            // Possibly already stopped, ignore
            System.err.println("Recognizer state error in stopListening: " + ise.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return lastResult;
    }

    // One-shot recognition for your legacy Speak button or simple use
    public String recognizeSpeech() {
        if (recognizer == null) return null;
        try {
            recognizer.startRecognition(true);
            SpeechResult result = recognizer.getResult();
            recognizer.stopRecognition();

            if (result != null) {
                return result.getHypothesis();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // Optional status check
    public boolean isListening() {
        return listening;
    }
}
