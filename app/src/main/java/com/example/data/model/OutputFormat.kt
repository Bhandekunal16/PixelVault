package com.example.data.model

enum class OutputFormat(
    val apiValue: String,
    val label: String,
    val extension: String,
    val mimeType: String,
    val description: String
) {
    WEBP("webp", "WebP", "webp", "image/webp", "Best compression and modern web support"),
    JPEG("jpeg", "JPEG", "jpg", "image/jpeg", "Universal compatibility for photos"),
    PNG("png", "PNG", "png", "image/png", "Lossless format for graphics and transparency"),
    AVIF("avif", "AVIF", "avif", "image/avif", "Next-gen ultra-high compression")
}
