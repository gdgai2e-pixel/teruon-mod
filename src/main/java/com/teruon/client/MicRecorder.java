package com.teruon.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/** Пишет микрофон, пока держишь V. */
public final class MicRecorder {
    public interface Callback {
        void onDone(byte[] wav);
        void onError(String message);
    }

    private static final double SPEECH_RMS = 150;  // ниже — считаем тишиной (подкрути, если микрофон очень тихий)
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

    /** Подбираем формат, который микрофон реально умеет (16 кГц, иначе 44.1/48/22 кГц). */
    private static AudioFormat pickFormat() {
        for (float rate : new float[] {16000f, 44100f, 48000f, 22050f}) {
            AudioFormat f = new AudioFormat(rate, 16, 1, true, false);
            if (AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, f))) return f;
        }
        return null;
    }

    private void run() {
        TargetDataLine line = null;
        try {
            AudioFormat fmt = pickFormat();
            if (fmt == null) {
                cb.onError("§cНе найден микрофон, который умеет писать звук. Проверь /teruon mic");
                return;
            }
            line = AudioSystem.getTargetDataLine(fmt);
            line.open(fmt);
            line.start();

            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            byte[] buf = new byte[((int) fmt.getSampleRate() / 10) * 2]; // 100 мс
            long start = System.currentTimeMillis();
            double max = 0;

            while (!stopRequested) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 0) continue;
                pcm.write(buf, 0, n);
                max = Math.max(max, rms(buf, n));
                if (System.currentTimeMillis() - start > MAX_MS) break;
            }
            line.stop();

            if (System.currentTimeMillis() - start < 500) {
                cb.onError("Слишком коротко — держи V, пока говоришь.");
                return;
            }
            if (max < SPEECH_RMS) {
                cb.onError("Я ничего не услышал (громкость " + (int) max + "). Проверь микрофон: /teruon mic");
                return;
            }
            byte[] data = pcm.toByteArray();
            AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), fmt, data.length / 2);
            ByteArrayOutputStream wav = new ByteArrayOutputStream();
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wav);
            cb.onDone(wav.toByteArray());
        } catch (Throwable e) {
            cb.onError("§cНе удалось записать звук: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            if (line != null) line.close();
        }
    }

    /** /teruon mic — показывает, какие микрофоны видит Java и какая у них громкость (3 секунды записи). */
    public static List<String> diagnose() {
        List<String> out = new ArrayList<>();
        try {
            int count = 0;
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (AudioSystem.getMixer(mi).getTargetLineInfo().length > 0) {
                    out.add("Устройство записи: " + mi.getName());
                    count++;
                }
            }
            if (count == 0) {
                out.add("§cJava не видит ни одного микрофона. Проверь Windows: Параметры -> Конфиденциальность -> Микрофон.");
                return out;
            }
            AudioFormat fmt = pickFormat();
            if (fmt == null) {
                out.add("§cНет подходящего формата записи.");
                return out;
            }
            out.add("Формат записи: " + (int) fmt.getSampleRate() + " Гц");
            TargetDataLine line = AudioSystem.getTargetDataLine(fmt);
            line.open(fmt);
            line.start();
            byte[] buf = new byte[((int) fmt.getSampleRate() / 10) * 2];
            double max = 0;
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end) {
                int n = line.read(buf, 0, buf.length);
                if (n > 0) max = Math.max(max, rms(buf, n));
            }
            line.stop();
            line.close();
            out.add("Максимальная громкость за 3 сек: " + (int) max
                    + (max < SPEECH_RMS ? " §c(тишина — микрофон не слышит или выключен)" : " §a(микрофон работает)"));
        } catch (Throwable e) {
            out.add("§cОшибка теста: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
        return out;
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
