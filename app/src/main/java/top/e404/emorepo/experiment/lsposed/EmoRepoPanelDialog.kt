package top.e404.emorepo.experiment.lsposed

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.Image
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.target.ImageViewTarget
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import top.e404.emorepo.BuildConfig
import top.e404.emorepo.MainActivityActions
import top.e404.emorepo.ipc.EmoRepoIpcContract

/** QQ 内的独立 EmoRepo 底部抽屉，不替换 QQ 原生表情面板。 */
internal class EmoRepoPanelDialog private constructor(
    private val hostContext: Context,
    private val hostClassLoader: ClassLoader,
    private val contact: QqContact,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dialog = PreviewTrackingDialog(hostContext, ::dispatchTouchPreviewMotion)
    private val dark = hostContext.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val foregroundColor = if (dark) Color.WHITE else Color.rgb(30, 30, 30)
    private val secondaryColor = if (dark) Color.LTGRAY else Color.DKGRAY
    private val surfaceColor = if (dark) Color.rgb(38, 38, 42) else Color.WHITE
    private val selectedColor = if (dark) Color.rgb(45, 105, 175) else Color.rgb(210, 232, 255)
    private val root = DrawerDismissLayout(
        hostContext,
        ::canDragDrawer,
        ::canExpandDrawer,
        ::expandDrawer,
        ::finishDrawerDrag,
    )
    private val sheetHost = FrameLayout(hostContext)
    private val packTabs = RecyclerView(hostContext)
    private val contentList = RecyclerView(hostContext)
    private val globalStatus = TextView(hostContext)
    private val globalProgress = ProgressBar(hostContext)
    private val previewOverlay = FrameLayout(hostContext)
    private val previewCard = FrameLayout(hostContext)
    private val previewImage = ImageView(hostContext)
    private val previewProgress = ProgressBar(hostContext)
    private val destroyed = AtomicBoolean(false)
    private val sending = AtomicBoolean(false)
    private val imageLoader = panelImageLoader(hostContext)
    private var revision = 0L
    private var packs: List<PanelPack> = emptyList()
    private var panelColumns = DEFAULT_PANEL_COLUMNS
    private var tabAdapter: PackTabAdapter? = null
    private var contentAdapter: PanelContentAdapter? = null
    private var contentLayoutManager: GridLayoutManager? = null
    private var collapsedExpanded = false
    private var activePackPosition = RecyclerView.NO_POSITION
    private var drawerState = DrawerState.COLLAPSED
    private var previewGeneration = 0
    private var previewDisposable: Disposable? = null
    private var touchPreviewActive = false
    private var touchPreviewPosition = RecyclerView.NO_POSITION
    private var previewEndedAt = 0L
    private val previewPreloadTasks = mutableListOf<Future<*>>()
    private var autoExpandPending = false

    fun show() {
        buildContent()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            destroyed.set(true)
            finishTouchPreview("面板关闭")
            stopPreviewPreload()
            tabAdapter?.dispose()
            contentAdapter?.dispose()
            synchronized(companionLock) {
                if (current === this) current = null
            }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.52f }
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        loadPacks()
    }

    private fun buildContent() {
        root.orientation = LinearLayout.VERTICAL
        // 空白把手和标题不是可点击控件，根容器需主动接住 DOWN 才能继续收到拖动事件。
        root.isClickable = true
        root.setPadding(dp(4), dp(4), dp(4), dp(4))
        root.background = roundedBackground(surfaceColor, dp(20).toFloat())

        root.addView(
            View(hostContext).apply {
                background = roundedBackground(if (dark) Color.DKGRAY else Color.LTGRAY, dp(2).toFloat())
            },
            LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
                bottomMargin = dp(2)
            },
        )

        packTabs.layoutManager = LinearLayoutManager(hostContext, RecyclerView.HORIZONTAL, false)
        packTabs.itemAnimator = null
        packTabs.overScrollMode = View.OVER_SCROLL_NEVER
        packTabs.setBackgroundColor(surfaceColor)
        packTabs.elevation = dp(4).toFloat()

        val content = FrameLayout(hostContext)
        contentList.itemAnimator = null
        contentList.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        contentList.setPadding(0, dp(2), 0, dp(PACK_TAB_HEIGHT_DP + 2))
        contentList.clipToPadding = false
        contentList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) {
                    syncActivePackFromScroll()
                    maybeAutoExpandCollapsed(dy)
                }
            }
        })
        content.addView(
            contentList,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        globalStatus.gravity = Gravity.CENTER
        globalStatus.textSize = 16f
        globalStatus.setTextColor(secondaryColor)
        globalStatus.setPadding(dp(24))
        content.addView(
            globalStatus,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        content.addView(globalProgress, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        sheetHost.isClickable = true
        sheetHost.setOnClickListener { dismiss() }
        sheetHost.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                drawerHeight(EXPANDED_HEIGHT_RATIO),
                Gravity.BOTTOM,
            ),
        )
        root.translationY = collapsedDrawerOffset()
        sheetHost.addView(
            packTabs,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(PACK_TAB_HEIGHT_DP),
                Gravity.BOTTOM,
            ),
        )
        buildTouchPreview()
        sheetHost.addView(
            previewOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(sheetHost)
        showGlobalStatus("正在读取 EmoRepo…", loading = true)
    }

    private fun buildTouchPreview() {
        previewOverlay.visibility = View.GONE
        previewOverlay.isClickable = false
        previewOverlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        previewCard.apply {
            background = roundedBackground(if (dark) Color.rgb(28, 28, 32) else Color.WHITE, dp(18).toFloat())
            elevation = dp(10).toFloat()
        }
        previewImage.scaleType = ImageView.ScaleType.FIT_CENTER
        previewImage.setPadding(dp(10))
        previewCard.addView(
            previewImage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        previewCard.addView(previewProgress, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        previewOverlay.addView(
            previewCard,
            FrameLayout.LayoutParams(
                dp(PREVIEW_CARD_SIZE_DP),
                dp(PREVIEW_CARD_SIZE_DP),
                Gravity.TOP or Gravity.START,
            ),
        )
    }

    private fun loadPacks() {
        val cached = synchronized(companionLock) { cachedPanelSnapshot }
        if (cached != null) applyPanelSnapshot(cached)
        refreshPanelSnapshot(hasVisibleCache = cached != null)
    }

    private fun refreshPanelSnapshot(hasVisibleCache: Boolean) {
        metadataWorker.execute {
            runCatching {
                val configuration = QqPanelRepository.configuration(hostContext)
                val currentRevision = QqPanelRepository.revision(hostContext)
                val currentPacks = QqPanelRepository.listPacks(hostContext)
                PanelSnapshot(configuration, currentRevision, currentPacks)
            }.onSuccess { snapshot ->
                val previous = synchronized(companionLock) {
                    val old = cachedPanelSnapshot
                    if (old?.revision != snapshot.revision) panelItemCache.clear()
                    cachedPanelSnapshot = snapshot
                    old
                }
                mainHandler.post {
                    if (destroyed.get()) return@post
                    if (previous != snapshot) applyPanelSnapshot(snapshot)
                }
            }.onFailure { error ->
                mainHandler.post {
                    if (destroyed.get()) return@post
                    if (hasVisibleCache) {
                        QqPanelIntegration.log("QQ 面板后台刷新失败，继续使用进程缓存", error)
                    } else {
                        showGlobalError(error.message ?: "EmoRepo 仓库读取失败", ::loadPacks)
                    }
                }
            }
        }
    }

    private fun applyPanelSnapshot(snapshot: PanelSnapshot) {
        panelColumns = snapshot.configuration.columns
        revision = snapshot.revision
        packs = orderPanelPacksForBrowsing(
            snapshot.packs.filter { pack ->
                pack.id == EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID || pack.itemCount > 0
            },
        )
        if (packs.isEmpty()) {
            showGlobalError("EmoRepo 暂时没有表情包", ::loadPacks)
        } else {
            showContent()
            bindPacks()
        }
    }

    private fun bindPacks() {
        tabAdapter?.dispose()
        contentAdapter?.dispose()
        collapsedExpanded = false
        tabAdapter = PackTabAdapter(packs).also { packTabs.adapter = it }
        val adapter = PanelContentAdapter(packs).also { contentAdapter = it }
        val layoutManager = GridLayoutManager(hostContext, panelColumns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.isHeader(position)) panelColumns else 1
            }
        }
        contentLayoutManager = layoutManager
        contentList.layoutManager = layoutManager
        contentList.adapter = adapter
        adapter.loadVisibleSections()
        val initial = visiblePanelPackPositions(packs, collapsedExpanded).firstOrNull() ?: 0
        selectPack(initial)
        contentList.post { adapter.refreshTrailingSpace() }
    }

    private fun selectPack(position: Int) {
        if (position !in packs.indices) return
        val adapterPosition = contentAdapter?.headerPositionForPack(position) ?: return
        activatePack(position)
        val layoutManager = contentLayoutManager ?: return
        layoutManager.scrollToPositionWithOffset(adapterPosition, dp(2))
    }

    private fun activatePack(position: Int) {
        if (position !in packs.indices) return
        if (activePackPosition != position) {
            activePackPosition = position
            startPreviewPreload(contentAdapter?.itemsForPack(position).orEmpty(), packs[position].displayName)
        }
        tabAdapter?.select(position)
        ensurePackTabVisible(position)
    }

    private fun ensurePackTabVisible(packPosition: Int) {
        val tabPosition = tabAdapter?.tabPositionForPack(packPosition) ?: return
        packTabs.post {
            if (destroyed.get()) return@post
            val layoutManager = packTabs.layoutManager as? LinearLayoutManager ?: return@post
            val first = layoutManager.findFirstCompletelyVisibleItemPosition()
            val last = layoutManager.findLastCompletelyVisibleItemPosition()
            if (!shouldRepositionPackTab(tabPosition, first, last)) return@post
            val centeredOffset = ((packTabs.width - dp(PACK_TAB_WIDTH_DP)) / 2).coerceAtLeast(0)
            layoutManager.scrollToPositionWithOffset(tabPosition, centeredOffset)
        }
    }

    private fun syncActivePackFromScroll() {
        val first = contentLayoutManager?.findFirstVisibleItemPosition()
            ?.takeIf { it != RecyclerView.NO_POSITION } ?: return
        contentAdapter?.packPositionAt(first)?.let(::activatePack)
    }

    private fun maybeAutoExpandCollapsed(verticalDelta: Int) {
        val layoutManager = contentLayoutManager ?: return
        val adapter = contentAdapter ?: return
        if (!autoExpandPending &&
            shouldAutoExpandCollapsed(
                collapsedExpanded = collapsedExpanded,
                hasCollapsedPacks = packs.any(PanelPack::collapsed),
                userScrollActive = contentList.scrollState != RecyclerView.SCROLL_STATE_IDLE,
                verticalDelta = verticalDelta,
                lastVisiblePosition = layoutManager.findLastVisibleItemPosition(),
                lastContentPosition = adapter.lastContentPosition(),
            )
        ) {
            autoExpandPending = true
            contentList.post {
                autoExpandPending = false
                if (destroyed.get() || collapsedExpanded) return@post
                QqPanelIntegration.log("纵向滚动到普通分组末尾，自动展开折叠表情包")
                updateCollapsedExpanded(expanded = true, revealTab = false)
            }
        }
    }

    private fun updateCollapsedExpanded(expanded: Boolean, revealTab: Boolean) {
        if (collapsedExpanded == expanded) return
        collapsedExpanded = expanded
        tabAdapter?.refreshCollapsedEntries(revealTab)
        contentAdapter?.setCollapsedExpanded(expanded)
        if (!expanded && packs.getOrNull(activePackPosition)?.collapsed == true) stopPreviewPreload()
    }

    private fun canDragDrawer(touchY: Float): Boolean {
        // 顶部把手、标题和表情包栏始终可以拖动抽屉，不受网格滚动位置影响。
        if (contentList.top > 0 && touchY < contentList.top) return true
        return !contentList.canScrollVertically(-1)
    }

    private fun canExpandDrawer(): Boolean = drawerState == DrawerState.COLLAPSED

    private fun expandDrawer() {
        if (drawerState != DrawerState.COLLAPSED) return
        animateDrawerState(DrawerState.EXPANDED)
    }

    private fun finishDrawerDrag(distance: Float, velocityY: Float) {
        val shouldAdvance = distance >= drawerHeight(COLLAPSED_HEIGHT_RATIO) * DRAWER_CLOSE_RATIO ||
            velocityY >= DRAWER_CLOSE_VELOCITY
        if (!shouldAdvance) {
            animateDrawerState(drawerState)
        } else if (drawerState == DrawerState.EXPANDED) {
            animateDrawerState(DrawerState.COLLAPSED)
        } else {
            root.animate()
                .translationY(root.height.toFloat())
                .setDuration(DRAWER_CLOSE_DURATION_MS)
                .withEndAction(::dismiss)
                .start()
            packTabs.animate()
                .translationY(packTabs.height.toFloat())
                .setDuration(DRAWER_CLOSE_DURATION_MS)
                .start()
        }
    }

    private fun animateDrawerState(targetState: DrawerState) {
        drawerState = targetState
        root.animate().cancel()
        val targetTranslation = if (targetState == DrawerState.EXPANDED) 0f else collapsedDrawerOffset()
        root.animate()
            .translationY(targetTranslation)
            .setDuration(DRAWER_RESIZE_DURATION_MS)
            .start()
    }

    private fun collapsedDrawerOffset(): Float =
        (drawerHeight(EXPANDED_HEIGHT_RATIO) - drawerHeight(COLLAPSED_HEIGHT_RATIO)).toFloat()

    private fun drawerHeight(ratio: Float): Int =
        (hostContext.resources.displayMetrics.heightPixels * ratio).toInt()

    private fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun send(item: PanelItem) {
        if (!sending.compareAndSet(false, true)) return
        dismiss()
        imageWorker.execute {
            runCatching { QqPanelFileCache.acquire(hostContext, item.packId, item) }
                .onSuccess { lease ->
                    mainHandler.post {
                        QqMessageSender.send(hostClassLoader, contact, lease.file) { result ->
                            try {
                                if (result.successful) {
                                    usageWorker.execute {
                                        runCatching {
                                            QqPanelRepository.recordUse(
                                                hostContext,
                                                item.packId,
                                                item.id,
                                                System.currentTimeMillis(),
                                            )
                                        }.onSuccess {
                                            updateRecentPanelCache(item)
                                        }.onFailure { error ->
                                            QqPanelIntegration.log("QQ 已发送，但最近使用记录失败", error)
                                        }
                                    }
                                } else {
                                    QqPanelIntegration.log(
                                        "QQ 表情发送失败：code=${result.code}, message=${result.message.orEmpty()}",
                                    )
                                }
                            } finally {
                                lease.close()
                            }
                        }
                    }
                }.onFailure { error ->
                    QqPanelIntegration.log("QQ 表情原图读取失败", error)
                }
        }
    }

    private fun showTouchPreview(item: PanelItem, anchor: View) {
        val requestGeneration = ++previewGeneration
        positionTouchPreview(anchor)
        previewDisposable?.dispose()
        previewDisposable = null
        previewImage.setImageDrawable(null)
        previewImage.contentDescription = "表情预览"
        previewProgress.visibility = View.VISIBLE
        previewOverlay.visibility = View.VISIBLE
        imageWorker.execute {
            runCatching { QqPanelFileCache.acquire(hostContext, item.packId, item) }
                .onSuccess { lease ->
                    mainHandler.post {
                        if (destroyed.get() || requestGeneration != previewGeneration) {
                            lease.close()
                            return@post
                        }
                        previewDisposable = LeaseDisposable(
                            loadItemImage(previewImage, lease.file, item, PREVIEW_SIZE_PX),
                            lease,
                        )
                        previewProgress.visibility = View.GONE
                    }
                }.onFailure { error ->
                    mainHandler.post {
                        if (requestGeneration == previewGeneration) {
                            hideTouchPreview()
                            showToast(error.message ?: "预览读取失败")
                        }
                    }
                }
        }
    }

    private fun positionTouchPreview(anchor: View) {
        val cardSize = dp(PREVIEW_CARD_SIZE_DP)
        val margin = dp(PREVIEW_ANCHOR_MARGIN_DP)
        val hostLocation = IntArray(2).also(sheetHost::getLocationOnScreen)
        val anchorLocation = IntArray(2).also(anchor::getLocationOnScreen)
        val anchorLeft = anchorLocation[0] - hostLocation[0]
        val anchorTop = anchorLocation[1] - hostLocation[1]
        val anchorBottom = anchorTop + anchor.height
        val maximumLeft = maxOf(margin, sheetHost.width - cardSize - margin)
        val maximumTop = maxOf(margin, sheetHost.height - cardSize - margin)
        val centeredLeft = anchorLeft + (anchor.width - cardSize) / 2
        val aboveTop = anchorTop - cardSize - margin
        val preferredTop = if (aboveTop >= margin) aboveTop else anchorBottom + margin
        previewCard.layoutParams = (previewCard.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = centeredLeft.coerceIn(margin, maximumLeft)
            topMargin = preferredTop.coerceIn(margin, maximumTop)
        }
    }

    private fun hideTouchPreview() {
        previewGeneration += 1
        previewDisposable?.dispose()
        previewDisposable = null
        previewImage.setImageDrawable(null)
        previewImage.contentDescription = null
        previewProgress.visibility = View.GONE
        previewOverlay.visibility = View.GONE
    }

    private fun dispatchTouchPreviewMotion(event: MotionEvent): Boolean {
        if (!touchPreviewActive) return false
        handleTouchPreviewMotion(event)
        return true
    }

    private fun showGlobalStatus(message: String, loading: Boolean) {
        globalStatus.setOnClickListener(null)
        globalStatus.text = message
        globalStatus.visibility = View.VISIBLE
        globalProgress.visibility = if (loading) View.VISIBLE else View.GONE
        contentList.visibility = View.GONE
        packTabs.visibility = View.GONE
    }

    private fun showGlobalError(message: String, retry: () -> Unit) {
        showGlobalStatus("$message\n\n点击重试", loading = false)
        globalStatus.setOnClickListener { retry() }
    }

    private fun showContent() {
        globalStatus.visibility = View.GONE
        globalProgress.visibility = View.GONE
        contentList.visibility = View.VISIBLE
        packTabs.visibility = View.VISIBLE
    }

    private fun showToast(message: String) {
        mainHandler.post { Toast.makeText(hostContext, message, Toast.LENGTH_SHORT).show() }
    }

    private fun loadItemImage(target: ImageView, file: File, item: PanelItem, size: Int): Disposable =
        enqueueAnimated(
            target,
            ImageRequest.Builder(hostContext)
                .data(file)
                .size(size, size)
                .memoryCacheKey(itemMemoryCacheKey(item, size))
                .memoryCachePolicy(if (item.animated) CachePolicy.DISABLED else CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build(),
        )

    private fun itemMemoryCacheKey(item: PanelItem, size: Int): String =
        "qq-panel:${item.id}:$size"

    private fun loadPackCover(target: ImageView, pack: PanelPack): Disposable? {
        val itemId = pack.coverItemId ?: return null
        val coverPackId = pack.coverPackId ?: return null
        return enqueueAnimated(
            target,
            ImageRequest.Builder(hostContext)
                .data(QqPanelRepository.itemUri(coverPackId, itemId))
                .size(dp(PACK_COVER_SIZE_DP), dp(PACK_COVER_SIZE_DP))
                .memoryCacheKey("qq-panel-cover:$revision:${pack.id}:$itemId")
                .diskCachePolicy(CachePolicy.DISABLED)
                .build(),
        )
    }

    private fun enqueueAnimated(target: ImageView, request: ImageRequest): Disposable {
        val imageTarget = AlwaysAnimatingImageViewTarget(target)
        val disposable = imageLoader.enqueue(request.newBuilder().target(imageTarget).build())
        return AnimationDisposable(disposable, imageTarget)
    }

    private inner class PackTabAdapter(
        private val items: List<PanelPack>,
    ) : RecyclerView.Adapter<PackTabHolder>() {
        private var selectedPackPosition = RecyclerView.NO_POSITION
        private val holders = mutableSetOf<PackTabHolder>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackTabHolder =
            PackTabHolder(createPackTab()).also(holders::add)

        override fun onBindViewHolder(holder: PackTabHolder, position: Int) {
            when (val entry = entries()[position]) {
                is PanelTabEntry.Pack -> {
                    val packPosition = entry.packPosition
                    val pack = items[packPosition]
                    holder.bind(pack, packPosition == selectedPackPosition) {
                        selectPack(packPosition)
                    }
                }
                PanelTabEntry.Collapsed -> {
                    holder.bindCollapsed(
                        expanded = collapsedExpanded,
                        selected = !collapsedExpanded && items.getOrNull(selectedPackPosition)?.collapsed == true,
                        onClick = ::toggleCollapsedEntries,
                    )
                }
                PanelTabEntry.Settings -> holder.bindSettings(::openEmoRepoSettings)
            }
        }

        override fun getItemCount(): Int = entries().size

        override fun onViewRecycled(holder: PackTabHolder) {
            holder.dispose()
        }

        fun select(position: Int) {
            if (position == selectedPackPosition) return
            val previousTab = tabPositionForPack(selectedPackPosition)
            selectedPackPosition = position
            previousTab?.let(::notifyItemChanged)
            tabPositionForPack(position)?.let(::notifyItemChanged)
        }

        fun tabPositionForPack(position: Int): Int? {
            if (position == RecyclerView.NO_POSITION) return null
            val entries = entries()
            entries.indexOf(PanelTabEntry.Pack(position)).takeIf { it >= 0 }?.let { return it }
            return entries.indexOf(PanelTabEntry.Collapsed)
                .takeIf { it >= 0 && items.getOrNull(position)?.collapsed == true }
        }

        private fun toggleCollapsedEntries() {
            updateCollapsedExpanded(!collapsedExpanded, revealTab = true)
        }

        fun refreshCollapsedEntries(revealTab: Boolean) {
            notifyDataSetChanged()
            if (!revealTab) return
            val foldPosition = entries().indexOf(PanelTabEntry.Collapsed).takeIf { it >= 0 } ?: return
            val target = if (collapsedExpanded) foldPosition + 1 else foldPosition
            mainHandler.post { packTabs.smoothScrollToPosition(target.coerceAtMost(itemCount - 1)) }
        }

        private fun entries(): List<PanelTabEntry> = panelTabEntries(items, collapsedExpanded)

        fun dispose() {
            holders.forEach(PackTabHolder::dispose)
            holders.clear()
        }
    }

    private inner class PackTabHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container = itemView as LinearLayout
        private val cover = container.getChildAt(0) as ImageView
        private val name = container.getChildAt(1) as TextView
        private var disposable: Disposable? = null

        fun bind(pack: PanelPack, selected: Boolean, onClick: () -> Unit) {
            disposable?.dispose()
            cover.setImageDrawable(null)
            cover.contentDescription = "${pack.displayName} 封面"
            name.text = pack.displayName
            container.background = roundedBackground(
                if (selected) selectedColor else Color.TRANSPARENT,
                dp(12).toFloat(),
            )
            container.setOnClickListener { onClick() }
            disposable = loadPackCover(cover, pack)
            if (disposable == null && pack.id == EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID) {
                cover.setImageResource(android.R.drawable.ic_menu_recent_history)
            }
        }

        fun bindSettings(onClick: () -> Unit) {
            disposable?.dispose()
            disposable = null
            cover.setImageResource(android.R.drawable.ic_menu_preferences)
            cover.contentDescription = "打开 EmoRepo 设置"
            name.text = "设置"
            container.background = roundedBackground(Color.TRANSPARENT, dp(12).toFloat())
            container.setOnClickListener { onClick() }
        }

        fun bindCollapsed(expanded: Boolean, selected: Boolean, onClick: () -> Unit) {
            disposable?.dispose()
            disposable = null
            cover.setImageDrawable(ArchiveIconDrawable(foregroundColor))
            cover.contentDescription = if (expanded) "收起折叠表情包" else "展开折叠表情包"
            name.text = "折叠"
            container.background = roundedBackground(
                if (selected) selectedColor else Color.TRANSPARENT,
                dp(12).toFloat(),
            )
            container.setOnClickListener { onClick() }
        }

        fun dispose() {
            disposable?.dispose()
            disposable = null
            cover.setImageDrawable(null)
        }
    }

    private fun createPackTab(): LinearLayout = LinearLayout(hostContext).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(2), dp(2), dp(2), dp(1))
        layoutParams = RecyclerView.LayoutParams(dp(PACK_TAB_WIDTH_DP), dp(66)).apply {
            marginEnd = dp(1)
        }
        addView(
            ImageView(hostContext).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.TRANSPARENT)
            },
            LinearLayout.LayoutParams(dp(PACK_COVER_SIZE_DP), dp(PACK_COVER_SIZE_DP)),
        )
        addView(
            TextView(hostContext).apply {
                gravity = Gravity.CENTER
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(foregroundColor)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)),
        )
    }

    private fun openEmoRepoSettings() {
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN)
                .setClassName(BuildConfig.APPLICATION_ID, MainActivityActions.MAIN_ACTIVITY_CLASS)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            PendingIntent.getActivity(
                hostContext,
                SETTINGS_PENDING_INTENT_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).send()
        }.onFailure { error ->
            QqPanelIntegration.log("打开 EmoRepo 设置失败", error)
        }
    }

    private inner class PanelContentAdapter(
        private val allPacks: List<PanelPack>,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val states = mutableSetOf<CellState>()
        private val sectionItems = mutableMapOf<Int, List<PanelItem>>()
        private var visiblePacks = visiblePanelPackPositions(allPacks, collapsedExpanded)
        private var entries = buildEntries()
        private var generation = 0
        private var memoryHits = 0
        private var memoryMisses = 0
        private var firstFrameHits = 0
        private var firstFrameMisses = 0

        override fun getItemCount(): Int = entries.size

        override fun getItemViewType(position: Int): Int = when (entries[position]) {
            is ContentEntry.Header -> CONTENT_HEADER_TYPE
            is ContentEntry.Item -> CONTENT_ITEM_TYPE
            ContentEntry.Footer -> CONTENT_FOOTER_TYPE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == CONTENT_HEADER_TYPE) {
                val row = LinearLayout(hostContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(CONTENT_HEADER_HEIGHT_DP),
                    )
                    setPadding(dp(8), dp(8), dp(8), dp(4))
                    addView(TextView(hostContext).apply {
                        textSize = 14f
                        setTextColor(foregroundColor)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(hostContext).apply {
                        textSize = 12f
                        setTextColor(secondaryColor)
                    })
                }
                HeaderHolder(row)
            } else if (viewType == CONTENT_ITEM_TYPE) {
                ItemHolder(ImageView(hostContext).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellSize())
                    setPadding(dp(1))
                    setBackgroundColor(Color.TRANSPARENT)
                })
            } else {
                FooterHolder(View(hostContext).apply { setBackgroundColor(Color.TRANSPARENT) })
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val entry = entries[position]) {
                is ContentEntry.Header -> (holder as HeaderHolder).bind(
                    allPacks[entry.packPosition],
                    sectionItems[entry.packPosition]?.size,
                )
                is ContentEntry.Item -> bindItem(
                    (holder as ItemHolder).image,
                    sectionItems[entry.packPosition]?.getOrNull(entry.itemPosition),
                    position,
                )
                ContentEntry.Footer -> (holder as FooterHolder).bind(trailingSpaceHeight())
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is ItemHolder) releaseCell(holder.image)
        }

        fun isHeader(position: Int): Boolean = when (entries.getOrNull(position)) {
            is ContentEntry.Header, ContentEntry.Footer -> true
            else -> false
        }

        fun headerPositionForPack(packPosition: Int): Int? =
            entries.indexOfFirst { it == ContentEntry.Header(packPosition) }.takeIf { it >= 0 }

        fun packPositionAt(adapterPosition: Int): Int? = when (val entry = entries.getOrNull(adapterPosition)) {
            is ContentEntry.Header -> entry.packPosition
            is ContentEntry.Item -> entry.packPosition
            ContentEntry.Footer -> visiblePacks.lastOrNull()
            null -> null
        }

        fun itemAtAdapterPosition(adapterPosition: Int): PanelItem? =
            (entries.getOrNull(adapterPosition) as? ContentEntry.Item)?.let { entry ->
                sectionItems[entry.packPosition]?.getOrNull(entry.itemPosition)
            }

        fun itemsForPack(packPosition: Int): List<PanelItem>? = sectionItems[packPosition]

        fun lastContentPosition(): Int = entries.indexOfLast { entry -> entry != ContentEntry.Footer }

        fun refreshTrailingSpace() {
            entries.lastIndex.takeIf { entries.getOrNull(it) == ContentEntry.Footer }?.let(::notifyItemChanged)
        }

        fun setCollapsedExpanded(expanded: Boolean) {
            val anchor = captureAnchor()
            visiblePacks = visiblePanelPackPositions(allPacks, expanded)
            entries = buildEntries()
            notifyDataSetChanged()
            loadVisibleSections()
            contentList.post {
                restoreAnchor(anchor)
                syncActivePackFromScroll()
            }
        }

        fun loadVisibleSections() {
            val requestGeneration = generation
            visiblePacks.forEach { packPosition ->
                val pack = allPacks[packPosition]
                val cacheKey = panelItemCacheKey(revision, pack)
                val cached = panelItemCache[cacheKey]
                if (cached != null) applyItems(packPosition, cached)
                val dynamic = pack.id == EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID ||
                    pack.id == EmoRepoIpcContract.VIRTUAL_RECENTLY_ADDED_PACK_ID
                if (cached == null || dynamic) {
                    metadataWorker.execute {
                        runCatching { QqPanelRepository.listItems(hostContext, pack) }
                            .onSuccess { items ->
                                panelItemCache[cacheKey] = items
                                mainHandler.post {
                                    if (!destroyed.get() && generation == requestGeneration) {
                                        applyItems(packPosition, items)
                                    }
                                }
                            }.onFailure { error ->
                                if (cached == null) {
                                    QqPanelIntegration.log("读取连续表情分组失败：${pack.displayName}", error)
                                } else {
                                    QqPanelIntegration.log("刷新连续表情分组失败，继续使用缓存：${pack.displayName}", error)
                                }
                            }
                    }
                }
            }
        }

        private fun applyItems(packPosition: Int, items: List<PanelItem>) {
            if (sectionItems[packPosition] == items) return
            val header = headerPositionForPack(packPosition)
            val expected = entries.count { entry ->
                entry is ContentEntry.Item && entry.packPosition == packPosition
            }
            sectionItems[packPosition] = items
            if (header != null && expected == items.size) {
                notifyItemRangeChanged(header, items.size + 1)
            } else if (header != null) {
                val anchor = captureAnchor()
                entries = buildEntries()
                notifyDataSetChanged()
                contentList.post { restoreAnchor(anchor) }
            }
            if (packPosition == activePackPosition) {
                startPreviewPreload(items, allPacks[packPosition].displayName)
            }
        }

        private fun buildEntries(): List<ContentEntry> = buildList {
            visiblePacks.forEach { packPosition ->
                add(ContentEntry.Header(packPosition))
                val itemCount = sectionItems[packPosition]?.size ?: allPacks[packPosition].itemCount
                repeat(itemCount) { itemPosition -> add(ContentEntry.Item(packPosition, itemPosition)) }
            }
            add(ContentEntry.Footer)
        }

        private fun captureAnchor(): ContentAnchor? {
            val layoutManager = contentLayoutManager ?: return null
            val position = layoutManager.findFirstVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION }
                ?: return null
            val view = layoutManager.findViewByPosition(position) ?: return null
            return ContentAnchor(entryKey(entries[position]), view.top - contentList.paddingTop)
        }

        private fun restoreAnchor(anchor: ContentAnchor?) {
            if (anchor == null) return
            val position = entries.indexOfFirst { entry -> entryKey(entry) == anchor.key }
            if (position >= 0) contentLayoutManager?.scrollToPositionWithOffset(position, anchor.offset)
        }

        private fun entryKey(entry: ContentEntry): String = when (entry) {
            is ContentEntry.Header -> "header:${allPacks[entry.packPosition].id}"
            is ContentEntry.Item -> {
                val pack = allPacks[entry.packPosition]
                val item = sectionItems[entry.packPosition]?.getOrNull(entry.itemPosition)
                "item:${pack.id}:${item?.packId.orEmpty()}:${item?.id ?: entry.itemPosition}"
            }
            ContentEntry.Footer -> "footer"
        }

        private fun trailingSpaceHeight(): Int {
            val lastPackPosition = visiblePacks.lastOrNull() ?: return 0
            val itemCount = sectionItems[lastPackPosition]?.size ?: allPacks[lastPackPosition].itemCount
            val rows = (itemCount + panelColumns - 1) / panelColumns
            val viewport = contentList.height - contentList.paddingTop - contentList.paddingBottom
            return (viewport - dp(CONTENT_HEADER_HEIGHT_DP) - rows * cellSize()).coerceAtLeast(0)
        }

        private fun bindItem(image: ImageView, item: PanelItem?, adapterPosition: Int) {
            releaseCell(image)
            image.setImageDrawable(null)
            image.layoutParams = (image.layoutParams as RecyclerView.LayoutParams).apply { height = cellSize() }
            image.contentDescription = item?.fileName ?: "正在读取表情"
            image.setOnClickListener(null)
            image.setOnLongClickListener(null)
            if (item == null) return
            image.setOnClickListener {
                if (SystemClock.uptimeMillis() - previewEndedAt >= PREVIEW_CLICK_SUPPRESSION_MS) send(item)
            }
            image.setOnLongClickListener {
                val position = contentList.getChildAdapterPosition(image)
                    .takeIf { it != RecyclerView.NO_POSITION } ?: adapterPosition
                beginTouchPreview(position, item, image)
                true
            }
            val state = CellState(item.packId, item.id)
            image.tag = state
            states += state
            val size = cellSize()
            val firstFrame = QqPanelFirstFrameCache.get(item.id)
            if (firstFrame != null) {
                firstFrameHits += 1
                image.setImageDrawable(BitmapDrawable(hostContext.resources, firstFrame))
            } else {
                firstFrameMisses += 1
            }
            val memoryValue = if (item.animated) {
                null
            } else {
                imageLoader.memoryCache?.get(MemoryCache.Key(itemMemoryCacheKey(item, size)))
            }
            if (memoryValue != null) {
                memoryHits += 1
                state.cachedTarget = AlwaysAnimatingImageViewTarget(image).also { target ->
                    // 命中时同步恢复 Drawable，避免仍经过文件线程后才显示。
                    target.onSuccess(memoryValue.image)
                }
                // 显示不等待文件线程，但仍异步补上租约，保护动画使用的缓存原文件。
                state.fileTask = imageWorker.submit {
                    runCatching { QqPanelFileCache.acquire(hostContext, item.packId, item) }
                        .onSuccess { lease ->
                            mainHandler.post {
                                if (!destroyed.get() && state.active && image.tag === state) {
                                    state.cachedLease = lease
                                } else {
                                    lease.close()
                                }
                            }
                        }.onFailure { error ->
                            QqPanelIntegration.log("保护 QQ 面板内存缓存原文件失败", error)
                        }
                }
                return
            }
            memoryMisses += 1
            state.fileTask = imageWorker.submit {
                if (!state.active || Thread.currentThread().isInterrupted) return@submit
                runCatching { QqPanelFileCache.acquire(hostContext, item.packId, item) }
                    .onSuccess { lease ->
                        val loadedFirstFrame = firstFrame ?: runCatching {
                            QqPanelFirstFrameCache.load(
                                hostContext.cacheDir,
                                lease.file,
                                item.id,
                            )
                        }.onFailure { error ->
                            QqPanelIntegration.log(
                                "生成 QQ 面板首帧失败",
                                error,
                            )
                        }.getOrNull()
                        mainHandler.post {
                            if (!destroyed.get() && state.active && image.tag === state) {
                                if (image.drawable == null && loadedFirstFrame != null) {
                                    image.setImageDrawable(
                                        BitmapDrawable(hostContext.resources, loadedFirstFrame),
                                    )
                                }
                                state.disposable = LeaseDisposable(
                                    loadItemImage(image, lease.file, item, cellSize()),
                                    lease,
                                )
                            } else {
                                lease.close()
                            }
                        }
                    }.onFailure { error ->
                        QqPanelIntegration.log("加载 QQ 面板表情失败", error)
                    }
            }
        }

        private fun releaseCell(image: ImageView) {
            val state = image.tag as? CellState ?: return
            state.active = false
            state.fileTask?.cancel(false)
            state.disposable?.dispose()
            state.cachedTarget?.stop()
            state.cachedLease?.close()
            states.remove(state)
            image.tag = null
        }

        fun dispose() {
            generation += 1
            releaseStates(logStatistics = true)
        }

        private fun releaseStates(logStatistics: Boolean) {
            states.forEach {
                it.active = false
                it.fileTask?.cancel(false)
                it.disposable?.dispose()
                it.cachedTarget?.stop()
                it.cachedLease?.close()
            }
            states.clear()
            if (!logStatistics) return
            val cache = imageLoader.memoryCache
            QqPanelIntegration.log(
                "释放表情网格：memoryHits=$memoryHits，memoryMisses=$memoryMisses，" +
                    "firstFrameHits=$firstFrameHits，firstFrameMisses=$firstFrameMisses，" +
                    "cacheBytes=${cache?.size ?: 0}/${cache?.maxSize ?: 0}",
            )
        }

        private inner class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val row = itemView as LinearLayout
            private val name = row.getChildAt(0) as TextView
            private val count = row.getChildAt(1) as TextView

            fun bind(pack: PanelPack, loadedCount: Int?) {
                name.text = pack.displayName
                count.text = (loadedCount ?: pack.itemCount).toString()
            }
        }

        private inner class ItemHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

        private inner class FooterHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(height: Int) {
                itemView.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
            }
        }
    }

    private fun beginTouchPreview(position: Int, item: PanelItem, anchor: View) {
        touchPreviewActive = true
        touchPreviewPosition = position
        showTouchPreview(item, anchor)
    }

    private fun handleTouchPreviewMotion(event: MotionEvent) {
        if (!touchPreviewActive) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val location = IntArray(2)
                contentList.getLocationOnScreen(location)
                val child = contentList.findChildViewUnder(
                    event.rawX - location[0],
                    event.rawY - location[1],
                ) ?: return
                val position = contentList.getChildAdapterPosition(child)
                if (position != RecyclerView.NO_POSITION && position != touchPreviewPosition) {
                    contentAdapter?.itemAtAdapterPosition(position)?.let { item ->
                        touchPreviewPosition = position
                        showTouchPreview(item, child)
                    }
                }
            }

            MotionEvent.ACTION_UP -> finishTouchPreview("松手")
            MotionEvent.ACTION_CANCEL -> finishTouchPreview("手势取消")
        }
    }

    private fun finishTouchPreview(reason: String) {
        if (!touchPreviewActive) {
            hideTouchPreview()
            return
        }
        QqPanelIntegration.log("关闭按压预览：$reason")
        touchPreviewActive = false
        touchPreviewPosition = RecyclerView.NO_POSITION
        previewEndedAt = SystemClock.uptimeMillis()
        contentList.isPressed = false
        contentList.cancelLongPress()
        for (index in 0 until contentList.childCount) contentList.getChildAt(index).isPressed = false
        hideTouchPreview()
    }

    private fun startPreviewPreload(items: List<PanelItem>, groupName: String) {
        stopPreviewPreload()
        if (items.isEmpty()) return
        // 当前分组可见区优先，后台从后续项目开始并最终补齐首屏遗漏。
        val visibleBoundary = (panelColumns * PRELOAD_VISIBLE_ROWS).coerceAtMost(items.size)
        val ordered = items.drop(visibleBoundary) + items.take(visibleBoundary)
        val candidates = ordered.filter { item -> QqPanelFirstFrameCache.get(item.id) == null }
        if (candidates.isEmpty()) return
        val completed = AtomicInteger()
        val failed = AtomicInteger()
        candidates.forEach { item ->
            previewPreloadTasks += previewPreloadWorker.submit {
                if (Thread.currentThread().isInterrupted || destroyed.get()) return@submit
                val successful = runCatching {
                    QqPanelFirstFrameCache.preload(hostContext, item)
                }.onFailure { error ->
                    QqPanelIntegration.log("预加载 QQ 面板首帧失败", error)
                }.getOrDefault(false)
                if (!successful) failed.incrementAndGet()
                if (completed.incrementAndGet() == candidates.size) {
                    QqPanelIntegration.log(
                        "表情分组首帧预加载完成：group=$groupName，total=${candidates.size}，failed=${failed.get()}",
                    )
                }
            }
        }
    }

    private fun stopPreviewPreload() {
        previewPreloadTasks.forEach { task -> task.cancel(false) }
        previewPreloadTasks.clear()
    }

    /** 引用身份保持稳定，避免可变加载句柄改变哈希值后无法从集合移除。 */
    private class CellState(
        val packId: String,
        val itemId: String,
        var disposable: Disposable? = null,
        var cachedTarget: AlwaysAnimatingImageViewTarget? = null,
        var cachedLease: CachedFileLease? = null,
        var fileTask: Future<*>? = null,
        var active: Boolean = true,
    )

    private enum class DrawerState {
        COLLAPSED,
        EXPANDED,
    }

    private data class PanelSnapshot(
        val configuration: PanelConfiguration,
        val revision: Long,
        val packs: List<PanelPack>,
    )

    private data class PanelItemCacheKey(
        val revision: Long,
        val packId: String,
        val coverPackId: String?,
        val coverItemId: String?,
        val itemCount: Int,
    )

    private sealed interface ContentEntry {
        data class Header(val packPosition: Int) : ContentEntry
        data class Item(val packPosition: Int, val itemPosition: Int) : ContentEntry
        data object Footer : ContentEntry
    }

    private data class ContentAnchor(val key: String, val offset: Int)

    /** 长按激活后从窗口入口接管余下触摸，避免子 View 竞争导致预览提前取消。 */
    private class PreviewTrackingDialog(
        context: Context,
        private val onPreviewMotion: (MotionEvent) -> Boolean,
    ) : Dialog(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean =
            if (onPreviewMotion(event)) true else super.dispatchTouchEvent(event)
    }

    /** QQ 宿主没有 AndroidX Lifecycle，因此成功解码后必须显式启动动画。 */
    private class AlwaysAnimatingImageViewTarget(
        private val imageView: ImageView,
    ) : ImageViewTarget(imageView) {
        override fun onSuccess(result: Image) {
            super.onSuccess(result)
            (imageView.drawable as? Animatable)?.start()
        }

        override fun onStart(placeholder: Image?) {
            // 已显示首帧时保留它，直到完整静态图或 GIF 动画解码成功。
            if (placeholder != null || imageView.drawable == null) super.onStart(placeholder)
        }

        override fun onError(error: Image?) {
            // 动画恢复失败时保留首帧，不让已有内容重新变成空白。
            if (error != null || imageView.drawable == null) super.onError(error)
        }

        fun stop() {
            (imageView.drawable as? Animatable)?.stop()
            imageView.setImageDrawable(null)
        }
    }

    private class AnimationDisposable(
        private val delegate: Disposable,
        private val target: AlwaysAnimatingImageViewTarget,
    ) : Disposable by delegate {
        override fun dispose() {
            delegate.dispose()
            target.stop()
        }
    }

    private class LeaseDisposable(
        private val delegate: Disposable,
        private val lease: CachedFileLease,
    ) : Disposable by delegate {
        override fun dispose() {
            try {
                delegate.dispose()
            } finally {
                lease.close()
            }
        }
    }

    /** 只接管明确的纵向抽屉手势，避免和连续表情列表滚动冲突。 */
    private class DrawerDismissLayout(
        context: Context,
        private val canDrag: (touchY: Float) -> Boolean,
        private val canExpand: () -> Boolean,
        private val onExpand: () -> Unit,
        private val onRelease: (distance: Float, velocityY: Float) -> Unit,
    ) : LinearLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var downLocalY = 0f
        private var startTranslationY = 0f
        private var dragDistance = 0f
        private var dragMode = DragMode.NONE
        private var velocityTracker: VelocityTracker? = null

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downLocalY = event.y
                    startTranslationY = translationY
                    dragDistance = 0f
                    dragMode = DragMode.NONE
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val nextMode = requestedDragMode(event)
                    if (nextMode != DragMode.NONE) {
                        dragMode = nextMode
                        parent?.requestDisallowInterceptTouchEvent(true)
                        if (nextMode == DragMode.EXPAND) onExpand()
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycleTracker()
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            velocityTracker?.addMovement(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    // DOWN 落在空白把手时由根容器直接消费，后续 MOVE 不再经过拦截回调。
                    if (dragMode == DragMode.NONE) {
                        dragMode = requestedDragMode(event)
                        if (dragMode == DragMode.EXPAND) onExpand()
                    }
                    if (dragMode == DragMode.DOWN) {
                        dragDistance = maxOf(0f, event.rawY - downY)
                        translationY = startTranslationY + dragDistance
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (dragMode == DragMode.DOWN) {
                        velocityTracker?.computeCurrentVelocity(1000)
                        onRelease(dragDistance, velocityTracker?.yVelocity ?: 0f)
                    }
                    dragMode = DragMode.NONE
                    recycleTracker()
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragMode == DragMode.DOWN) onRelease(dragDistance, 0f)
                    dragMode = DragMode.NONE
                    recycleTracker()
                }
            }
            return true
        }

        private fun requestedDragMode(event: MotionEvent): DragMode {
            val dx = event.rawX - downX
            val dy = event.rawY - downY
            if (kotlin.math.abs(dy) <= touchSlop || kotlin.math.abs(dy) <= kotlin.math.abs(dx)) {
                return DragMode.NONE
            }
            return when {
                dy > 0f && canDrag(downLocalY) -> DragMode.DOWN
                dy < 0f && canExpand() -> DragMode.EXPAND
                else -> DragMode.NONE
            }
        }

        private fun recycleTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }

        private enum class DragMode {
            NONE,
            DOWN,
            EXPAND,
        }
    }

    private fun cellSize(): Int =
        ((contentList.width.takeIf { it > 0 } ?: hostContext.resources.displayMetrics.widthPixels) -
            dp(2) * (panelColumns - 1)) / panelColumns

    private fun dp(value: Int): Int =
        (value * hostContext.resources.displayMetrics.density + 0.5f).toInt()

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    /** 模块资源不能通过 QQ 的 Context 解析，因此直接绘制稳定的归档图标。 */
    private class ArchiveIconDrawable(color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val scale = minOf(bounds.width(), bounds.height()) / 24f
            if (scale <= 0f) return
            paint.strokeWidth = 2f * scale
            val left = bounds.centerX() - 12f * scale
            val top = bounds.centerY() - 12f * scale
            canvas.drawRoundRect(
                left + 5f * scale,
                top + 8f * scale,
                left + 19f * scale,
                top + 20f * scale,
                1f * scale,
                1f * scale,
                paint,
            )
            canvas.drawRoundRect(
                left + 3f * scale,
                top + 4f * scale,
                left + 21f * scale,
                top + 8f * scale,
                1f * scale,
                1f * scale,
                paint,
            )
            canvas.drawLine(
                left + 9f * scale,
                top + 13f * scale,
                left + 15f * scale,
                top + 13f * scale,
                paint,
            )
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Android Drawable 兼容接口")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        private val companionLock = Any()
        private val metadataWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "EmoRepo-Panel-Metadata")
        }
        private val usageWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "EmoRepo-Panel-Usage")
        }
        private val imageWorker: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "EmoRepo-Panel-Image")
        }
        private val previewPreloadWorker: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "EmoRepo-Preview-Preload").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY + 1
            }
        }
        private val panelItemCache = ConcurrentHashMap<PanelItemCacheKey, List<PanelItem>>()
        private var cachedPanelSnapshot: PanelSnapshot? = null
        private var current: EmoRepoPanelDialog? = null
        private var sharedImageLoader: ImageLoader? = null

        fun show(context: Context, hostClassLoader: ClassLoader, contact: QqContact) {
            Handler(Looper.getMainLooper()).post {
                synchronized(companionLock) {
                    current?.dialog?.dismiss()
                    EmoRepoPanelDialog(context, hostClassLoader, contact).also {
                        current = it
                        it.show()
                    }
                }
            }
        }

        fun dismissCurrent() {
            Handler(Looper.getMainLooper()).post {
                synchronized(companionLock) { current?.dialog?.dismiss() }
            }
        }

        private fun panelImageLoader(context: Context): ImageLoader = synchronized(companionLock) {
            sharedImageLoader ?: ImageLoader.Builder(context.applicationContext)
                .memoryCache {
                    MemoryCache.Builder().maxSizeBytes(IMAGE_MEMORY_CACHE_BYTES).build()
                }
                .components {
                    if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory())
                    else add(GifDecoder.Factory())
                }
                .build()
                .also { sharedImageLoader = it }
        }

        private fun panelItemCacheKey(revision: Long, pack: PanelPack) = PanelItemCacheKey(
            revision = revision,
            packId = pack.id,
            coverPackId = pack.coverPackId,
            coverItemId = pack.coverItemId,
            itemCount = pack.itemCount,
        )

        /** 成功发送后直接前移最近项，避免下一次打开等待 Provider 回读 CSV。 */
        private fun updateRecentPanelCache(item: PanelItem) = synchronized(companionLock) {
            val snapshot = cachedPanelSnapshot ?: return@synchronized
            val recentIndex = snapshot.packs.indexOfFirst {
                it.id == EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID
            }
            if (recentIndex < 0) return@synchronized
            val recentPack = snapshot.packs[recentIndex]
            val cachedRecent = panelItemCache.entries.firstOrNull { (key, _) ->
                key.revision == snapshot.revision &&
                    key.packId == EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID
            }?.value ?: return@synchronized
            val updatedItems = listOf(item) + cachedRecent.filterNot { recent ->
                recent.packId == item.packId && recent.id == item.id
            }
            val updatedPack = recentPack.copy(
                coverPackId = item.packId,
                coverItemId = item.id,
                itemCount = updatedItems.size,
            )
            val updatedPacks = snapshot.packs.toMutableList().apply {
                this[recentIndex] = updatedPack
            }
            panelItemCache.keys
                .filter { it.packId == EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID }
                .forEach(panelItemCache::remove)
            panelItemCache[panelItemCacheKey(snapshot.revision, updatedPack)] = updatedItems
            val addedIndex = updatedPacks.indexOfFirst {
                it.id == EmoRepoIpcContract.VIRTUAL_RECENTLY_ADDED_PACK_ID
            }
            if (addedIndex >= 0) {
                val addedPack = updatedPacks[addedIndex]
                val cachedAdded = panelItemCache.entries.firstOrNull { (key, _) ->
                    key.revision == snapshot.revision &&
                        key.packId == EmoRepoIpcContract.VIRTUAL_RECENTLY_ADDED_PACK_ID
                }?.value
                if (cachedAdded != null) {
                    val filteredAdded = cachedAdded.filterNot { added ->
                        added.packId == item.packId && added.id == item.id
                    }
                    panelItemCache.keys
                        .filter { it.packId == EmoRepoIpcContract.VIRTUAL_RECENTLY_ADDED_PACK_ID }
                        .forEach(panelItemCache::remove)
                    if (filteredAdded.isEmpty()) {
                        updatedPacks.removeAt(addedIndex)
                    } else {
                        val updatedAddedPack = addedPack.copy(
                            coverPackId = filteredAdded.first().packId,
                            coverItemId = filteredAdded.first().id,
                            itemCount = filteredAdded.size,
                        )
                        updatedPacks[addedIndex] = updatedAddedPack
                        panelItemCache[panelItemCacheKey(snapshot.revision, updatedAddedPack)] = filteredAdded
                    }
                }
            }
            cachedPanelSnapshot = snapshot.copy(packs = updatedPacks)
        }

        private const val DEFAULT_PANEL_COLUMNS = 4
        private const val COLLAPSED_HEIGHT_RATIO = 0.72f
        private const val EXPANDED_HEIGHT_RATIO = 0.92f
        private const val PACK_TAB_WIDTH_DP = 60
        private const val PACK_COVER_SIZE_DP = 38
        private const val PACK_TAB_HEIGHT_DP = 68
        private const val SETTINGS_PENDING_INTENT_REQUEST_CODE = 0xE404
        private const val PREVIEW_CARD_SIZE_DP = 190
        private const val PREVIEW_ANCHOR_MARGIN_DP = 8
        // 四列长包的基础像素和 GIF 帧缓冲会超过 64 MiB，保留明确上限但避免过早淘汰。
        private const val IMAGE_MEMORY_CACHE_BYTES = 128L * 1024L * 1024L
        private const val PREVIEW_SIZE_PX = 1024
        private const val PREVIEW_CLICK_SUPPRESSION_MS = 350L
        private const val CONTENT_HEADER_TYPE = 0
        private const val CONTENT_ITEM_TYPE = 1
        private const val CONTENT_FOOTER_TYPE = 2
        private const val CONTENT_HEADER_HEIGHT_DP = 36
        private const val PRELOAD_VISIBLE_ROWS = 8
        private const val DRAWER_CLOSE_RATIO = 0.22f
        private const val DRAWER_CLOSE_VELOCITY = 1_200f
        private const val DRAWER_CLOSE_DURATION_MS = 160L
        private const val DRAWER_RETURN_DURATION_MS = 180L
        private const val DRAWER_RESIZE_DURATION_MS = 220L
    }
}
