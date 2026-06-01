package com.gradle.idetour.tours.model

data class TourItem(
    val id: String? = null,
    val title: String,
    val file: String,
    val anchor: Position? = null,
    val inlays: List<InlaySpec>,
    val highlights: List<HighlightSpec> = emptyList()
)

data class InlaySpec(
    val line: Int,
    val text: String,
    val position: InlayPosition = InlayPosition.AboveLine
)

enum class InlayPosition { AboveLine, BelowLine, EndOfLine }

data class HighlightSpec(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int
)

data class Position(val line: Int, val col: Int = 1)
