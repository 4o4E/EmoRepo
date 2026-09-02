package top.e404.emorepo.experiment.lsposed

internal enum class QqPanelPreviewEncoding {
    LOSSLESS_WEBP,
    PNG,
}

/** 首帧缓存始终无损，只按系统是否提供明确的无损 WebP 编码器选择容器。 */
internal fun qqPanelPreviewEncoding(sdkInt: Int): QqPanelPreviewEncoding =
    if (sdkInt >= 30) QqPanelPreviewEncoding.LOSSLESS_WEBP else QqPanelPreviewEncoding.PNG
