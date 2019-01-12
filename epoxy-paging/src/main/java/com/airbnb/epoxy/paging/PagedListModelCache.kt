/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.airbnb.epoxy.paging

import android.annotation.SuppressLint
import android.os.Handler
import android.util.Log
import androidx.paging.AsyncPagedListDiffer
import androidx.paging.PagedList
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import com.airbnb.epoxy.EpoxyController
import com.airbnb.epoxy.EpoxyModel
import java.util.concurrent.Executor

/**
 * A PagedList stream wrapper that caches models built for each item. It tracks changes in paged lists and caches
 * models for each item when they are invalidated to avoid rebuilding models for the whole list when PagedList is
 * updated.
 */
internal class PagedListModelCache<T>(
    private val modelBuilder: (itemIndex: Int, item: T?) -> EpoxyModel<*>,
    private val rebuildCallback: () -> Unit,
    itemDiffCallback: DiffUtil.ItemCallback<T>,
    private val diffExecutor: Executor? = null,
    private val modelBuildingHandler: Handler
) {
    /**
     * Backing list for built models. This is a full array list that has null items for not yet build models.
     *
     * All interactions with this should by synchronized, since it is accessed from several threads
     * for model building, paged list updates, and cache clearing.
     */
    private val modelCache = arrayListOf<EpoxyModel<*>?>()
    /**
     * Tracks the last accessed position so that we can report it back to the paged list when models are built.
     */
    private var lastPosition: Int? = null

    /**
     * Observer for the PagedList changes that invalidates the model cache when data is updated.
     */
    private val updateCallback = object : ListUpdateCallback {
        @Synchronized
        override fun onChanged(position: Int, count: Int, payload: Any?) {
            (position until (position + count)).forEach {
                modelCache[it] = null
            }
            rebuildCallback()
        }

        @Synchronized
        override fun onMoved(fromPosition: Int, toPosition: Int) {
            val model = modelCache.removeAt(fromPosition)
            modelCache.add(toPosition, model)
            rebuildCallback()
        }

        @Synchronized
        override fun onInserted(position: Int, count: Int) {
            (0 until count).forEach {
                modelCache.add(position, null)
            }
            rebuildCallback()
        }

        @Synchronized
        override fun onRemoved(position: Int, count: Int) {
            (0 until count).forEach {
                modelCache.removeAt(position)
            }
            rebuildCallback()
        }
    }

    @SuppressLint("RestrictedApi")
    private val asyncDiffer = object : AsyncPagedListDiffer<T>(
        updateCallback,
        AsyncDifferConfig.Builder<T>(
            itemDiffCallback
        ).also { builder ->
            if (diffExecutor != null) {
                builder.setBackgroundThreadExecutor(diffExecutor)
            }

            // we have to reply on this private API, otherwise, paged list might be changed when models are being built,
            // potentially creating concurrent modification problems.
            builder.setMainThreadExecutor { runnable: Runnable ->
                modelBuildingHandler.post(runnable)
            }
        }.build()
    ) {
        init {
            if (modelBuildingHandler != EpoxyController.defaultModelBuildingHandler) {
                try {
                    // looks like AsyncPagedListDiffer in 1.x ignores the config.
                    // Reflection to the rescue.
                    val mainThreadExecutorField =
                        AsyncPagedListDiffer::class.java.getDeclaredField("mMainThreadExecutor")
                    mainThreadExecutorField.isAccessible = true
                    mainThreadExecutorField.set(this, Executor {
                        modelBuildingHandler.post(it)
                    })
                } catch (t: Throwable) {
                    val msg = "Failed to hijack update handler in AsyncPagedListDiffer." +
                            "You can only build models on the main thread"
                    Log.e("PagedListModelCache", msg, t)
                    throw IllegalStateException(msg, t)
                }
            }
        }
    }

    fun submitList(pagedList: PagedList<T>?) {
        asyncDiffer.submitList(pagedList)
    }

    @Synchronized
    fun getModels(): List<EpoxyModel<*>> {
        val currentList = asyncDiffer.currentList
        (0 until modelCache.size).forEach { position ->
            if (modelCache[position] != null) {
                return@forEach
            }

            modelBuilder(position, currentList?.get(position)).also {
                modelCache[position] = it
            }
        }

        lastPosition?.let {
            triggerLoadAround(it)
        }
        @Suppress("UNCHECKED_CAST")
        return modelCache as List<EpoxyModel<*>>
    }

    @Synchronized
    fun clearModels() {
        modelCache.fill(null)
    }

    fun loadAround(position: Int) {
        triggerLoadAround(position)
        lastPosition = position
    }

    private fun triggerLoadAround(position: Int) {
        asyncDiffer.currentList?.let {
            if (it.size > 0) {
                it.loadAround(Math.min(position, it.size - 1))
            }
        }
    }
}
