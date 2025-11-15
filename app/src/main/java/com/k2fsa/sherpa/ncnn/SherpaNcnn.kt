package com.k2fsa.sherpa.ncnn

import android.content.res.AssetManager

data class FeatureExtractorConfig(
    var sampleRate: Float,
    var featureDim: Int,
)


data class ModelConfig(
    var encoderParam: String,
    var encoderBin: String,
    var decoderParam: String,
    var decoderBin: String,
    var joinerParam: String,
    var joinerBin: String,
    var tokens: String,
    var numThreads: Int = 1,
    var useGPU: Boolean = true, // If there is a GPU and useGPU true, we will use GPU
)

data class DecoderConfig(
    var method: String = "modified_beam_search", // valid values: greedy_search, modified_beam_search
    var numActivePaths: Int = 4, // used only by modified_beam_search
)

data class RecognizerConfig(
    var featConfig: FeatureExtractorConfig,
    var modelConfig: ModelConfig,
    var decoderConfig: DecoderConfig,
    var enableEndpoint: Boolean = true,
    var rule1MinTrailingSilence: Float = 2.4f,
    var rule2MinTrailingSilence: Float = 1.0f,
    var rule3MinUtteranceLength: Float = 30.0f,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
)

class SherpaNcnn(
    var config: RecognizerConfig,
    assetManager: AssetManager? = null,
) {
    private val ptr: Long

    init {
        if (assetManager != null) {
            ptr = newFromAsset(assetManager, config)
        } else {
            ptr = newFromFile(config)
        }
    }

    protected fun finalize() {
        delete(ptr)
    }

    fun acceptSamples(samples: FloatArray) =
        acceptWaveform(ptr, samples = samples, sampleRate = config.featConfig.sampleRate)

    fun isReady() = isReady(ptr)

    fun decode() = decode(ptr)

    fun inputFinished() = inputFinished(ptr)
    fun isEndpoint(): Boolean = isEndpoint(ptr)
    fun reset(recreate: Boolean = false) = reset(ptr, recreate = recreate)

    val text: String
        get() = getText(ptr)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: RecognizerConfig,
    ): Long

    private external fun newFromFile(
        config: RecognizerConfig,
    ): Long

    private external fun delete(ptr: Long)
    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Float)
    private external fun inputFinished(ptr: Long)
    private external fun isReady(ptr: Long): Boolean
    private external fun decode(ptr: Long)
    private external fun isEndpoint(ptr: Long): Boolean
    private external fun reset(ptr: Long, recreate: Boolean)
    private external fun getText(ptr: Long): String

    companion object {
        init {
            System.loadLibrary("sherpa-ncnn-jni")
        }
    }
}

fun getFeatureExtractorConfig(
    sampleRate: Float,
    featureDim: Int
): FeatureExtractorConfig {
    return FeatureExtractorConfig(
        sampleRate = sampleRate,
        featureDim = featureDim,
    )
}

fun getDecoderConfig(method: String, numActivePaths: Int): DecoderConfig {
    return DecoderConfig(method = method, numActivePaths = numActivePaths)
}


/*
@param type
0 - https://huggingface.co/csukuangfj/sherpa-ncnn-2022-09-30
    This model supports only Chinese

1 - https://huggingface.co/csukuangfj/sherpa-ncnn-conv-emformer-transducer-2022-12-06
    This model supports both English and Chinese

2 - https://huggingface.co/csukuangfj/sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13
    This model supports both English and Chinese

3 - https://huggingface.co/csukuangfj/sherpa-ncnn-streaming-zipformer-en-2023-02-13
    This model supports only English

4 - https://huggingface.co/shaojieli/sherpa-ncnn-streaming-zipformer-fr-2023-04-14
    This model supports only French

5 - https://github.com/k2-fsa/sherpa-ncnn/releases/download/models/sherpa-ncnn-streaming-zipformer-zh-14M-2023-02-23.tar.bz2
    This is a small model and supports only Chinese
6 - https://github.com/k2-fsa/sherpa-ncnn/releases/download/models/sherpa-ncnn-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2
    This is a medium model and supports only Chinese

Please follow
https://k2-fsa.github.io/sherpa/ncnn/pretrained_models/index.html
to add more pre-trained models
 */
