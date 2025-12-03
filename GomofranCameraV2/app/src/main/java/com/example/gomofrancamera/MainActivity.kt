package com.example.gomofrancamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.gomofrancamera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding

    // CameraX 변수
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    // 현재 선택된 가이드 (OverlayView 표시용)
    private var currentGuide: GuideItem? = null

    private val prefs by lazy {
        getSharedPreferences("GomofranCameraPrefs", Context.MODE_PRIVATE)
    }

    // 카메라 설정 변수
    private var currentRatioKey: Int = RATIO_4_3_CUSTOM
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var selectedTimer = 0
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    companion object {
        private const val TAG = "MainActivity"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA
            ).apply {
                // 안드로이드 13 (Tiramisu) 이상 -> READ_MEDIA_IMAGES 요청
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                }
                // 안드로이드 12 이하 -> READ_EXTERNAL_STORAGE 요청
                else {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)

                    // 안드로이드 9 (P) 이하 -> WRITE_EXTERNAL_STORAGE 추가 요청
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
            }.toTypedArray()

        private const val KEY_LAST_RATIO = "last_ratio"

        private const val RATIO_4_3_CUSTOM = 0
        private const val RATIO_1_1_CUSTOM = 1
        private const val RATIO_16_9_CUSTOM = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        setupWindowInsets()

        // 저장된 비율 불러오기
        currentRatioKey = prefs.getInt(KEY_LAST_RATIO, RATIO_4_3_CUSTOM)
        updateRatioIcon(currentRatioKey)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // 버튼 리스너 설정
        viewBinding.shutterButton.setOnClickListener { takePicture() }

        viewBinding.galleryButton.setOnClickListener {
            // 1. 갤러리 권한이 있는지 확인
            if (hasGalleryPermission()) {
                // 권한 있으면 앨범 화면으로 이동
                val intent = Intent(this, AlbumActivity::class.java)
                startActivity(intent)
            } else {
                // 2. 권한 없으면 안내 메시지 띄우고 권한 요청
                Toast.makeText(this, "갤러리를 열려면 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                )
            }
        }

        setupGridButton()

        viewBinding.timerButton.setOnClickListener {
            selectedTimer = when (selectedTimer) {
                0 -> 3
                3 -> 5
                5 -> 10
                else -> 0
            }
            val iconRes = when (selectedTimer) {
                3 -> R.drawable.ic_timer_3
                5 -> R.drawable.ic_timer_5
                10 -> R.drawable.ic_timer_10
                else -> R.drawable.ic_timer_off
            }
            viewBinding.timerButton.setImageResource(iconRes)
            val message = if (selectedTimer > 0) "타이머 ${selectedTimer}초" else "타이머 끄기"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        viewBinding.flashButton.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }
            val iconRes = when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
                else -> R.drawable.ic_flash_off
            }
            viewBinding.flashButton.setImageResource(iconRes)
            imageCapture?.flashMode = flashMode

            val message = when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> "플래시 켜짐"
                ImageCapture.FLASH_MODE_AUTO -> "플래시 자동"
                else -> "플래시 꺼짐"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        var isExposureVisible = false
        viewBinding.exposureButton.setOnClickListener {
            isExposureVisible = !isExposureVisible
            if (isExposureVisible) {
                viewBinding.exposureSeekBar.visibility = View.VISIBLE
                viewBinding.exposureButton.setColorFilter(Color.YELLOW)
            } else {
                viewBinding.exposureSeekBar.visibility = View.GONE
                viewBinding.exposureButton.clearColorFilter()
            }
        }

        viewBinding.ratioButton.setOnClickListener {
            currentRatioKey = when (currentRatioKey) {
                RATIO_4_3_CUSTOM -> RATIO_1_1_CUSTOM
                RATIO_1_1_CUSTOM -> RATIO_16_9_CUSTOM
                else -> RATIO_4_3_CUSTOM
            }
            prefs.edit().putInt(KEY_LAST_RATIO, currentRatioKey).apply()
            updateRatioIcon(currentRatioKey)
            startCamera()
        }
        updateRatioIcon(currentRatioKey)

        viewBinding.switchCameraButton.setOnClickListener {
            currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }

        setupRecommendationPanel()
    }

    // ⭐️ AI 관련 코드(ImageAnalysis)가 모두 제거된 startCamera
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // 1. 화면 비율 설정
            val viewPortRational = when (currentRatioKey) {
                RATIO_1_1_CUSTOM -> Rational(1, 1)
                RATIO_16_9_CUSTOM -> Rational(9, 16)
                else -> Rational(3, 4)
            }

            val cameraAspectRatio = if (currentRatioKey == RATIO_16_9_CUSTOM) {
                AspectRatio.RATIO_16_9
            } else {
                AspectRatio.RATIO_4_3
            }

            // 2. 뷰포트 설정
            val viewPort = ViewPort.Builder(viewPortRational, viewBinding.viewFinder.display.rotation)
                .build()

            // 3. 이미지 분석기 (AI) 설정
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(cameraAspectRatio)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this),
                ImageAnalyzer(this) { result ->
                    updateRealtimeFeedback(result)
                }
            )

            // 4. 미리보기 설정
            val preview = Preview.Builder()
                .setTargetAspectRatio(cameraAspectRatio)
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // 5. 이미지 캡처 설정
            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(cameraAspectRatio)
                .setFlashMode(flashMode)
                .build()

            // 6. 유즈케이스 그룹 생성 (중복 제거됨!)
            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageCapture!!)
                .addUseCase(imageAnalysis) // AI 분석 포함
                .setViewPort(viewPort)
                .build()

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this, currentCameraSelector, useCaseGroup
                )
                updateUiForRatio(currentRatioKey)
                setupExposureControl()

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "카메라 실행 실패", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // AI 분석 결과를 받아서 처리하는 함수
    private fun updateRealtimeFeedback(result: ImageAnalysisResult) {
        // 1. 로그 확인
        Log.d(TAG, "AI 분석 중.. 배경: ${result.backgroundCategory}, 자세: ${result.poseCategory}")

        runOnUiThread {
            val guide = currentGuide
            val detected = result.detectedRect

            // 가이드가 없거나 사람이 감지되지 않으면 텍스트 숨김 또는 기본 메시지
            if (guide == null || detected == null) {
                if (guide != null && detected == null) {
                    viewBinding.guideMessageText.visibility = View.VISIBLE
                    viewBinding.guideMessageText.text = "사람이 보이지 않아요 🧐"
                    viewBinding.guideMessageText.setTextColor(Color.RED)
                } else {
                    // 가이드가 없는 경우 텍스트 숨김 (선택사항)
                    viewBinding.guideMessageText.visibility = View.GONE
                }
                return@runOnUiThread
            }

            // --- ⭐️ 여기서부터 오차 범위 체크 로직 시작 ---
            val guideCenterX = guide.targetRect.centerX()
            val guideCenterY = guide.targetRect.centerY()
            val detectedCenterX = detected.centerX()
            val detectedCenterY = detected.centerY()

            // 오차 계산
            val diffX = guideCenterX - detectedCenterX
            val diffY = guideCenterY - detectedCenterY

            val absDiffX = Math.abs(diffX)
            val absDiffY = Math.abs(diffY)

            // 오차 범위 (15%)
            val tolerance = 0.15f

            val feedbackMessage = if (absDiffX < tolerance && absDiffY < tolerance) {
                "✅ 구도가 완벽해요! 찰칵!"
            } else {
                // ⭐️ [수정됨] 사진 찍는 사람이 움직여야 하는 방향 (반대로 설정)
                if (absDiffX > absDiffY) {
                    // 가로(X)가 더 많이 틀렸을 때
                    if (diffX > 0) {
                        // 상황: 가이드(중심)는 500, 사람(내위치)은 100 (왼쪽에 있음)
                        // 해결: 카메라를 '왼쪽'으로 돌려야 사람이 중앙으로 옴
                        "👈 카메라를 왼쪽으로 비추세요"
                    } else {
                        // 상황: 가이드(중심)는 500, 사람(내위치)은 900 (오른쪽에 있음)
                        // 해결: 카메라를 '오른쪽'으로 돌려야 사람이 중앙으로 옴
                        "👉 카메라를 오른쪽으로 비추세요"
                    }
                } else {
                    // 세로(Y)가 더 많이 틀렸을 때
                    if (diffY > 0) {
                        // 상황: 가이드(중심)는 아래(800), 사람(내위치)은 위(200)
                        // 해결: 카메라를 '위'로 들어야(Tilt Up) 사람이 내려옴
                        "👆 카메라를 위쪽을 향하게 드세요"
                    } else {
                        // 상황: 가이드(중심)는 위(200), 사람(내위치)은 아래(800)
                        // 해결: 카메라를 '아래'로 내려야(Tilt Down) 사람이 올라옴
                        "👇 카메라를 아래쪽을 향하게 내리세요"
                    }
                }
            }

            // 화면 표시
            viewBinding.guideMessageText.visibility = View.VISIBLE
            viewBinding.guideMessageText.text = feedbackMessage

            // 텍스트 크기 등 원상 복구 (디버그 모드 해제)
            viewBinding.guideMessageText.textSize = 24f
            viewBinding.guideMessageText.maxLines = 2

            // 성공 시 초록색, 실패 시 빨간색/노란색
            if (feedbackMessage.contains("완벽")) {
                viewBinding.guideMessageText.setTextColor(Color.GREEN)
            } else {
                // 거의 다 왔으면(20% 이내) 노란색, 멀면 빨간색
                if (absDiffX < 0.25f && absDiffY < 0.25f) {
                    viewBinding.guideMessageText.setTextColor(Color.YELLOW)
                } else {
                    viewBinding.guideMessageText.setTextColor(Color.RED)
                }
            }
        }
    }

    // (보너스) 영어를 한글로 바꿔주는 간단한 함수 추가
    private fun translateBackground(eng: String): String {
        return when(eng) {
            "sea" -> "바다/물가"
            "nature" -> "숲/자연"
            "city" -> "도시/건물"
            "indoor" -> "실내"
            else -> "기타"
        }
    }

    private fun translatePose(eng: String): String {
        return when(eng) {
            "full_body" -> "전신 나옴"
            "upper_body" -> "상반신 나옴"
            "face_only" -> "얼굴 위주"
            "person_too_small" -> "사람이 너무 작음"
            "no_person" -> "사람 없음"
            else -> "분석 중..."
        }
    }

    // ⭐️ AI 자동 추천 로직 제거된 패널 설정
    private fun setupRecommendationPanel() {
        val sampleGuides = listOf(
            GuideItem(R.drawable.img_ref_01, GuideType.OVAL, RectF(0.2f, 0.1f, 0.8f, 0.7f)),
            GuideItem(R.drawable.img_ref_02, GuideType.OVAL, RectF(0.3f, 0.2f, 0.7f, 0.9f)),
            GuideItem(R.drawable.img_ref_03, GuideType.OVAL, RectF(0.1f, 0.2f, 0.9f, 0.9f)),
            GuideItem(R.drawable.img_ref_04, GuideType.RECT, RectF(0.1f, 0.1f, 0.9f, 0.9f)),
            GuideItem(R.drawable.img_ref_05, GuideType.OVAL, RectF(0.2f, 0.2f, 0.8f, 0.6f)),
            GuideItem(R.drawable.img_ref_06, GuideType.RECT, RectF(0.3f, 0.4f, 0.7f, 0.8f)),
            GuideItem(R.drawable.img_ref_07, GuideType.OVAL, RectF(0.2f, 0.1f, 0.8f, 0.7f)),
            GuideItem(R.drawable.img_ref_08, GuideType.OVAL, RectF(0.3f, 0.3f, 0.7f, 0.9f)),
            GuideItem(R.drawable.img_ref_09, GuideType.RECT, RectF(0.2f, 0.1f, 0.8f, 0.9f)),
            GuideItem(R.drawable.img_ref_10, GuideType.RECT, RectF(0.1f, 0.3f, 0.9f, 0.8f)),
            GuideItem(R.drawable.img_ref_11, GuideType.OVAL, RectF(0.2f, 0.3f, 0.8f, 0.8f)),
            GuideItem(R.drawable.img_ref_12, GuideType.OVAL, RectF(0.25f, 0.15f, 0.75f, 0.85f)),
            GuideItem(R.drawable.img_ref_13, GuideType.OVAL, RectF(0.2f, 0.2f, 0.8f, 0.7f)),
            GuideItem(R.drawable.img_ref_14, GuideType.OVAL, RectF(0.2f, 0.2f, 0.8f, 0.6f)),
            GuideItem(R.drawable.img_ref_15, GuideType.RECT, RectF(0.3f, 0.4f, 0.7f, 0.6f))
        )

        // 클릭 리스너: AI 분석 없이 가이드(점선)만 표시
        val onItemClick: (GuideItem?) -> Unit = { selectedGuide ->
            currentGuide = selectedGuide
            if (selectedGuide != null) {
                viewBinding.overlayView.setGuide(selectedGuide)
                viewBinding.guideMessageText.visibility = View.VISIBLE
                viewBinding.guideMessageText.text = "가이드에 맞춰보세요!" // 고정 멘트
                viewBinding.guideMessageText.setTextColor(Color.BLACK)
            } else {
                viewBinding.overlayView.setGuide(null)
                viewBinding.guideMessageText.visibility = View.GONE
            }
        }

        val adapter = RecommendationAdapter(sampleGuides, onItemClick)
        viewBinding.recommendationPanel.adapter = adapter

        var isPanelOpen = false
        viewBinding.panelHandle.setOnClickListener {
            isPanelOpen = !isPanelOpen
            val panelWidth = 120 * resources.displayMetrics.density

            if (isPanelOpen) {
                // 자동 추천 로직 제거됨
                viewBinding.recommendationPanel.bringToFront()
                viewBinding.panelHandle.bringToFront()
                viewBinding.recommendationPanel.animate().translationX(0f).setDuration(200).start()
                viewBinding.panelHandle.animate().translationX(-panelWidth).setDuration(200).start()
                viewBinding.handleIcon.animate().rotation(180f).setDuration(200).start()
            } else {
                viewBinding.recommendationPanel.animate().translationX(panelWidth).setDuration(200).start()
                viewBinding.panelHandle.animate().translationX(0f).setDuration(200).start()
                viewBinding.handleIcon.animate().rotation(0f).setDuration(200).start()
            }
        }
    }

    // ... (아래 함수들은 변경 없음: setupExposureControl, updateUiForRatio, setupWindowInsets, setupGridButton, takePicture, etc.) ...

    private fun setupExposureControl() {
        val cameraControl = camera?.cameraControl ?: return
        val exposureState = camera?.cameraInfo?.exposureState ?: return
        if (!exposureState.isExposureCompensationSupported) {
            viewBinding.exposureSeekBar.visibility = View.GONE
            return
        }
        val range = exposureState.exposureCompensationRange
        viewBinding.exposureSeekBar.max = range.upper - range.lower
        viewBinding.exposureSeekBar.progress = exposureState.exposureCompensationIndex - range.lower
        viewBinding.exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val actualIndex = progress + range.lower
                    cameraControl.setExposureCompensationIndex(actualIndex)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateUiForRatio(ratioKey: Int) {
        val previewParams = viewBinding.viewFinder.layoutParams as ConstraintLayout.LayoutParams
        val overlayParams = viewBinding.overlayView.layoutParams as ConstraintLayout.LayoutParams
        val ratioString = when (ratioKey) {
            RATIO_1_1_CUSTOM -> "1:1"
            RATIO_4_3_CUSTOM -> "3:4"
            else -> "9:16"
        }
        previewParams.dimensionRatio = ratioString
        previewParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        previewParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        previewParams.verticalBias = 0.5f
        viewBinding.viewFinder.layoutParams = previewParams
        overlayParams.dimensionRatio = ratioString
        overlayParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        overlayParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        overlayParams.verticalBias = 0.5f
        viewBinding.overlayView.layoutParams = overlayParams
        if (ratioKey == RATIO_16_9_CUSTOM) {
            viewBinding.bottomBar.setBackgroundColor(Color.parseColor("#80000000"))
            viewBinding.topBar.setBackgroundColor(Color.parseColor("#80000000"))
            previewParams.dimensionRatio = null
            overlayParams.dimensionRatio = null
        } else {
            viewBinding.bottomBar.setBackgroundColor(Color.BLACK)
            viewBinding.topBar.setBackgroundColor(Color.parseColor("#80000000"))
        }
    }

    private fun updateRatioIcon(ratioKey: Int) {
        val iconRes = when (ratioKey) {
            RATIO_1_1_CUSTOM -> R.drawable.ic_aspect_ratio_1_1
            RATIO_16_9_CUSTOM -> R.drawable.ic_aspect_ratio_16_9
            else -> R.drawable.ic_aspect_ratio_4_3
        }
        viewBinding.ratioButton.setImageResource(iconRes)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.topBar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as ConstraintLayout.LayoutParams
            params.topMargin = systemBars.top
            view.layoutParams = params
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.bottomBar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as ConstraintLayout.LayoutParams
            params.bottomMargin = systemBars.bottom
            view.layoutParams = params
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupGridButton() {
        var isGridEnabled = false
        viewBinding.gridButton.setOnClickListener {
            Log.d(TAG, "Grid button clicked! isGridEnabled = $isGridEnabled")
            isGridEnabled = !isGridEnabled
            viewBinding.overlayView.setGridVisible(isGridEnabled)
            if (isGridEnabled) {
                viewBinding.gridButton.setColorFilter(Color.BLUE)
            } else {
                viewBinding.gridButton.clearColorFilter()
            }
        }
    }

    private fun takePicture() {
        // 진동/소리 피드백 (AI 제외 버전에서도 유지)
        fun feedback() {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, 150))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
            val sound = android.media.MediaActionSound()
            sound.play(android.media.MediaActionSound.SHUTTER_CLICK)
        }

        if (selectedTimer > 0) {
            viewBinding.countdownText.visibility = View.VISIBLE
            object : android.os.CountDownTimer((selectedTimer * 1000).toLong(), 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val count = (millisUntilFinished / 1000.0).let { Math.ceil(it).toInt() }
                    viewBinding.countdownText.text = count.toString()
                }
                override fun onFinish() {
                    viewBinding.countdownText.visibility = View.GONE
                    feedback()
                    captureImage()
                }
            }.start()
        } else {
            feedback()
            captureImage()
        }
    }

    private fun captureImage() {
        val imageCapture = imageCapture ?: return
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.KOREA).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GomofranCamera")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Toast.makeText(baseContext, "사진 저장 실패: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
            override fun onImageSaved(output: ImageCapture.OutputFileResults){
                val msg = "사진이 저장되었습니다"
                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasGalleryPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 안드로이드 13 이상: 이미지 읽기 권한 체크
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            // 안드로이드 12 이하: 저장소 읽기 권한 체크
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera() else {
                Toast.makeText(this, "카메라 권한을 허용해야 앱을 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}