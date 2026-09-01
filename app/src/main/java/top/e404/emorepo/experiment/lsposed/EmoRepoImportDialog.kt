package top.e404.emorepo.experiment.lsposed

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.Image
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.target.ImageViewTarget
import java.io.File
import top.e404.emorepo.ipc.EmoRepoIpcContract

/** QQ 图片消息导入使用的自定义表情包选择与确认窗口。 */
internal class EmoRepoImportDialog private constructor(
    private val context: Context,
    private val packs: List<PanelPack>,
    private val pictureCount: Int,
    private val onImport: (String) -> Unit,
) {
    private val dark = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val foregroundColor = if (dark) Color.WHITE else Color.rgb(28, 28, 30)
    private val secondaryColor = if (dark) Color.LTGRAY else Color.rgb(92, 92, 96)
    private val surfaceColor = if (dark) Color.rgb(38, 38, 42) else Color.WHITE
    private val accentColor = if (dark) Color.rgb(58, 125, 205) else Color.rgb(35, 112, 210)
    private val rowColor = if (dark) Color.rgb(49, 49, 54) else Color.rgb(246, 247, 249)
    private val badgeColor = if (dark) Color.rgb(64, 77, 94) else Color.rgb(225, 235, 248)
    private val imageLoader = importImageLoader(context)
    private val dialog = Dialog(context)
    private val content = LinearLayout(context)
    private var chooserAdapter: PackAdapter? = null
    private var confirmationCover: Disposable? = null
    private var previewRequest: Disposable? = null
    private var previewFile: File? = null
    private var previewImage: ImageView? = null
    private var chooserVisible = false

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(18), dp(16), dp(18), dp(14))
        content.background = roundedBackground(surfaceColor, dp(20).toFloat())
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { disposeContent() }
        showChooser()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.48f }
            setLayout(dialogWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
    }

    private fun showChooser() {
        disposeContent()
        chooserVisible = true
        content.removeAllViews()
        content.addView(title("添加到 EmoRepo"))
        content.addView(
            subtitle("选择要导入 $pictureCount 张图片的表情包"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
            },
        )
        content.addView(
            createImportPreview(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                importPreviewHeight(),
            ).apply { bottomMargin = dp(12) },
        )
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            adapter = PackAdapter(packs, ::showConfirmation).also { chooserAdapter = it }
        }
        val maximumHeight = (context.resources.displayMetrics.heightPixels * 0.40f).toInt()
        val desiredHeight = dp(PACK_ROW_HEIGHT_DP + PACK_ROW_SPACING_DP) * packs.size
        val minimumHeight = minOf(dp(PACK_ROW_HEIGHT_DP), maximumHeight).coerceAtLeast(1)
        content.addView(
            list,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                desiredHeight.coerceIn(minimumHeight, maximumHeight.coerceAtLeast(minimumHeight)),
            ),
        )
        content.addView(
            actionButton("取消", filled = false) { dialog.dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ACTION_HEIGHT_DP)).apply {
                topMargin = dp(10)
            },
        )
    }

    private fun showConfirmation(pack: PanelPack) {
        disposeContent()
        content.removeAllViews()
        content.addView(title("确认导入"))
        val summary = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(rowColor, dp(14).toFloat())
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(android.R.drawable.ic_menu_gallery)
            contentDescription = "${pack.displayName} 封面"
        }
        summary.addView(cover, LinearLayout.LayoutParams(dp(CONFIRM_COVER_SIZE_DP), dp(CONFIRM_COVER_SIZE_DP)))
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(packNameRow(pack, 17f))
            addView(TextView(context).apply {
                text = "现有 ${pack.itemCount} 张 · 将导入 $pictureCount 张"
                textSize = 13f
                setTextColor(secondaryColor)
            })
        }
        summary.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(
            summary,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
                bottomMargin = dp(16)
            },
        )
        confirmationCover = loadCover(cover, pack, CONFIRM_COVER_SIZE_DP)
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(actionButton("返回", filled = false, ::showChooser), LinearLayout.LayoutParams(0, dp(ACTION_HEIGHT_DP), 1f))
            addView(actionButton("取消", filled = false) { dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(ACTION_HEIGHT_DP), 1f).apply {
                marginStart = dp(6)
            })
            addView(actionButton("导入", filled = true) {
                dialog.dismiss()
                onImport(pack.id)
            }, LinearLayout.LayoutParams(0, dp(ACTION_HEIGHT_DP), 1f).apply {
                marginStart = dp(6)
            })
        }
        content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.window?.setLayout(dialogWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun updatePreview(file: File) {
        if (!file.isFile || file.length() <= 0L || !dialog.isShowing) return
        previewFile = file
        if (chooserVisible) bindImportPreview()
    }

    private fun createImportPreview(): View = FrameLayout(context).apply {
        background = roundedBackground(rowColor, dp(14).toFloat())
        addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setImageResource(android.R.drawable.ic_menu_gallery)
                contentDescription = "待导入表情预览"
                previewImage = this
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        bindImportPreview()
    }

    private fun bindImportPreview() {
        previewRequest?.dispose()
        previewRequest = null
        val image = previewImage ?: return
        image.setImageResource(android.R.drawable.ic_menu_gallery)
        val file = previewFile ?: return
        val target = AnimatingTarget(image)
        val request = ImageRequest.Builder(context)
            .data(file)
            .size(dialogWidth() - dp(36), importPreviewHeight())
            .memoryCacheKey("qq-import-preview:${file.length()}:${file.lastModified()}")
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(target)
            .build()
        previewRequest = CoverDisposable(imageLoader.enqueue(request), target)
    }

    private fun loadCover(target: ImageView, pack: PanelPack, sizeDp: Int): Disposable? {
        val itemId = pack.coverItemId ?: return null
        val coverPackId = pack.coverPackId ?: return null
        val imageTarget = AnimatingTarget(target)
        val request = ImageRequest.Builder(context)
            .data(QqPanelRepository.itemUri(coverPackId, itemId))
            .size(dp(sizeDp), dp(sizeDp))
            .memoryCacheKey("qq-import-cover:${pack.id}:$itemId:$sizeDp")
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(imageTarget)
            .build()
        val disposable = imageLoader.enqueue(request)
        return CoverDisposable(disposable, imageTarget)
    }

    private fun disposeContent() {
        chooserVisible = false
        chooserAdapter?.dispose()
        chooserAdapter = null
        confirmationCover?.dispose()
        confirmationCover = null
        previewRequest?.dispose()
        previewRequest = null
        previewImage = null
    }

    private fun title(value: String) = TextView(context).apply {
        text = value
        textSize = 20f
        setTextColor(foregroundColor)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun subtitle(value: String) = TextView(context).apply {
        text = value
        textSize = 14f
        setTextColor(secondaryColor)
    }

    private fun actionButton(label: String, filled: Boolean, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(if (filled) Color.WHITE else accentColor)
        background = roundedBackground(if (filled) accentColor else Color.TRANSPARENT, dp(12).toFloat())
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun packNameRow(pack: PanelPack, nameSize: Float) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = pack.displayName
            textSize = nameSize
            setTextColor(foregroundColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (pack.collapsed) {
            addView(collapsedBadge(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) })
        }
    }

    private fun collapsedBadge() = TextView(context).apply {
        text = "已折叠"
        textSize = 11f
        setTextColor(accentColor)
        setPadding(dp(6), dp(2), dp(6), dp(2))
        background = roundedBackground(badgeColor, dp(8).toFloat())
    }

    private inner class PackAdapter(
        private val items: List<PanelPack>,
        private val onClick: (PanelPack) -> Unit,
    ) : RecyclerView.Adapter<PackHolder>() {
        private val holders = mutableSetOf<PackHolder>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackHolder =
            PackHolder(createPackRow()).also(holders::add)

        override fun onBindViewHolder(holder: PackHolder, position: Int) = holder.bind(items[position], onClick)

        override fun getItemCount(): Int = items.size

        override fun onViewRecycled(holder: PackHolder) = holder.dispose()

        fun dispose() {
            holders.forEach(PackHolder::dispose)
            holders.clear()
        }
    }

    private inner class PackHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val row = itemView as LinearLayout
        private val cover = row.getChildAt(0) as ImageView
        private val labels = row.getChildAt(1) as LinearLayout
        private val nameRow = labels.getChildAt(0) as LinearLayout
        private val name = nameRow.getChildAt(0) as TextView
        private val collapsed = nameRow.getChildAt(1) as TextView
        private val count = labels.getChildAt(1) as TextView
        private var coverRequest: Disposable? = null

        fun bind(pack: PanelPack, onClick: (PanelPack) -> Unit) {
            dispose()
            cover.setImageResource(android.R.drawable.ic_menu_gallery)
            cover.contentDescription = "${pack.displayName} 封面"
            name.text = pack.displayName
            collapsed.visibility = if (pack.collapsed) View.VISIBLE else View.GONE
            count.text = "${pack.itemCount} 张表情"
            row.setOnClickListener { onClick(pack) }
            coverRequest = loadCover(cover, pack, PACK_COVER_SIZE_DP)
        }

        fun dispose() {
            coverRequest?.dispose()
            coverRequest = null
            cover.setImageDrawable(null)
        }
    }

    private fun createPackRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = roundedBackground(rowColor, dp(14).toFloat())
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PACK_ROW_HEIGHT_DP)).apply {
            bottomMargin = dp(6)
        }
        addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(PACK_COVER_SIZE_DP), dp(PACK_COVER_SIZE_DP)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    textSize = 16f
                    setTextColor(foregroundColor)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(collapsedBadge(), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(6) })
            })
            addView(TextView(context).apply {
                textSize = 13f
                setTextColor(secondaryColor)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(TextView(context).apply {
            text = "›"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(secondaryColor)
        }, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private class AnimatingTarget(private val imageView: ImageView) : ImageViewTarget(imageView) {
        override fun onStart(placeholder: Image?) {
            if (placeholder != null || imageView.drawable == null) super.onStart(placeholder)
        }

        override fun onSuccess(result: Image) {
            super.onSuccess(result)
            (imageView.drawable as? Animatable)?.start()
        }

        override fun onError(error: Image?) {
            if (error != null || imageView.drawable == null) super.onError(error)
        }

        fun stop() {
            (imageView.drawable as? Animatable)?.stop()
            imageView.setImageDrawable(null)
        }
    }

    private class CoverDisposable(
        private val delegate: Disposable,
        private val target: AnimatingTarget,
    ) : Disposable by delegate {
        override fun dispose() {
            delegate.dispose()
            target.stop()
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun dialogWidth(): Int =
        minOf(context.resources.displayMetrics.widthPixels - dp(32), dp(MAXIMUM_WIDTH_DP))

    private fun importPreviewHeight(): Int =
        minOf(dp(IMPORT_PREVIEW_HEIGHT_DP), (context.resources.displayMetrics.heightPixels * 0.22f).toInt())
            .coerceAtLeast(1)

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    companion object {
        private const val PACK_ROW_HEIGHT_DP = 70
        private const val PACK_ROW_SPACING_DP = 6
        private const val PACK_COVER_SIZE_DP = 50
        private const val CONFIRM_COVER_SIZE_DP = 72
        private const val IMPORT_PREVIEW_HEIGHT_DP = 150
        private const val ACTION_HEIGHT_DP = 44
        private const val MAXIMUM_WIDTH_DP = 440
        private var sharedImageLoader: ImageLoader? = null

        fun show(
            context: Context,
            packs: List<PanelPack>,
            pictureCount: Int,
            onImport: (String) -> Unit,
        ): EmoRepoImportDialog {
            require(packs.isNotEmpty()) { "EmoRepo 没有可导入的表情包" }
            return EmoRepoImportDialog(context, packs, pictureCount, onImport).also { it.show() }
        }

        private fun importImageLoader(context: Context): ImageLoader = synchronized(this) {
            sharedImageLoader ?: ImageLoader.Builder(context.applicationContext)
                .components {
                    if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory())
                    else add(GifDecoder.Factory())
                }
                .build()
                .also { sharedImageLoader = it }
        }
    }
}
