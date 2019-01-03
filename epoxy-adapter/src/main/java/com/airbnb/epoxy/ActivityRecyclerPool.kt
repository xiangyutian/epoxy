package com.airbnb.epoxy

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference
import java.util.*

internal class ActivityRecyclerPool {

    /**
     * Store one unique pool per activity. They are cleared out when activities are destroyed, so this
     * only needs to hold pools for active activities.
     */
    private val pools = ArrayList<PoolReference>(5)

    @JvmOverloads
    fun getPool(
        context: Context,
        poolFactory: () -> RecyclerView.RecycledViewPool = { UnboundedViewPool() }
    ): PoolReference {

        val iterator = pools.iterator()
        var poolToUse: PoolReference? = null

        while (iterator.hasNext()) {
            val poolReference = iterator.next()
            when {
                poolReference.context === context -> {
                    if (poolToUse != null) {
                        throw IllegalStateException("A pool was already found")
                    }
                    poolToUse = poolReference
                    // finish iterating to remove any old contexts
                }
                poolReference.context.isActivityDestroyed() -> {
                    // A pool from a different activity that was destroyed.
                    // Clear the pool references to allow the activity to be GC'd
                    poolReference.viewPool.clear()
                    iterator.remove()
                }
            }
        }

        if (poolToUse == null) {
            poolToUse = PoolReference(context, poolFactory(), this)
            pools.add(poolToUse)
        }

        return poolToUse
    }

    fun clearIfDestroyed(pool: PoolReference) {
        if (pool.context.isActivityDestroyed()) {
            pool.viewPool.clear()
            pools.remove(pool)
        }
    }
}

internal class PoolReference(
    context: Context,
    val viewPool: RecyclerView.RecycledViewPool,
    private val parent: ActivityRecyclerPool
) {
    private val contextReference: WeakReference<Context> = WeakReference(context)

    val context: Context? get() = contextReference.get()

    fun clearIfDestroyed() {
        parent.clearIfDestroyed(this)
    }
}

internal fun Context?.isActivityDestroyed(): Boolean {
    if (this == null) {
        return true
    }

    if (this !is Activity) {
        return false
    }

    if (isFinishing) {
        return true
    }

    return if (Build.VERSION.SDK_INT >= 17) {
        isDestroyed
    } else {
        // Use this as a proxy for being destroyed on older devices
        !ViewCompat.isAttachedToWindow(window.decorView)
    }
}

