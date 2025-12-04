package com.example.gomofrancamera

import android.graphics.RectF

// 추천 구도 아이템 데이터 모델
data class GuideItem(
    val imageResId: Int,       // 이미지 리소스 ID (R.drawable.xxx)
    val type: GuideType,       // 가이드 모양 (타원, 네모, 없음)
    val targetRect: RectF,     // 목표 구도 위치 (0.0 ~ 1.0 비율)
    val tags: List<String> = emptyList() // (선택) 태그 목록
)

// 가이드 모양 종류
enum class GuideType {
    NONE, // 가이드 없음
    OVAL, // 인물 (타원)
    RECT  // 사물/풍경 (네모)
}