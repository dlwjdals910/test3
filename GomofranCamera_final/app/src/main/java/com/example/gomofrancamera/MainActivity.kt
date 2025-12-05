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
import com.example.gomofrancamera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding

    // CameraX 변수
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    // ⭐️ 팀원이 만든 새로운 AI 엔진
    private lateinit var imageAnalyzer: ImageAnalyzer

    // 현재 선택된 가이드
    private var currentGuide: GuideItem? = null

    // 현재 감지된 배경/상황 정보 (자동 추천용)
    private var currentContextTags: List<String> = emptyList()

    // 자동 촬영 관련 변수
    private var matchStartTime: Long = 0
    private var isAutoCaptureProcessing = false

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
            mutableListOf (
                Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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

        // ⭐️ [수정] ImageAnalyzer 생성 시 리스너(결과 처리)를 바로 정의
        imageAnalyzer = ImageAnalyzer(this) { result ->
            // 1. 분석 결과를 받아서 피드백 생성
            val feedback = generateFeedback(result, currentGuide)

            // 2. UI 업데이트 (메인 스레드에서 실행되도록 runOnUiThread 사용)
            runOnUiThread {
                updateGuideUI(feedback)

                // 3. 자동 추천용 태그 저장
                if (result.backgroundCategory.isNotEmpty()) {
                    currentContextTags = listOf(result.backgroundCategory)
                }
            }
        }

        setupWindowInsets()

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
            val intent = Intent(this, AlbumActivity::class.java)
            startActivity(intent)
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

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

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

            val preview = Preview.Builder()
                .setTargetAspectRatio(cameraAspectRatio)
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(cameraAspectRatio)
                .setFlashMode(flashMode)
                .build()

            // ⭐️ [수정] 분석기 설정이 아주 간단해집니다!
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // ⭐️ 우리가 만든 imageAnalyzer를 그대로 전달
            imageAnalysis?.setAnalyzer(ContextCompat.getMainExecutor(this), imageAnalyzer)

            val viewPort = ViewPort.Builder(viewPortRational, viewBinding.viewFinder.display.rotation)
                .build()

            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageCapture!!)
                .addUseCase(imageAnalysis!!)
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
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // 분석 결과와 가이드를 비교하여 피드백 생성
    private fun generateFeedback(analysis: ImageAnalysisResult, guide: GuideItem?): AnalysisResult {
        if (guide == null) {
            val info = "배경: ${analysis.backgroundCategory}, 포즈: ${analysis.poseCategory}"
            return AnalysisResult(info, analysis.detectedRect, false)
        }

        if (analysis.detectedRect == null) {
            return AnalysisResult("피사체를 찾아주세요 👀", null, false)
        }

        val objRect = analysis.detectedRect
        val targetCx = guide.targetRect.centerX()
        val targetCy = guide.targetRect.centerY()
        val currentCx = objRect.centerX()
        val currentCy = objRect.centerY()

        val diffX = targetCx - currentCx
        val diffY = targetCy - currentCy

        val objArea = objRect.width() * objRect.height()
        val targetArea = guide.targetRect.width() * guide.targetRect.height()
        val sizeRatio = objArea / targetArea

        var message = ""
        var isMatched = false
        val tolerance = 0.15f

        if (diffX > tolerance) message = "오른쪽으로 ➡️"
        else if (diffX < -tolerance) message = "⬅️ 왼쪽으로"
        else if (diffY > tolerance) message = "⬇️ 낮추세요"
        else if (diffY < -tolerance) message = "⬆️ 올리세요"
        else if (sizeRatio < 0.8f) message = "더 가까이 🔍"
        else if (sizeRatio > 1.2f) message = "뒤로 가세요 🔙"
        else {
            message = "완벽해요! 찰칵! ✨"
            isMatched = true
        }

        return AnalysisResult(message, objRect, isMatched)
    }

    // UI 업데이트 및 자동 촬영
    private fun updateGuideUI(result: AnalysisResult) {
        if (result.message.isNotEmpty()) {
            viewBinding.guideMessageText.visibility = View.VISIBLE
            viewBinding.guideMessageText.text = result.message

            if (result.isMatched) {
                viewBinding.guideMessageText.setTextColor(Color.GREEN)

                if (!isAutoCaptureProcessing) {
                    if (matchStartTime == 0L) matchStartTime = System.currentTimeMillis()
                    if (System.currentTimeMillis() - matchStartTime >= 1500) {
                        isAutoCaptureProcessing = true
                        viewBinding.guideMessageText.text = "찰칵! 📸"
                        viewBinding.guideMessageText.setTextColor(Color.BLUE)
                        triggerVibration()
                        playShutterSound()
                        captureImage()
                        viewBinding.root.postDelayed({
                            isAutoCaptureProcessing = false
                            matchStartTime = 0L
                        }, 2000)
                    }
                }
            } else {
                matchStartTime = 0L
                viewBinding.guideMessageText.setTextColor(Color.BLACK)
            }
        } else {
            viewBinding.guideMessageText.visibility = View.GONE
            matchStartTime = 0L
        }

        viewBinding.overlayView.setDetectedRect(result.detectedRect)
    }

    // 추천 패널 설정
    private fun setupRecommendationPanel() {
        // ⭐️ 1. 샘플 데이터: 좌표(RectF)는 비워둡니다. (AI가 채워줄 거니까요!)
        //    (파일 이름은 사용자님 프로젝트에 있는 것으로 맞춰주세요)
        val sampleGuides = listOf(
            GuideItem(R.drawable.img_ref_01, GuideType.RECT, RectF(), listOf("Dog", "Animal")),
            GuideItem(R.drawable.img_ref_02, GuideType.RECT, RectF(), listOf("Car", "Blue")),
            GuideItem(R.drawable.img_ref_03, GuideType.RECT, RectF(), listOf("Couple", "People")),
            GuideItem(R.drawable.img_ref_04, GuideType.RECT, RectF(), listOf("Building", "Scenery")),
            GuideItem(R.drawable.img_ref_05, GuideType.RECT, RectF(), listOf("Food", "Mart")),
            GuideItem(R.drawable.img_ref_06, GuideType.RECT, RectF(), listOf("Dog", "Animal")),
            GuideItem(R.drawable.img_ref_07, GuideType.RECT, RectF(), listOf("Man", "White")),
            GuideItem(R.drawable.img_ref_08, GuideType.RECT, RectF(), listOf("Sunset", "Back")),
            GuideItem(R.drawable.img_ref_09, GuideType.RECT, RectF(), listOf("Mirror", "Couple")),
            GuideItem(R.drawable.img_ref_10, GuideType.RECT, RectF(), listOf("Flower", "Scenery")),
            GuideItem(R.drawable.img_ref_11, GuideType.RECT, RectF(), listOf("Cafe", "Drink")),
            GuideItem(R.drawable.img_ref_12, GuideType.RECT, RectF(), listOf("Night", "Street")),
            GuideItem(R.drawable.img_ref_13, GuideType.RECT, RectF(), listOf("Snow", "Winter")),
            GuideItem(R.drawable.img_ref_14, GuideType.RECT, RectF(), listOf("Glasses", "Indoor")),
            GuideItem(R.drawable.img_ref_15, GuideType.RECT, RectF(), listOf("Object", "Cookie"))
        )

        // ⭐️ 2. 클릭 리스너: AI 분석 후 '사각형' 가이드 생성
        val onItemClick: (GuideItem?) -> Unit = { selectedGuide ->
            if (selectedGuide != null) {
                // (1) 이미지 리소스를 비트맵으로 변환
                val bitmap = android.graphics.BitmapFactory.decodeResource(resources, selectedGuide.imageResId)

                // (2) AI 엔진(ImageAnalyzer)에게 분석 요청!
                //    -> 사진 속 사람의 위치(RectF)를 받아옵니다.
                val detectedRect = imageAnalyzer.analyzeBitmap(bitmap)

                if (detectedRect != null) {
                    // (3-A) 사람이 있으면: 찾은 위치로 '사각형' 가이드 생성
                    val newGuide = selectedGuide.copy(
                        type = GuideType.RECT, // ⭐️ 무조건 사각형으로 통일!
                        targetRect = detectedRect
                    )

                    currentGuide = newGuide
                    viewBinding.overlayView.setGuide(newGuide)
                    viewBinding.guideMessageText.text = "AI가 찾은 최적 구도입니다!"
                } else {
                    // (3-B) 사람이 없으면(풍경 등): 화면 중앙에 기본 사각형 생성
                    val defaultRect = RectF(0.2f, 0.2f, 0.8f, 0.8f)
                    val newGuide = selectedGuide.copy(
                        type = GuideType.RECT, // ⭐️ 무조건 사각형!
                        targetRect = defaultRect
                    )

                    currentGuide = newGuide
                    viewBinding.overlayView.setGuide(newGuide)
                    viewBinding.guideMessageText.text = "중앙에 맞춰보세요 (인물 감지 불가)"
                }

                // 공통 UI 설정
                viewBinding.guideMessageText.visibility = View.VISIBLE
                viewBinding.guideMessageText.setTextColor(Color.BLACK)

            } else {
                // 선택 취소 시
                currentGuide = null
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
                performAutoRecommendation(sampleGuides, onItemClick)

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

    private fun performAutoRecommendation(originalList: List<GuideItem>, onClick: (GuideItem?) -> Unit) {
        if (currentContextTags.isEmpty()) return

        val sortedList = originalList.sortedByDescending { item ->
            item.tags.any { tag ->
                currentContextTags.any { detected -> detected.contains(tag, ignoreCase = true) }
            }
        }

        val newAdapter = RecommendationAdapter(sortedList, onClick)
        viewBinding.recommendationPanel.adapter = newAdapter

        if (sortedList != originalList) {
            Toast.makeText(this, "AI가 ${currentContextTags.first()} 구도를 추천했어요!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerVibration() {
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
    }

    private fun playShutterSound() {
        val sound = android.media.MediaActionSound()
        sound.play(android.media.MediaActionSound.SHUTTER_CLICK)
    }

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
        if (selectedTimer > 0) {
            viewBinding.countdownText.visibility = View.VISIBLE
            object : android.os.CountDownTimer((selectedTimer * 1000).toLong(), 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val count = (millisUntilFinished / 1000.0).let { Math.ceil(it).toInt() }
                    viewBinding.countdownText.text = count.toString()
                }
                override fun onFinish() {
                    viewBinding.countdownText.visibility = View.GONE
                    triggerVibration()
                    playShutterSound()
                    captureImage()
                }
            }.start()
        } else {
            triggerVibration()
            playShutterSound()
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