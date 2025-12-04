package com.example.gomofrancamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.classifier.ImageClassifier

data class ImageAnalysisResult(
    val backgroundCategory: String,
    val poseCategory: String,
    val personHeightRatio: Float,
    val detectedRect: RectF?
)

// ⭐️ 1. @OptIn을 클래스 레벨로 이동 (컴파일 오류 해결의 핵심)
@androidx.annotation.OptIn(ExperimentalGetImage::class)
class ImageAnalyzer(
    context: Context,
    private val listener: (ImageAnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "ImageAnalyzer"
        private const val POSE_LANDMARKER_MODEL = "pose_landmarker_lite.task"
    }

    private val backgroundClassifier: ImageClassifier? = try {
        ImageClassifier.createFromFile(context, "mobilenet_v1_1.0_224_quant.tflite")
    } catch (e: Exception) {
        Log.e(TAG, "TFLite model loading failed", e)
        null
    }

    private val poseLandmarker: PoseLandmarker? = try {
        val baseOptions = BaseOptions.builder().setModelAssetPath(POSE_LANDMARKER_MODEL).build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .build()
        PoseLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        Log.e(TAG, "MediaPipe model loading failed", e)
        null
    }

    // ⭐️ 2. analyze 함수 (인터페이스 구현)
    override fun analyze(imageProxy: ImageProxy) {
        if (backgroundClassifier == null || poseLandmarker == null) {
            imageProxy.close()
            return
        }

        val bitmap = imageProxy.toBitmap()

        // 1. 배경 분석
        val tensorImage = TensorImage.fromBitmap(bitmap)
        val classificationResult = backgroundClassifier.classify(tensorImage)
        val backgroundLabel = classificationResult.firstOrNull()?.categories?.firstOrNull()?.label ?: "unknown"
        val backgroundCategory = mapBackgroundLabel(backgroundLabel)

        // 2. 포즈 분석
        val mpImage = BitmapImageBuilder(bitmap).build()
        val poseResult: PoseLandmarkerResult? = poseLandmarker.detect(mpImage)

        var poseCategory = "no_person"
        var personHeightRatio = 0f
        var detectedRect: RectF? = null

        if (poseResult != null && poseResult.landmarks().isNotEmpty()) {
            val landmarks = poseResult.landmarks().first()

            val xs = landmarks.map { it.x() }
            val ys = landmarks.map { it.y() }

            val minX = xs.minOrNull() ?: 0f
            val maxX = xs.maxOrNull() ?: 0f
            val minY = ys.minOrNull() ?: 0f
            val maxY = ys.maxOrNull() ?: 0f

            personHeightRatio = maxY - minY
            detectedRect = RectF(minX, minY, maxX, maxY)

            if (personHeightRatio < 0.2f) {
                poseCategory = "person_too_small"
            } else {
                // 간단한 포즈 분류 로직
                poseCategory = "person_detected"
            }
        }

        // 3. 결과 전달
        val finalResult = ImageAnalysisResult(backgroundCategory, poseCategory, personHeightRatio, detectedRect)
        listener(finalResult)

        imageProxy.close()
    }

    private fun mapBackgroundLabel(label: String): String {
        val lowerLabel = label.lowercase()
        // 간단한 매핑 로직
        if (lowerLabel.contains("sea") || lowerLabel.contains("beach")) return "sea"
        if (lowerLabel.contains("building") || lowerLabel.contains("city")) return "city"
        if (lowerLabel.contains("forest") || lowerLabel.contains("tree")) return "nature"
        return "others"
    }
    /**
     * ⭐️ [신규 기능] 갤러리/샘플 이미지(Bitmap)를 분석해서 사람 위치(Box)를 찾아주는 함수
     * (MainActivity에서 사진을 클릭할 때 호출됩니다)
     */
    fun analyzeBitmap(bitmap: Bitmap): RectF? {
        if (poseLandmarker == null) return null

        // 1. MediaPipe용 이미지로 변환
        val mpImage = BitmapImageBuilder(bitmap).build()

        // 2. 포즈 감지 실행 (동기 모드)
        val poseResult = poseLandmarker.detect(mpImage)

        // 3. 사람이 감지되었다면 박스(RectF) 계산
        if (poseResult != null && poseResult.landmarks().isNotEmpty()) {
            val landmarks = poseResult.landmarks().first()

            // 모든 관절(Landmark)의 최소/최대 x, y 좌표를 찾습니다.
            val xList = landmarks.map { it.x() }
            val yList = landmarks.map { it.y() }

            val minX = xList.minOrNull() ?: 0f
            val maxX = xList.maxOrNull() ?: 0f
            val minY = yList.minOrNull() ?: 0f
            val maxY = yList.maxOrNull() ?: 0f

            // 박스가 너무 타이트하지 않게 여백(Padding)을 5% 정도 줍니다.
            val padding = 0.05f
            return RectF(
                (minX - padding).coerceAtLeast(0f),
                (minY - padding).coerceAtLeast(0f),
                (maxX + padding).coerceAtMost(1f),
                (maxY + padding).coerceAtMost(1f)
            )
        }
        return null // 사람이 없으면 null
    }
}