package com.example.gomofrancamera

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.gomofrancamera.databinding.ItemThumbnailBinding // ⭐️ 썸네일 XML 바인딩

class ThumbnailAdapter(
    private val photoList: List<Uri>,
    private val onClick: (Uri) -> Unit // ⭐️ 썸네일을 클릭했을 때 실행할 함수
) : RecyclerView.Adapter<ThumbnailAdapter.ThumbnailViewHolder>() {

    inner class ThumbnailViewHolder(val binding: ItemThumbnailBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            // 썸네일 '칸'이 클릭되면, onClick 함수를 실행합니다.
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onClick(photoList[position])
                }
            }
        }

        fun bind(photoUri: Uri) {
            Glide.with(binding.root.context)
                .load(photoUri)
                .into(binding.thumbnailImageView)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbnailViewHolder {
        val binding =
            ItemThumbnailBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ThumbnailViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ThumbnailViewHolder, position: Int) {
        holder.bind(photoList[position])
    }

    override fun getItemCount() = photoList.size
}