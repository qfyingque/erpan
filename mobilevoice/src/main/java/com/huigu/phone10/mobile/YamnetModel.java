package com.huigu.phone10.mobile;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.FloatBuffer;
import java.util.Collections;

/** CPU session shared by the Android integration and the desktop compatibility probe. */
public final class YamnetModel implements AutoCloseable {
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;

    public YamnetModel(byte[] model) throws OrtException {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            session = environment.createSession(model, options);
        }
    }

    public float[] scores(float[] waveform) throws OrtException {
        if (waveform.length != 15600) throw new IllegalArgumentException("Expected 975ms at 16kHz");
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(waveform), new long[]{waveform.length});
             OrtSession.Result result = session.run(Collections.singletonMap("waveform", input),
                     Collections.singleton("output_0"))) {
            float[][] scores = (float[][]) result.get("output_0").orElseThrow(IllegalStateException::new).getValue();
            if (scores.length != 1 || scores[0].length != 521) throw new IllegalStateException("Unexpected sound model output");
            return scores[0];
        }
    }

    @Override public void close() throws OrtException { session.close(); }
}
