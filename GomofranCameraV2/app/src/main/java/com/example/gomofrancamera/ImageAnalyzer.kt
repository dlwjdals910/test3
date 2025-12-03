package com.example.gomofrancamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.classifier.ImageClassifier

/**
 * [데이터 클래스] ImageAnalysisResult
 * 기능: AI 분석이 완료된 후, UI(MainActivity)로 전달할 최종 결과 데이터를 담는 그릇입니다.
 *
 * @property backgroundCategory (Output) 분석된 배경의 종류 (예: "sea", "city", "nature")
 * @property poseCategory (Output) 분석된 인물의 자세 (예: "full_body", "upper_body", "face_only")
 * @property personHeightRatio (Output) 화면 전체 높이 대비 사람의 키 비율 (0.0 ~ 1.0)
 */
data class ImageAnalysisResult(
    val backgroundCategory: String,
    val poseCategory: String,
    val personHeightRatio: Float,
    val detectedRect: RectF? // ⭐️ [추가] 감지된 사람의 위치 박스
)

/**
 * [클래스] ImageAnalyzer
 * 기능: CameraX의 ImageAnalysis.Analyzer를 구현하여, 매 프레임마다 이미지를 받아 배경과 자세를 분석합니다.
 *
 * @param context (Input) 모델 파일(tflite, task 파일)을 assets 폴더에서 읽어오기 위한 앱 컨텍스트
 * @param listener (Output Callback) 분석이 끝났을 때 결과를 MainActivity로 던져주기 위한 콜백 함수
 */
