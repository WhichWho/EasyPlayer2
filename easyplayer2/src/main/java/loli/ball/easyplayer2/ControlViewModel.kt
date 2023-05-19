package loli.ball.easyplayer2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.annotation.UiThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.exoplayer2.C.TIME_UNSET
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import loli.ball.easyplayer2.surface.EasySurfaceView

/**
 * Created by HeYanLe on 2023/3/8 22:45.
 * https://github.com/heyanLE
 */
class ControlViewModel(
    @field:SuppressLint("StaticFieldLeak")
    val context: Context,
    val exoPlayer: ExoPlayer,
    val isPadMode: Boolean = false,
) : ViewModel(), Player.Listener {

    companion object {
        const val CONTROL_HIDE_DELAY = 4000L
        const val POSITION_LOOP_DELAY = 1000L

        const val ratioWidth = 16F
        const val ratioHeight = 9F
    }

    // 普通状态  长按加速中 锁定中 左右滑动中 上下滑动中 结束

    enum class ControlState {
        Normal, Locked, HorizontalScroll, Ended
    }

    var controlState by mutableStateOf(ControlState.Normal)

    var isNormalLockedControlShow by mutableStateOf(true)

    var horizontalScrollPosition by mutableStateOf(0L)

    var fullScreenState by mutableStateOf<Pair<Boolean, Boolean>>(false to false)
    val isFullScreen: Boolean
        get() = fullScreenState.first

    val isReverse: Boolean
        get() = fullScreenState.second

    var isLoading by mutableStateOf(false)

    var playWhenReady by mutableStateOf(exoPlayer.playWhenReady)

    var position by mutableStateOf(0L)
    var bufferPosition by mutableStateOf(0L)

    var during by mutableStateOf(0L)

    var title by mutableStateOf("")

    var isLongPress by mutableStateOf(false)
    var lastSpeed = 1.0f

    // 设置的倍速，不一定是真正的播放速度
    // 可以用于倍速控制器显示
    private var _curSpeed by mutableStateOf(1.0f)
    val curSpeed = _curSpeed

    private var lastHideJob: Job? = null
    private var loopJob: Job? = null

    private var lastVideoSize: VideoSize? = null

    @SuppressLint("StaticFieldLeak")
    val surfaceView = EasySurfaceView(context)

    var fullScreenVertically = false

    fun onLockedChange(locked: Boolean) {
        viewModelScope.launch {
            if (locked) {
                controlState = ControlState.Locked
                showControlWithHideDelay()
            } else {
                controlState = ControlState.Normal
                showControlWithHideDelay()
            }
        }
    }

    fun onFullScreen(fullScreen: Boolean, reverse: Boolean = false, ctx: Activity) {
        viewModelScope.launch {
            val oldFullScreen = isFullScreen
            val oldReverse = isReverse
            if (oldFullScreen != fullScreen || oldReverse != reverse) {
                if (fullScreen) {
                    ctx.requestedOrientation =
                        if (fullScreenVertically) {
                            if (reverse) ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            if (reverse) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                } else {
                    if (isPadMode) {
                        ctx.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    } else {
                        ctx.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }
                viewModelScope.launch {
                    fullScreenState = fullScreen to reverse
                }
            }
            if (oldFullScreen != fullScreen) {
                controlState = ControlState.Normal
                showControlWithHideDelay()
            }
        }
    }

    private var lastOrientation = 0
    fun onOrientation(orientation: Int, act: Activity) {
        if (controlState != ControlState.Normal || isLongPress) {
            return
        }
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            lastOrientation = -1
            return
        }

        if (orientation > 350 || orientation < 10) {
            val o: Int = act.requestedOrientation
            if (o == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE && lastOrientation == 0) return
            //0度，用户竖直拿着手机
            lastOrientation = 0
            if (!isPadMode && act.isAutoRotateOn()) {
                onFullScreen(fullScreen = false, reverse = false, act)
            }

        } else if (orientation in 81..99) {
            val o: Int = act.requestedOrientation
            if (o == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT && lastOrientation == 90) return
            //90度，用户右侧横屏拿着手机
            lastOrientation = 90
            if (!isPadMode && act.isAutoRotateOn()) {
                onFullScreen(fullScreen = true, reverse = true, act)
            }
        } else if (orientation in 261..279) {
            val o: Int = act.requestedOrientation
            //手动切换横竖屏
            if (o == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT && lastOrientation == 270) return
            //270度，用户左侧横屏拿着手机
            lastOrientation = 270
            if (!isPadMode && act.isAutoRotateOn()) {
                onFullScreen(fullScreen = true, reverse = false, act)
            }
        }
    }

    @UiThread
    fun onPlayPause(isPlay: Boolean) {
        exoPlayer.playWhenReady = isPlay
    }

    @UiThread
    fun onPrepare() {
        isLoading = true
    }

    @UiThread
    fun onPositionChange(position: Long) {
        if (!exoPlayer.isMedia()) {
            return
        }
        if (controlState == ControlState.Normal) {
            controlState = ControlState.HorizontalScroll
        }

        horizontalScrollPosition =
            position.coerceIn(0, exoPlayer.duration.coerceAtLeast(Int.MAX_VALUE.toLong()))

    }

    fun onActionUP() {
        if (controlState == ControlState.Locked) {
            if (isLongPress) {
                if (exoPlayer.playbackParameters.speed != lastSpeed) {
                    exoPlayer.setPlaybackSpeed(lastSpeed)
                }
                isLongPress = false
            }
            return
        }
        if (controlState == ControlState.HorizontalScroll) {
            exoPlayer.seekTo(horizontalScrollPosition)
        }
        controlState = ControlState.Normal
        showControlWithHideDelay()
        if (isLongPress) {
            if (exoPlayer.playbackParameters.speed != lastSpeed) {
                exoPlayer.setPlaybackSpeed(lastSpeed)
            }
            isLongPress = false
        }
    }

    fun onLongPress() {
        lastSpeed = exoPlayer.playbackParameters.speed
        exoPlayer.setPlaybackSpeed(lastSpeed * 2)
        isLongPress = true
    }

    fun onSingleClick() {
        if (isNormalLockedControlShow) {
            isNormalLockedControlShow = false
        } else {
            showControlWithHideDelay()
        }
    }

    fun onLaunch() {
        exoPlayer.setVideoSurfaceView(surfaceView)
        lastVideoSize?.let {
            surfaceView.setVideoSize(it.width, it.height)
        }
        exoPlayer.setPlaybackSpeed(1.0f)
        lastSpeed = 1.0f
        isLongPress = false
    }

    fun setSpeed(speed: Float) {
        exoPlayer.playbackParameters = exoPlayer.playbackParameters.withSpeed(speed)
        _curSpeed = exoPlayer.playbackParameters.speed
    }

    fun onDisposed() {
        exoPlayer.stop()
        exoPlayer.clearVideoSurfaceView(surfaceView)
    }

    private fun showControlWithHideDelay() {
        lastHideJob?.cancel()
        isNormalLockedControlShow = true
        lastHideJob = viewModelScope.launch {
            delay(CONTROL_HIDE_DELAY)
            if (this.isActive && lastHideJob != null && lastHideJob?.isActive == true) {
                isNormalLockedControlShow = false
            }
        }
    }

    init {
        exoPlayer.addListener(this)
    }

    override fun onCleared() {
        super.onCleared()
        exoPlayer.removeListener(this)
    }

    private fun getLoopJob(): Job {
        return viewModelScope.launch {
            while (isActive) {
                syncTimeIfNeed()
                delay(POSITION_LOOP_DELAY)
            }
        }
    }

    private fun starLoop() {
        viewModelScope.launch {
            if (loopJob == null || loopJob?.isActive != true) {
                loopJob?.cancel()
                loopJob = getLoopJob()
            }
        }
    }

    private fun stopLoop() {
        viewModelScope.launch {
            loopJob?.cancel()
            loopJob = null
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        surfaceView.keepScreenOn = isPlaying
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_READY -> {
                isLoading = false
                syncTimeIfNeed()
                starLoop()
            }

            Player.STATE_IDLE -> {
                isLoading = false
                stopLoop()
            }

            Player.STATE_BUFFERING -> {
                isLoading = true
                syncTimeIfNeed()
                starLoop()
            }

            Player.STATE_ENDED -> {
                isLoading = false
                stopLoop()
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        super.onPlayWhenReadyChanged(playWhenReady, reason)
        this.playWhenReady = playWhenReady
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        super.onVideoSizeChanged(videoSize)
        surfaceView.setVideoSize(videoSize.width, videoSize.height)
        fullScreenVertically = videoSize.width < videoSize.height
    }

    private fun syncTimeIfNeed() {
        with(exoPlayer) {
            if (!isMedia()) return
            during = duration.let { if (it == TIME_UNSET) 0 else it }
            position = currentPosition
            bufferPosition = bufferedPosition
        }
    }

    private fun ExoPlayer.isMedia(): Boolean {
        return playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY
    }

    fun isShowOverlay(): Boolean {
        return when (controlState) {
            ControlState.Normal -> isNormalLockedControlShow
            ControlState.Locked -> false
            ControlState.Ended -> false
            else -> true
        }
    }

    private fun Activity.isAutoRotateOn(): Boolean {
        //获取系统是否允许自动旋转屏幕
        return Settings.System.getInt(
            contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0
        ) == 1
    }

}

class ControlViewModelFactory(
    private val context: Context,
    private val exoPlayer: ExoPlayer,
    private val isPadMode: Boolean = false,
) : ViewModelProvider.Factory {

    companion object {
        @Composable
        fun viewModel(exoPlayer: ExoPlayer, isPadMode: Boolean = false): ControlViewModel {
            return viewModel<ControlViewModel>(
                factory = ControlViewModelFactory(
                    LocalContext.current,
                    exoPlayer,
                    isPadMode = isPadMode
                )
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    @SuppressWarnings("unchecked")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ControlViewModel::class.java))
            return ControlViewModel(context, exoPlayer, isPadMode) as T
        throw RuntimeException("unknown class :" + modelClass.name)
    }

}