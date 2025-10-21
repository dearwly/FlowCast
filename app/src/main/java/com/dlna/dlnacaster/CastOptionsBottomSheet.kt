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

        etUrl.addTextChangedListener { text ->
            btnPlayUrl.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        btnPlayUrl.setOnClickListener {
            listener?.onCastUrl(etUrl.text.toString())
        }
        btnVideo.setOnClickListener {
            listener?.onPickVideo()
        }
        btnAudio.setOnClickListener {
            listener?.onPickAudio()
        }
        btnImage.setOnClickListener {
            listener?.onPickImage()
        }
        btnFile.setOnClickListener {
            listener?.onPickFile()
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}