class ImageAnalyzer(
    context: Context,
    private val listener: (ImageAnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "ImageAnalyzer"
        private const val POSE_LANDMARKER_MODEL = "pose_landmarker_lite.task"
    }

    // 배경 분류를 위한 TensorFlow Lite 모델 초기화
    private val backgroundClassifier: ImageClassifier? = try {
        ImageClassifier.createFromFile(context, "mobilenet_v1_1.0_224_quant.tflite")
    } catch (e: Exception) {
        Log.e(TAG, "TFLite model loading failed", e)
        null
    }

    // 인물 자세 인식을 위한 MediaPipe PoseLandmarker 초기화
    private val poseLandmarker: PoseLandmarker? = try {
        val baseOptions = BaseOptions.builder().setModelAssetPath(POSE_LANDMARKER_MODEL).build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE) // 동영상이 아닌 개별 이미지 모드로 설정
            .build()
        PoseLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        Log.e(TAG, "MediaPipe model loading failed", e)
        null
    }

    /**
     * [함수] analyze
     * 기능: 카메라에서 들어온 실시간 이미지 프레임 하나를 분석하는 핵심 함수입니다.
     *       CameraX 라이브러리에 의해 매 프레임마다 자동으로 호출됩니다.
     *
     * @param imageProxy (Input) 카메라 하드웨어로부터 캡처된 원본 이미지 데이터 (YUV 포맷 등)
     * Output: 없음 (void). 대신 분석이 끝나면 listener를 통해 결과를 전송함.
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // 모델이 로드되지 않았으면 처리하지 않고 이미지를 닫음 (메모리 누수 방지)
        if (backgroundClassifier == null || poseLandmarker == null) {
            imageProxy.close()
            return
        }

        // 1. ImageProxy를 안드로이드 표준 Bitmap으로 변환
        val bitmap = imageProxy.toBitmap()

        // --- 분석 1단계: 배경 분류 (TensorFlow Lite) ---
        val tensorImage = TensorImage.fromBitmap(bitmap)
        val classificationResult = backgroundClassifier.classify(tensorImage)
        // 가장 확률이 높은 결과 하나를 가져옴 (없으면 "unknown")
        val backgroundLabel = classificationResult.firstOrNull()?.categories?.firstOrNull()?.label ?: "unknown"
        // 복잡한 영어 라벨을 단순한 카테고리로 변환
        val backgroundCategory = mapBackgroundLabel(backgroundLabel)

        // --- 분석 2단계: 인물 자세 분석 (MediaPipe) ---
        val mpImage = BitmapImageBuilder(bitmap).build()
        val poseResult: PoseLandmarkerResult? = poseLandmarker.detect(mpImage)

        // 기본값 설정
        var poseCategory = "no_person"
        var personHeightRatio = 0f
        var detectedRect: RectF? = null

        // 사람이 감지되었고, 랜드마크(관절 포인트)가 있을 경우 분석 시작
        if (poseResult != null && poseResult.landmarks().isNotEmpty()) {
            val landmarks = poseResult.landmarks().first() // 첫 번째 사람만 분석

            // 사람의 키 비율 계산 (가장 높은 y좌표 - 가장 낮은 y좌표)
            val ys = landmarks.map { it.y() }
            personHeightRatio = (ys.maxOrNull() ?: 0f) - (ys.minOrNull() ?: 0f)

            // ⭐️ [추가] 랜드마크를 기준으로 사람의 박스(Bounding Box) 계산
            val xList = landmarks.map { it.x() }
            val yList = landmarks.map { it.y() }

            val minX = xList.minOrNull() ?: 0f
            val maxX = xList.maxOrNull() ?: 0f
            val minY = yList.minOrNull() ?: 0f
            val maxY = yList.maxOrNull() ?: 0f

            // 약간의 여유 공간(Padding)을 둬서 박스 생성
            detectedRect = RectF(minX, minY, maxX, maxY)

            // 비율에 따라 자세 분류 (전신, 상반신, 얼굴 위주 등)
            if (personHeightRatio < 0.2f) {
                poseCategory = "person_too_small" // 사람이 너무 작음
            } else {
                val visibilityThreshold = 0.6f // 랜드마크가 화면에 보일 확률 임계값

                // 발목(27, 28번 랜드마크)이 보이는지 확인 -> 전신
                val ankleVisibility = if (landmarks.size > 28) {
                    (landmarks[27].visibility().orElse(0f) > visibilityThreshold) || (landmarks[28].visibility().orElse(0f) > visibilityThreshold)
                } else false

                // 무릎(25, 26번 랜드마크)이 보이는지 확인 -> 상반신
                val kneeVisibility = if (landmarks.size > 26) {
                    (landmarks[25].visibility().orElse(0f) > visibilityThreshold) || (landmarks[26].visibility().orElse(0f) > visibilityThreshold)
                } else false

                poseCategory = when {
                    ankleVisibility -> "full_body" // 전신
                    kneeVisibility -> "upper_body" // 상반신 (무릎까지)
                    else -> "face_only"            // 그 외 (얼굴 위주)
                }
            }
        }

        // --- 3단계: 결과 전송 ---
        val finalResult = ImageAnalysisResult(backgroundCategory, poseCategory, personHeightRatio, detectedRect)
        listener(finalResult)

        // [중요] 처리가 끝난 이미지는 반드시 닫아줘야 다음 프레임이 들어옵니다.
        imageProxy.close()
    }

    /**
     * [함수] mapBackgroundLabel
     * 기능: AI가 뱉어낸 구체적인 사물 이름(예: "seashore", "skyscraper")을 넓은 범주의 카테고리로 묶어줍니다.
     *
     * @param label (Input) AI 모델이 인식한 원본 라벨 문자열
     * @return (Output) 매핑된 카테고리 문자열 (예: "sea", "city")
     */
    private fun mapBackgroundLabel(label: String): String {
        val lowerLabel = label.lowercase()
        val backgroundMapping = mapOf(
            "sea" to listOf("sea", "ocean", "beach", "sand", "water", "lake", "coast"),
            "nature" to listOf("forest", "mountain", "grass", "tree", "valley", "flower", "park", "garden"),
            "city" to listOf("building", "street", "city", "house", "shop", "road", "downtown"),
            "indoor" to listOf("room", "table", "library", "inside", "desk", "restaurant", "theater"),
            "night_view" to listOf("night", "light", "traffic")
        )

        // 매핑 테이블을 순회하며 키워드가 포함되어 있는지 확인
        for ((category, keywords) in backgroundMapping) {
            if (keywords.any { it in lowerLabel }) {
                return category
            }
        }

        // 매핑되는 것이 없으면 기타(others)로 반환
        val shortRaw = lowerLabel.split(',').firstOrNull()?.trim() ?: "unknown"
        return "others($shortRaw)"
    }
}