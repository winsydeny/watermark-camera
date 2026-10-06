package com.sydeny.wmcamera.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.sydeny.wmcamera.ServiceLocator
import com.sydeny.wmcamera.data.GalleryPhoto
import com.sydeny.wmcamera.data.GalleryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 相册页的状态。 */
sealed interface GalleryState {
    data object Loading : GalleryState
    data object Empty : GalleryState
    data class Ready(val photos: List<GalleryPhoto>) : GalleryState
    data class Failed(val reason: String) : GalleryState
}

class GalleryViewModel(
    private val repository: GalleryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<GalleryState>(GalleryState.Loading)
    val state: StateFlow<GalleryState> = _state.asStateFlow()

    init {
        reload()
    }

    /**
     * 每次从相机页进相册都重读一遍。
     *
     * 不缓存：用户很可能刚拍完一张就切过来，缓存会让新照片凭空消失，
     * 直到杀掉进程才看见。
     */
    fun reload() {
        _state.value = GalleryState.Loading
        viewModelScope.launch {
            _state.value = runCatching {
                // MediaStore 查询会走 binder，大数据量时不是瞬时的，别占主线程
                withContext(Dispatchers.IO) { repository.loadPhotos() }
            }.fold(
                onSuccess = { photos ->
                    if (photos.isEmpty()) {
                        GalleryState.Empty
                    } else {
                        GalleryState.Ready(photos)
                    }
                },
                onFailure = { error ->
                    GalleryState.Failed(error.message ?: error.javaClass.simpleName)
                },
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T {
                val repository = GalleryRepository(ServiceLocator.applicationContext)
                return GalleryViewModel(repository) as T
            }
        }
    }
}