fun getModelConfig(type: Int, useGPU: Boolean): ModelConfig? {
    when (type) {
        0 -> {
            val modelDir = "sherpa-ncnn-2022-09-30"
            return ModelConfig(
                encoderParam = "$modelDir/encoder_jit_trace-pnnx.ncnn.param",
                encoderBin = "$modelDir/encoder_jit_trace-pnnx.ncnn.bin",
                decoderParam = "$modelDir/decoder_jit_trace-pnnx.ncnn.param",
                decoderBin = "$modelDir/decoder_jit_trace-pnnx.ncnn.bin",
                joinerParam = "$modelDir/joiner_jit_trace-pnnx.ncnn.param",
                joinerBin = "$modelDir/joiner_jit_trace-pnnx.ncnn.bin",
                tokens = "$modelDir/tokens.txt",
                numThreads = 1,
                useGPU = useGPU,
            )
        }

        1 -> {
            val modelDir = "sherpa-ncnn-conv-emformer-transducer-2022-12-06"
            return ModelConfig(
                encoderParam = "$modelDir/encoder_jit_trace-pnnx.ncnn.int8.param",
                encoderBin = "$modelDir/encoder_jit_trace-pnnx.ncnn.int8.bin",
                decoderParam = "$modelDir/decoder_jit_trace-pnnx.ncnn.param",
                decoderBin = "$modelDir/decoder_jit_trace-pnnx.ncnn.bin",
                joinerParam = "$modelDir/joiner_jit_trace-pnnx.ncnn.int8.param",
                joinerBin = "$modelDir/joiner_jit_trace-pnnx.ncnn.int8.bin",
                tokens = "$modelDir/tokens.txt",
                numThreads = 1,
                useGPU = useGPU,
            )
        }

        2 -> {
            val modelDir = "sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13"
            return ModelConfig(
                encoderParam = "$modelDir/encoder_jit_trace-pnnx.ncnn.param",
                encoderBin = "$modelDir/encoder_jit_trace-pnnx.ncnn.bin",
                decoderParam = "$modelDir/decoder_jit_trace-pnnx.ncnn.param",
                decoderBin = "$modelDir/decoder_jit_trace-pnnx.ncnn.bin",
                joinerParam = "$modelDir/joiner_jit_trace-pnnx.ncnn.param",
                joinerBin = "$modelDir/joiner_jit_trace-pnnx.ncnn.bin",
                tokens = "$modelDir/tokens.txt",
                numThreads = 1,
                useGPU = useGPU,
            )
        }

        3 -> {
            val modelDir = "sherpa-ncnn-streaming-zipformer-en-2023-02-13"
            return ModelConfig(
                encoderParam = "$modelDir/encoder_jit_trace-pnnx.ncnn.param",
                encoderBin = "$modelDir/encoder_jit_trace-pnnx.ncnn.bin",
                decoderParam = "$modelDir/decoder_jit_trace-pnnx.ncnn.param",
                decoderBin = "$modelDir/decoder_jit_trace-pnnx.ncnn.bin",
                joinerParam = "$modelDir/joiner_jit_trace-pnnx.ncnn.param",
                joinerBin = "$modelDir/joiner_jit_trace-pnnx.ncnn.bin",
                tokens = "$modelDir/tokens.txt",
                numThreads = 1,
                useGPU = useGPU,
            )
        }

        4 -> {
            val modelDir = "sherpa-ncnn-streaming-zipformer-fr-2023-04-14"
            return ModelConfig(
                encoderParam = "$modelDir/encoder_jit_trace-pnnx.ncnn.param",
                encoderBin = "$modelDir/encoder_jit_trace-pnnx.ncnn.bin",
                decoderParam = "$modelDir/decoder_jit_trace-pnnx.ncnn.param",
                decoderBin = "$modelDir/decoder_jit_trace-pnnx.ncnn.bin",
                joinerParam = "$modelDir/joiner_jit_trace-pnnx.ncnn.param",
                joinerBin = "$modelDir/joiner_jit_trace-pnnx.ncnn.bin",
                tokens = "$modelDir/tokens.txt",
                numThreads = 1,
                useGPU = useGPU,
            )
        }

        5 -> {
            val modelDir = "sherpa-ncnn-streaming-zipformer-zh-14M-2023-02-23"
            return ModelConfig(
                encoderParam = "$modelDir/encoder_jit_trace-pnnx.ncnn.param",
                encoderBin = "$modelDir/encoder_jit_trace-pnnx.ncnn.bin",
                decoderParam = "$modelDir/decoder_jit_trace-pnnx.ncnn.param",
                decoderBin = "$modelDir/decoder_jit_trace-pnnx.ncnn.bin",
                joinerParam = "$modelDir/joiner_jit_trace-pnnx.ncnn.param",
                joinerBin = "$modelDir/joiner_jit_trace-pnnx.ncnn.bin",
                tokens = "$modelDir/tokens.txt",
                numThreads = 2,
                useGPU = useGPU,
            )
        }

        6 -> {
            val modelDir = "sherpa-ncnn-streaming-zipformer-small-bilingual-zh-en-2023-02-16/96"
            return ModelConfig(
                encoderParam = "$modelDir/encoder_jit_trace-pnnx.ncnn.param",
                encoderBin = "$modelDir/encoder_jit_trace-pnnx.ncnn.bin",
                decoderParam = "$modelDir/decoder_jit_trace-pnnx.ncnn.param",
                decoderBin = "$modelDir/decoder_jit_trace-pnnx.ncnn.bin",
                joinerParam = "$modelDir/joiner_jit_trace-pnnx.ncnn.param",
                joinerBin = "$modelDir/joiner_jit_trace-pnnx.ncnn.bin",
                tokens = "$modelDir/tokens.txt",
                numThreads = 2,
                useGPU = useGPU,
            )
        }
    }
    return null
}
