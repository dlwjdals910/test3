package com.example.gomofrancamera

import android.app.Activity
import android.app.AlertDialog
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.gomofrancamera.databinding.ActivityPhotoDetailBinding

class PhotoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoDetailBinding
    private lateinit var thumbnailAdapter: ThumbnailAdapter
    private var allPhotoUris: List<Uri> = emptyList()
    private var currentPhotoUri: Uri? = null

    // ⭐️ 1. 삭제 권한 요청 팝업 결과를 처리하는 런처
    private val deletePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 사용자가 시스템 팝업에서 '허용'을 눌렀을 때
            Toast.makeText(this, "사진을 삭제했습니다.", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            // 사용자가 '거부'했을 때
            Toast.makeText(this, "삭제가 거부되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()

        val photoUriStrings = intent.getStringArrayListExtra("photoList")
        val clickedPosition = intent.getIntExtra("clickedPosition", 0)

        if (photoUriStrings != null) {
            allPhotoUris = photoUriStrings.map { it.toUri() }
            currentPhotoUri = allPhotoUris.getOrNull(clickedPosition)

            if (currentPhotoUri != null) {
                setupThumbnailRecyclerView(allPhotoUris, clickedPosition)
                updateMainImage(currentPhotoUri!!)
            }
        } else {
            finish()
        }
    }

    // 메뉴 생성
    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_photo_detail, menu)
        return true
    }

    // 메뉴 클릭 이벤트
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete -> {
                showDeleteDialog()
                true
            }
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateMainImage(photoUri: Uri) {
        currentPhotoUri = photoUri
        Glide.with(this)
            .load(photoUri)
            .into(binding.fullPhotoView)
    }

    private fun setupThumbnailRecyclerView(photoList: List<Uri>, clickedPosition: Int) {
        thumbnailAdapter = ThumbnailAdapter(photoList) { clickedUri ->
            updateMainImage(clickedUri)
        }

        binding.thumbnailRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PhotoDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = thumbnailAdapter
            scrollToPosition(clickedPosition)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = systemBarInsets.top)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun showDeleteDialog() {
        AlertDialog.Builder(this)
            .setTitle("사진 삭제")
            .setMessage("이 사진을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                deleteCurrentPhoto()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ⭐️ 2. 실제 삭제 로직 (권한 요청 포함)
    private fun deleteCurrentPhoto() {
        val uriToDelete = currentPhotoUri ?: return

        try {
            // 1차 시도: 바로 삭제 요청
            val rowsDeleted = contentResolver.delete(uriToDelete, null, null)

            if (rowsDeleted > 0) {
                Toast.makeText(this, "사진을 삭제했습니다.", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "사진을 삭제하지 못했습니다.", Toast.LENGTH_SHORT).show()
            }

        } catch (securityException: SecurityException) {
            // ⭐️ 권한이 없어서 실패한 경우 (SecurityException 발생)

            // 안드로이드 10 (API 29) 이상인지 확인
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                val recoverableSecurityException = securityException as? android.app.RecoverableSecurityException

                if (recoverableSecurityException != null) {
                    // Android 10: RecoverableSecurityException을 통해 팝업 띄우기
                    val intentSender = recoverableSecurityException.userAction.actionIntent.intentSender
                    deletePermissionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // ⭐️ Android 11 (API 30) 이상: createDeleteRequest 사용
                    // 드래그앤드롭한 사진은 보통 이 로직을 탑니다.
                    val deleteRequest = MediaStore.createDeleteRequest(contentResolver, listOf(uriToDelete))
                    deletePermissionLauncher.launch(IntentSenderRequest.Builder(deleteRequest).build())
                } else {
                    Toast.makeText(this, "삭제 권한이 없습니다.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "오류 발생: ${securityException.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }
}