package com.rize.rizeandroid.pose

import android.os.Handler
import android.os.Looper

object PoseDataManager {

    var poseDataListener: ((List<Double>) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun sendPoseData(data: List<Double>) {
        mainHandler.post {
            poseDataListener?.invoke(data)
        }
    }
}
