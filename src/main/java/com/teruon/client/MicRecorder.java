package com.teruon.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;

/** Пишет микрофон, сам останавливается после паузы в речи (или по повторному нажатию M). */
public final class MicRecorder {
    public interface Callback {
        void onDone(byte[] wav);
        void onError(String message);
    }

    private static final double SPEECH_RMS = 600;   // порог "это речь"; подкрути, если микрофон тихий/шумный
    private static final int SILENCE_CHUNKS = 12;   // 12 * 0.1с = 1.2с тишины -> конец
    private static final long WAIT_FOR_SPEECH_MS = 8000;
    private static final long MAX_MS = 20000;

    private final Callback cb;
    private volatile boolean stopRequested;

    public MicRecorder(Callback cb) {
        this.cb = cb;
    }

    public void start() {
        Thread t = new Thread(this::run, "Teruon-Mic");
        t.setDaemon(true);
        t.start();
    }

    public void requestStop() {
        stopRequested = true;
    }

    private void run() {
        AudioFormat fmt = new AudioFormat(16000f, 16, 1, true, false);
        TargetDataLine line = null;
        try {
            line = AudioSystem.getTargetDataLine(fmt);
            line.open(fmt);
            line.start();

            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            byte[] buf = new byte[3200]; // 100 мс
            long start = System.currentTimeMillis();
            boolean speech = false;
            int silent = 0;

            while (!stopRequested) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 0) continue;
                pcm.write(buf, 0, n);
                if (rms(buf, n) > SPEECH_RMS) { speech = true; silent = 0; } else { silent++; }

                long el = System.currentTimeMillis() - start;
                if (speech && silent >= SILENCE_CHUNKS) break;
                if (!speech && el > WAIT_FOR_SPEECH_MS) break;
                if (el > MAX_MS) break;
            }
            line.stop();
            if (!speech) {
                cb.onError("Я ничего не услышал.");
                return;
            }
            byte[] data = pcm.toByteArray();
            AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), fmt, data.length / 2);
            ByteArrayOutputStream wav = new ByteArrayOutputStream();
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wav);
            cb.onDone(wav.toByteArray());
        } catch (Exception e) {
            cb.onError("Не удалось открыть микрофон: " + e.getMessage());
        } finally {
            if (line != null) line.close();
        }
    }

    private static double rms(byte[] b, int n) {
        double sum = 0;
        int cnt = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            short s = (short) ((b[i + 1] << 8) | (b[i] & 0xFF));
            sum += (double) s * s;
            cnt++;
        }
        return cnt == 0 ? 0 : Math.sqrt(sum / cnt);
    }
}
