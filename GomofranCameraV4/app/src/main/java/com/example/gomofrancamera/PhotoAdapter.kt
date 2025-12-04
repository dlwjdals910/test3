package com.example.gomofrancamera

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.gomofrancamera.databinding.ItemPhotoBinding

class PhotoAdapter(private val photoList: List<Uri>) :
    RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

    inner class PhotoViewHolder(val binding: ItemPhotoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {

                    // ⭐️ 1. 전체 사진 URI 목록을 String 목록으로 변환
                    val photoUriStrings = photoList.map { it.toString() }

                    val intent = Intent(binding.root.context, PhotoDetailActivity::class.java).apply {
                        // ⭐️ 2. "photoList"라는 이름표로 '전체 사진 목록'을 전달
                        putStringArrayListExtra("photoList", ArrayList(photoUriStrings))
                        // ⭐️ 3. "clickedPosition"라는 이름표로 '클릭한 사진의 순서'를 전달
                        putExtra("clickedPosition", position)
                    }
                    binding.root.context.startActivity(intent)
                }
            }
        }

        fun bind(photoUri: Uri) {
            Glide.with(binding.root.context)
                .load(photoUri)
                .into(binding.photoImageView)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding =
            ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(photoList[position])
    }

    override fun getItemCount() = photoList.size
}