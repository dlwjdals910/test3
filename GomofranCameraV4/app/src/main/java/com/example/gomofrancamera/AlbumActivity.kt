package com.example.gomofrancamera

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import com.example.gomofrancamera.databinding.ActivityAlbumBinding
import kotlin.concurrent.thread

class AlbumActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlbumBinding
    private lateinit var photoAdapter: PhotoAdapter

    // ⭐️ 1. 안드로이드 버전에 따라 필요한 권한을 다르게 설정합니다.
    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 안드로이드 13 이상
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            // 안드로이드 12 이하 (여기가 핵심!)
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // 권한 결과 확인
            val allGranted = permissions.entries.all { it.value }

            if (allGranted) {
                loadPhotosFromGallery()
            } else {
                Toast.makeText(this, "갤러리 접근 권한을 허용해야 합니다.", Toast.LENGTH_SHORT).show()
                finish() // 권한 거부 시 화면 닫기
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()

        // 권한이 없으면 요청
        if (!allGalleryPermissionsGranted()) {
            requestPermissionsLauncher.launch(requiredPermissions)
        }
    }

    override fun onResume() {
        super.onResume()
        // 권한이 있으면 사진 불러오기
        if (allGalleryPermissionsGranted()) {
            loadPhotosFromGallery()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = systemBarInsets.top)
            WindowInsetsCompat.CONSUMED
        }
    }

    // ⭐️ 2. 권한 체크 함수도 버전에 맞게 수정된 변수를 사용하도록 변경
    private fun allGalleryPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupRecyclerView(photoList: List<Uri>) {
        photoAdapter = PhotoAdapter(photoList)
        binding.photoRecyclerView.apply {
            layoutManager = GridLayoutManager(this@AlbumActivity, 3)
            adapter = photoAdapter
        }
    }

    private fun loadPhotosFromGallery() {
        // 이미 로딩 중이거나 데이터가 꼬이는 것을 방지하기 위해 리스트 초기화는 스레드 내부에서 처리 권장
        thread {
            val photoList = mutableListOf<Uri>() // 스레드 내부 지역변수로 변경

            val projection = arrayOf(
                MediaStore.Images.Media._ID
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    photoList.add(contentUri)
                }
            }

            runOnUiThread {
                binding.toolbar.title = "앨범 (${photoList.size}장)"
                setupRecyclerView(photoList)
            }
        }
    }
}