package com.dlna.dlnacaster

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.widget.addTextChangedListener
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class CastOptionsBottomSheet : BottomSheetDialogFragment() {

    // 1. 定义一个接口，让 Activity 实现
    interface CastOptionsListener {
        fun onCastUrl(url: String)
        fun onPickVideo()
        fun onPickAudio()
        fun onPickImage()
        fun onPickFile()
    }

    private var listener: CastOptionsListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // 将 Activity 转换为我们的监听器
        if (context is CastOptionsListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement CastOptionsListener")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_cast_options, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etUrl = view.findViewById<EditText>(R.id.et_url)
        val btnPlayUrl = view.findViewById<ImageButton>(R.id.btn_play_url)
        val btnVideo = view.findViewById<LinearLayout>(R.id.btn_local_video)
        val btnAudio = view.findViewById<LinearLayout>(R.id.btn_local_audio)
        val btnImage = view.findViewById<LinearLayout>(R.id.btn_local_image)
        val btnFile = view.findViewById<LinearLayout>(R.id.btn_local_file)

        // 监听文本框变化，以显示/隐藏播放按钮
        etUrl.addTextChangedListener { text ->
            btnPlayUrl.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        // 设置所有按钮的点击事件
        btnPlayUrl.setOnClickListener {
            listener?.onCastUrl(etUrl.text.toString())
            dismiss() // 关闭弹窗
        }
        btnVideo.setOnClickListener {
            listener?.onPickVideo()
            dismiss()
        }
        btnAudio.setOnClickListener {
            listener?.onPickAudio()
            dismiss()
        }
        btnImage.setOnClickListener {
            listener?.onPickImage()
            dismiss()
        }
        btnFile.setOnClickListener {
            listener?.onPickFile()
            dismiss()
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}