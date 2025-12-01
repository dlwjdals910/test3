package com.example.gomofrancamera

import android.graphics.RectF

// AI 분석 결과 데이터
data class AnalysisResult(
    val message: String,      // 사용자에게 보여줄 조언 (예: "뒤로 가세요")
    val detectedRect: RectF?, // 감지된 사람의 위치 (화면에 그리기용)
    val isMatched: Boolean    // 구도가 맞았는지 여부 (촬영 가능?)
)