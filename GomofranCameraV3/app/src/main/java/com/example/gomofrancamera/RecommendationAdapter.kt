package com.example.gomofrancamera

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
// ⭐️ [수정] 바인딩 클래스가 ItemThumbnailBinding -> ItemRecommendationBinding 으로 변경됨
import com.example.gomofrancamera.databinding.ItemRecommendationBinding

class RecommendationAdapter(
    private val items: List<GuideItem>,
    private val onClick: (GuideItem?) -> Unit
) : RecyclerView.Adapter<RecommendationAdapter.ViewHolder>() {

    private var selectedPosition = -1

    // ⭐️ [수정] ViewHolder가 사용하는 바인딩 변경
    inner class ViewHolder(val binding: ItemRecommendationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GuideItem, position: Int) {
            binding.thumbnailImageView.setImageResource(item.imageResId)

            // 선택된 아이템 표시 (선택되면 불투명, 아니면 반투명)
            binding.root.alpha = if (selectedPosition == position) 1.0f else 0.5f

            binding.root.setOnClickListener {
                if (selectedPosition == position) {
                    selectedPosition = -1
                    onClick(null)
                } else {
                    selectedPosition = position
                    onClick(item)
                }
                notifyDataSetChanged()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // ⭐️ [수정] item_recommendation.xml을 inflate 하도록 변경
        val binding = ItemRecommendationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount() = items.size
}