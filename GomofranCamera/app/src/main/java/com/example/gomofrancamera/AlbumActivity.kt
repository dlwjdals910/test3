package com.example.gomofrancamera

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
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

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            permissions.entries.forEach {
                if (!it.value) { allGranted = false }
            }

            if (allGranted) {
                loadPhotosFromGallery()
            } else {
                Toast.makeText(this, "갤러리 접근 권한을 허용해야 합니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    private val REQUIRED_PERMISSIONS_GALLERY = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()

        // ⭐️ onCreate에서는 권한이 '없을 때' 요청하는 작업만 합니다.
        // (사진 로딩은 onResume으로 이동했습니다)
        if (!allGalleryPermissionsGranted()) {
            requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS_GALLERY)
        }
    }

    // ⭐️ 화면이 다시 보일 때마다 실행되는 함수
    override fun onResume() {
        super.onResume()

        // 권한이 있다면, 갤러리를 (다시) 불러옵니다.
        // 상세 화면에서 사진을 지우고 돌아오면 이 코드가 실행되어 목록이 갱신됩니다.
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

    private fun allGalleryPermissionsGranted() = REQUIRED_PERMISSIONS_GALLERY.all {
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
        val photoList = mutableListOf<Uri>()

        thread {
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