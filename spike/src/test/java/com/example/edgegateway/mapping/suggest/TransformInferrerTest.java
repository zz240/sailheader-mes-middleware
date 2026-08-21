package com.example.edgegateway.mapping.suggest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransformInferrerTest {

    @Test
    void singleSampleValueIsStringNotConstant() {
        TransformInferrer.Inference inference =
                TransformInferrer.infer(List.of("PO-001"));

        assertThat(inference.kind()).isEqualTo(TransformKind.STRING);
    }

    @Test
    void repeatedSameValuesSuggestConstant() {
        TransformInferrer.Inference inference =
                TransformInferrer.infer(List.of("FIXED", "FIXED", "FIXED"));

        assertThat(inference.kind()).isEqualTo(TransformKind.CONSTANT);
        assertThat(inference.constant()).isEqualTo("FIXED");
    }
}
