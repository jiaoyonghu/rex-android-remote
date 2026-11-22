package com.rex.remote;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.tensorflow.lite.Interpreter;

/**
 * Frozen openWakeWord v0.5.1 TFLite MEL + Google Speech Embedding frontend.
 * This avoids loading a second incompatible ONNX Runtime JNI into sherpa's app.
 * Not a speaker-verification model and not an end-to-end trained wakeword head.
 * v1.5: invoked only by the isolated neural worker; buffers are reused.
 */
public final class OpenWakeWordFeatures implements Closeable {
    public static final int DIM = 16 * 96;
    private static final int RATE = 16000;
    private static final int CONTEXT = 48000;
    private static final int MEL_BINS = 32;
    private static final int MEL_WINDOW = 76;
    private static final int STEPS = 16;
    private final Interpreter mel;
    private final Interpreter speech;
    private final ByteBuffer audioBuffer,spectrumBuffer,patchBuffer,embeddingBuffer;
    private final float[] flatSpectrum;

    public OpenWakeWordFeatures(Context context) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(1);
        Interpreter m = null, s = null;
        try {
            m = new Interpreter(read(context, "openwakeword/melspectrogram.tflite"), options);
            s = new Interpreter(read(context, "openwakeword/embedding_model.tflite"), options);
            m.resizeInput(0, new int[]{1, CONTEXT}, true);
            m.allocateTensors();
            // The official openWakeWord embedding model supports batched inputs.
            s.resizeInput(0, new int[]{STEPS, MEL_WINDOW, MEL_BINS, 1}, true);
            s.allocateTensors();
            if (m.getOutputTensor(0).numBytes() <= 0 ||
                s.getOutputTensor(0).numElements() < DIM) {
                throw new IllegalStateException("Unexpected openWakeWord TFLite model output shape");
            }
            mel = m;
            speech = s;
            audioBuffer=floats(CONTEXT);
            spectrumBuffer=ByteBuffer.allocateDirect(m.getOutputTensor(0).numBytes())
                .order(ByteOrder.nativeOrder());
            embeddingBuffer=ByteBuffer.allocateDirect(s.getOutputTensor(0).numBytes())
                .order(ByteOrder.nativeOrder());
            patchBuffer=floats(STEPS*MEL_WINDOW*MEL_BINS);
            flatSpectrum=new float[spectrumBuffer.capacity()/4];
        } catch (RuntimeException | LinkageError ex) {
            if (s != null) s.close();
            if (m != null) m.close();
            throw new IOException("openWakeWord TFLite initialization failed: " + ex, ex);
        }
    }

    private static ByteBuffer read(Context context, String asset) throws IOException {
        try (java.io.InputStream input = context.getAssets().open(asset)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = input.read(chunk)) != -1) {
                if (bytes.size() + n > 10_000_000) throw new IOException("Model too large: " + asset);
                bytes.write(chunk, 0, n);
            }
            byte[] raw = bytes.toByteArray();
            if (raw.length < 8 || raw[4] != 'T' || raw[5] != 'F' || raw[6] != 'L' || raw[7] != '3') {
                throw new IOException("Invalid TFLite asset: " + asset);
            }
            ByteBuffer direct = ByteBuffer.allocateDirect(raw.length).order(ByteOrder.nativeOrder());
            direct.put(raw);
            direct.rewind();
            return direct;
        }
    }

    private static ByteBuffer floats(int count) {
        return ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder());
    }

    public float[] embed(short[] pcm) {
        if (pcm == null || pcm.length < 3200 || pcm.length > RATE * 5) return null;
        try {
            ByteBuffer audio = audioBuffer;
            audio.clear();
            int used = Math.min(pcm.length, CONTEXT - 3200);
            int start = (CONTEXT - used) / 2;
            int from = Math.max(0, pcm.length - used);
            for (int i = 0; i < CONTEXT; i++) {
                float sample = (i >= start && i < start + used) ? pcm[from + i - start] : 0f;
                audio.putFloat(sample);
            }
            audio.rewind();
            ByteBuffer spectrum=spectrumBuffer;
            spectrum.clear();
            mel.run(audio,spectrum);
            spectrum.rewind();
            int floats = flatSpectrum.length;
            if (floats % MEL_BINS != 0) return null;
            int frames = floats / MEL_BINS;
            if (frames < MEL_WINDOW) return null;
            for (int i = 0; i < floats; i++) flatSpectrum[i] = spectrum.getFloat() / 10f + 2f;

            ByteBuffer patches=patchBuffer;
            patches.clear();
            int maxStart = frames - MEL_WINDOW;
            for (int step = 0; step < STEPS; step++) {
                int offset = Math.round(step * (maxStart / (float)(STEPS - 1)));
                for (int i = 0; i < MEL_WINDOW * MEL_BINS; i++) {
                    patches.putFloat(flatSpectrum[offset * MEL_BINS + i]);
                }
            }
            patches.rewind();
            ByteBuffer embeddings=embeddingBuffer;
            embeddings.clear();
            speech.run(patches, embeddings);
            embeddings.rewind();
            if (embeddings.remaining() / 4 < DIM) return null;
            float[] vector = new float[DIM];
            for (int i = 0; i < DIM; i++) vector[i] = embeddings.getFloat();
            return PersonalNeuralStore.unit(vector);
        } catch (RuntimeException | LinkageError ex) {
            Log.e("RExPersonalNeural", "TFLite personal frontend inference failed", ex);
            return null;
        }
    }

    @Override public void close() {
        try { speech.close(); } catch (RuntimeException ignored) { }
        try { mel.close(); } catch (RuntimeException ignored) { }
    }
}
