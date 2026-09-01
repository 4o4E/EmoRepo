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
import android.os.Parcelable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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
    private val pager = ViewPager2(hostContext)
    private val globalStatus = TextView(hostContext)
    private val globalProgress = ProgressBar(hostContext)
    private val previewOverlay = FrameLayout(hostContext)
    private val previewCard = FrameLayout(hostContext)
    private val previewImage = ImageView(hostContext)
    private val previewProgress = ProgressBar(hostContext)
    private val destroyed = AtomicBoolean(false)
    private val sending = AtomicBoolean(false)
    private val imageLoader = panelImageLoader(hostContext)
    private val visiblePages = mutableMapOf<String, PackPage>()
    private val packScrollStates = mutableMapOf<String, Parcelable>()
    private var revision = 0L
    private var packs: List<PanelPack> = emptyList()
    private var panelColumns = DEFAULT_PANEL_COLUMNS
    private var tabAdapter: PackTabAdapter? = null
    private var pageAdapter: PackPageAdapter? = null
    private var pageCallbackRegistered = false
    private var drawerState = DrawerState.COLLAPSED
    private var previewGeneration = 0
    private var previewDisposable: Disposable? = null
    private var touchPreviewOwner: PackPage? = null
    private var pagerTouchDownX = 0f
    private var pagerTouchDownY = 0f
    private var pagerTouchStartPosition = 0

    fun show() {
        buildContent()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            destroyed.set(true)
            hideTouchPreview()
            if (pageCallbackRegistered) {
                pager.unregisterOnPageChangeCallback(pageChangeCallback)
                pageCallbackRegistered = false
            }
            tabAdapter?.dispose()
            pageAdapter?.dispose()
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
        pager.offscreenPageLimit = 1
        (pager.getChildAt(0) as? RecyclerView)?.addOnItemTouchListener(
            object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
                    trackPagerLoopGesture(event)
                    return false
                }
            },
        )
        content.addView(
            pager,
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
            showPager()
            bindPacks()
        }
    }

    private fun bindPacks() {
        tabAdapter?.dispose()
        pageAdapter?.dispose()
        tabAdapter = PackTabAdapter(packs).also { packTabs.adapter = it }
        pageAdapter = PackPageAdapter(packs).also { pager.adapter = it }
        if (!pageCallbackRegistered) {
            pager.registerOnPageChangeCallback(pageChangeCallback)
            pageCallbackRegistered = true
        }
        val initial = packs.indexOfFirst { it.id == lastSelectedPackId }.takeIf { it >= 0 } ?: 0
        selectPack(initial, smoothScroll = false)
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            val pack = packs.getOrNull(position) ?: return
            lastSelectedPackId = pack.id
            tabAdapter?.select(position)
            tabAdapter?.tabPositionForPack(position)?.let(packTabs::smoothScrollToPosition)
            updatePreviewPreload(pack.id)
        }
    }

    private fun selectPack(position: Int, smoothScroll: Boolean) {
        if (position !in packs.indices) return
        pager.setCurrentItem(position, smoothScroll)
        lastSelectedPackId = packs[position].id
        tabAdapter?.select(position)
        tabAdapter?.tabPositionForPack(position)?.let(packTabs::smoothScrollToPosition)
        mainHandler.post { updatePreviewPreload(packs[position].id) }
    }

    private fun trackPagerLoopGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pagerTouchDownX = event.x
                pagerTouchDownY = event.y
                pagerTouchStartPosition = pager.currentItem
            }
            MotionEvent.ACTION_UP -> {
                panelHorizontalLoopTarget(
                    startPosition = pagerTouchStartPosition,
                    packCount = packs.size,
                    distanceX = event.x - pagerTouchDownX,
                    distanceY = event.y - pagerTouchDownY,
                    thresholdPixels = dp(PAGER_LOOP_SWIPE_DP).toFloat(),
                )?.let { target -> selectPack(target, smoothScroll = false) }
            }
        }
    }

    private fun updatePreviewPreload(selectedPackId: String?) {
        visiblePages.forEach { (packId, page) -> page.setPageActive(packId == selectedPackId) }
    }

    private fun canDragDrawer(touchY: Float): Boolean {
        // 顶部把手、标题和表情包栏始终可以拖动抽屉，不受网格滚动位置影响。
        if (pager.top > 0 && touchY < pager.top) return true
        val packId = packs.getOrNull(pager.currentItem)?.id ?: return true
        return visiblePages[packId]?.canScrollUp() != true
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
        val owner = touchPreviewOwner ?: return false
        owner.handlePreviewMotion(event)
        return true
    }

    private fun showGlobalStatus(message: String, loading: Boolean) {
        globalStatus.setOnClickListener(null)
        globalStatus.text = message
        globalStatus.visibility = View.VISIBLE
        globalProgress.visibility = if (loading) View.VISIBLE else View.GONE
        pager.visibility = View.GONE
        packTabs.visibility = View.GONE
    }

    private fun showGlobalError(message: String, retry: () -> Unit) {
        showGlobalStatus("$message\n\n点击重试", loading = false)
        globalStatus.setOnClickListener { retry() }
    }

    private fun showPager() {
        globalStatus.visibility = View.GONE
        globalProgress.visibility = View.GONE
        pager.visibility = View.VISIBLE
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
        private var collapsedExpanded = false
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
                        selectPack(packPosition, smoothScroll = true)
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
            collapsedExpanded = !collapsedExpanded
            notifyDataSetChanged()
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

    private inner class PackPageAdapter(
        private val items: List<PanelPack>,
    ) : RecyclerView.Adapter<PackPageHolder>() {
        private val holders = mutableSetOf<PackPageHolder>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackPageHolder =
            PackPageHolder(
                PackPage(hostContext).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            ).also(holders::add)

        override fun onBindViewHolder(holder: PackPageHolder, position: Int) {
            holder.page.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        override fun onViewRecycled(holder: PackPageHolder) {
            holder.page.dispose()
        }

        fun dispose() {
            holders.forEach { it.page.dispose() }
            holders.clear()
        }
    }

    private inner class PackPageHolder(val page: PackPage) : RecyclerView.ViewHolder(page)

    private inner class PackPage(context: Context) : FrameLayout(context) {
        private val grid = StatefulGridView(context)
        private val status = TextView(context)
        private val progress = ProgressBar(context)
        private var generation = 0
        private var boundPack: PanelPack? = null
        private var itemAdapter: ItemAdapter? = null
        private var displayedItems: List<PanelItem> = emptyList()
        private val previewPreloadTasks = mutableListOf<Future<*>>()
        private var pageActive = false
        private var touchPreviewActive = false
        private var touchPreviewPosition = GridView.INVALID_POSITION
        private var previewEndedAt = 0L

        init {
            grid.numColumns = panelColumns
            grid.horizontalSpacing = dp(2)
            grid.verticalSpacing = dp(3)
            grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
            grid.gravity = Gravity.CENTER
            grid.setPadding(0, dp(2), 0, dp(PACK_TAB_HEIGHT_DP + 2))
            grid.setOnItemClickListener { _, _, position, _ ->
                if (SystemClock.uptimeMillis() - previewEndedAt < PREVIEW_CLICK_SUPPRESSION_MS) {
                    return@setOnItemClickListener
                }
                if (boundPack == null) return@setOnItemClickListener
                itemAdapter?.getItem(position)?.let(::send)
            }
            grid.setOnTouchListener { _, event ->
                if (!touchPreviewActive) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val position = grid.pointToPosition(event.x.toInt(), event.y.toInt())
                        if (position != GridView.INVALID_POSITION && position != touchPreviewPosition) {
                            val item = itemAdapter?.getItem(position)
                            val anchor = itemViewAt(position)
                            if (item != null && anchor != null) {
                                touchPreviewPosition = position
                                showTouchPreview(item, anchor)
                            }
                        }
                    }

                    MotionEvent.ACTION_UP -> finishTouchPreview("松手")
                    MotionEvent.ACTION_CANCEL -> finishTouchPreview("手势取消")
                }
                true
            }
            grid.setOnItemLongClickListener { _, _, position, _ ->
                if (boundPack == null) return@setOnItemLongClickListener true
                val item = itemAdapter?.getItem(position) ?: return@setOnItemLongClickListener true
                touchPreviewActive = true
                touchPreviewPosition = position
                touchPreviewOwner = this
                pager.isUserInputEnabled = false
                showTouchPreview(item, itemViewAt(position) ?: grid)
                true
            }
            addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            status.gravity = Gravity.CENTER
            status.textSize = 16f
            status.setTextColor(secondaryColor)
            status.setPadding(dp(24))
            addView(status, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(progress, LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }

        fun bind(pack: PanelPack) {
            dispose()
            boundPack = pack
            visiblePages[pack.id] = this
            pageActive = packs.getOrNull(pager.currentItem)?.id == pack.id
            val cacheKey = panelItemCacheKey(revision, pack)
            val cachedItems = panelItemCache[cacheKey]
            if (cachedItems != null) {
                showItems(pack, cachedItems)
                if (pack.id == EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID) {
                    refreshItems(pack, cacheKey, cachedItems)
                }
                return
            }
            showStatus("正在读取 ${pack.displayName}…", loading = true)
            refreshItems(pack, cacheKey, null)
        }

        private fun refreshItems(
            pack: PanelPack,
            cacheKey: PanelItemCacheKey,
            visibleItems: List<PanelItem>?,
        ) {
            val requestGeneration = generation
            metadataWorker.execute {
                runCatching { QqPanelRepository.listItems(hostContext, pack) }
                    .onSuccess { items ->
                        panelItemCache[cacheKey] = items
                        mainHandler.post {
                            if (destroyed.get() || generation != requestGeneration || boundPack?.id != pack.id) {
                                return@post
                            }
                            if (items != visibleItems) showItems(pack, items)
                        }
                    }.onFailure { error ->
                        mainHandler.post {
                            if (destroyed.get() || generation != requestGeneration || boundPack?.id != pack.id) {
                                return@post
                            }
                            if (visibleItems == null) {
                                showError(error.message ?: "${pack.displayName} 读取失败") { bind(pack) }
                            } else {
                                QqPanelIntegration.log("刷新当前表情包元数据失败，继续使用进程缓存", error)
                            }
                        }
                    }
            }
        }

        private fun showItems(pack: PanelPack, items: List<PanelItem>) {
            captureScrollState()
            stopPreviewPreload()
            itemAdapter?.dispose()
            itemAdapter = null
            displayedItems = items
            grid.adapter = null
            if (items.isEmpty()) {
                if (pack.id == EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID) {
                    showStatus("暂无最近使用", loading = false)
                } else {
                    showError("${pack.displayName} 暂时没有表情") { bind(pack) }
                }
            } else {
                itemAdapter = ItemAdapter(items, pageActive).also { grid.adapter = it }
                showGrid()
                restoreScrollState(pack.id, items.size)
                if (pageActive) {
                    startPreviewPreload()
                }
            }
        }

        fun setPageActive(active: Boolean) {
            if (pageActive == active) return
            if (pageActive) captureScrollState()
            pageActive = active
            itemAdapter?.setFullContentEnabled(active)
            if (active) {
                boundPack?.let { pack -> restoreScrollState(pack.id, displayedItems.size) }
                startPreviewPreload()
            } else {
                stopPreviewPreload()
            }
        }

        fun startPreviewPreload() {
            stopPreviewPreload()
            if (boundPack == null) return
            val items = displayedItems
            if (items.isEmpty()) return
            // 可见首屏由高优先级网格任务负责，后台先从后续区域顺序预生成，再补首屏遗漏。
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
                            "当前表情包首帧预加载完成：total=${candidates.size}，failed=${failed.get()}",
                        )
                    }
                }
            }
        }

        fun stopPreviewPreload() {
            previewPreloadTasks.forEach { task -> task.cancel(false) }
            previewPreloadTasks.clear()
        }

        fun canScrollUp(): Boolean = grid.canScrollVertically(-1)

        fun dispose() {
            generation += 1
            captureScrollState()
            stopPreviewPreload()
            finishTouchPreview("页面释放")
            boundPack?.let { visiblePages.remove(it.id, this) }
            boundPack = null
            pageActive = false
            itemAdapter?.dispose()
            itemAdapter = null
            grid.adapter = null
        }

        private fun captureScrollState() {
            val packId = boundPack?.id ?: return
            if (grid.adapter == null || grid.count <= 0) return
            grid.captureScrollState()?.let { state -> packScrollStates[packId] = state }
        }

        private fun restoreScrollState(packId: String, itemCount: Int) {
            val state = packScrollStates[packId] ?: return
            if (itemCount <= 0) return
            grid.restoreScrollState(state)
            grid.post {
                if (boundPack?.id == packId && grid.adapter != null) grid.restoreScrollState(state)
            }
        }

        private fun finishTouchPreview(reason: String) {
            if (!touchPreviewActive) return
            QqPanelIntegration.log("关闭按压预览：$reason")
            touchPreviewActive = false
            touchPreviewPosition = GridView.INVALID_POSITION
            previewEndedAt = SystemClock.uptimeMillis()
            if (touchPreviewOwner === this) touchPreviewOwner = null
            pager.isUserInputEnabled = true
            grid.isPressed = false
            grid.cancelLongPress()
            // 关闭预览不改变表情数据，只清除当前子项按压态，避免整张网格重新绑定导致闪烁。
            for (index in 0 until grid.childCount) {
                grid.getChildAt(index).isPressed = false
            }
            hideTouchPreview()
        }

        fun handlePreviewMotion(event: MotionEvent) {
            if (!touchPreviewActive) return
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val location = IntArray(2)
                    grid.getLocationOnScreen(location)
                    val position = grid.pointToPosition(
                        (event.rawX - location[0]).toInt(),
                        (event.rawY - location[1]).toInt(),
                    )
                    if (position != GridView.INVALID_POSITION && position != touchPreviewPosition) {
                        val item = itemAdapter?.getItem(position)
                        val anchor = itemViewAt(position)
                        if (item != null && anchor != null) {
                            touchPreviewPosition = position
                            showTouchPreview(item, anchor)
                        }
                    }
                }

                MotionEvent.ACTION_UP -> finishTouchPreview("松手")
                MotionEvent.ACTION_CANCEL -> finishTouchPreview("手势取消")
            }
        }

        private fun itemViewAt(position: Int): View? {
            val childIndex = position - grid.firstVisiblePosition
            return childIndex.takeIf { it in 0 until grid.childCount }?.let(grid::getChildAt)
        }

        private fun showStatus(message: String, loading: Boolean) {
            status.setOnClickListener(null)
            status.text = message
            status.visibility = View.VISIBLE
            progress.visibility = if (loading) View.VISIBLE else View.GONE
            grid.visibility = View.GONE
        }

        private fun showError(message: String, retry: () -> Unit) {
            showStatus("$message\n\n点击重试", loading = false)
            status.setOnClickListener { retry() }
        }

        private fun showGrid() {
            status.visibility = View.GONE
            progress.visibility = View.GONE
            grid.visibility = View.VISIBLE
        }
    }

    private inner class ItemAdapter(
        private val items: List<PanelItem>,
        private var fullContentEnabled: Boolean,
    ) : BaseAdapter() {
        private val states = mutableSetOf<CellState>()
        private var memoryHits = 0
        private var memoryMisses = 0
        private var firstFrameHits = 0
        private var firstFrameMisses = 0

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): PanelItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val image = (convertView as? ImageView) ?: ImageView(hostContext).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellSize())
                setPadding(dp(1))
                setBackgroundColor(Color.TRANSPARENT)
            }
            (image.tag as? CellState)?.let { old ->
                old.active = false
                old.fileTask?.cancel(false)
                old.disposable?.dispose()
                old.cachedTarget?.stop()
                old.cachedLease?.close()
                states.remove(old)
            }
            image.setImageDrawable(null)
            val item = getItem(position)
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
            // ViewPager 邻页只保留已有首帧，选中后才读取和解码完整内容。
            if (!fullContentEnabled) return image
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
                return image
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
            return image
        }

        fun setFullContentEnabled(enabled: Boolean) {
            if (fullContentEnabled == enabled) return
            fullContentEnabled = enabled
            releaseStates(logStatistics = false)
            notifyDataSetChanged()
        }

        fun dispose() = releaseStates(logStatistics = true)

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

    private class StatefulGridView(context: Context) : GridView(context) {
        fun captureScrollState(): Parcelable? = super.onSaveInstanceState()

        fun restoreScrollState(state: Parcelable) {
            super.onRestoreInstanceState(state)
        }
    }

    /** 只接管明确的纵向抽屉手势，避免和网格滚动、左右翻页冲突。 */
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
        ((pager.width.takeIf { it > 0 } ?: hostContext.resources.displayMetrics.widthPixels) -
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
        private var lastSelectedPackId: String? = null
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
        private const val PAGER_LOOP_SWIPE_DP = 72
        private const val PRELOAD_VISIBLE_ROWS = 8
        private const val DRAWER_CLOSE_RATIO = 0.22f
        private const val DRAWER_CLOSE_VELOCITY = 1_200f
        private const val DRAWER_CLOSE_DURATION_MS = 160L
        private const val DRAWER_RETURN_DURATION_MS = 180L
        private const val DRAWER_RESIZE_DURATION_MS = 220L
    }
}
