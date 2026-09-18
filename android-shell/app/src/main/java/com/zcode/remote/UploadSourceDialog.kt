package com.zcode.remote

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialog
import com.zcode.remote.databinding.DialogUploadSourceBinding

/**
 * "选择上传方式" chooser, mirroring the modal in the HarmonyOS build
 * (`entry/src/main/ets/pages/Index.ets`, `showUploadModal`).
 *
 * Two tiles only — 相册 and 文件. The HarmonyOS build deliberately dropped its
 * camera entry, and this follows suit: no CAMERA permission, nothing to request.
 *
 * Dismissal is the subtle part. The WebView hands us a callback that must be
 * invoked exactly once, so every exit path is funnelled through [settled]:
 * picking a tile settles the dialog and starts the picker (the picker's own
 * result callback reports cancellation), while tapping the scrim or pressing
 * back goes through [cancel] and reports cancellation immediately.
 */
class UploadSourceDialog(
    context: Context,
    private val onPickImages: () -> Unit,
    private val onPickFiles: () -> Unit,
    private val onCancelled: () -> Unit,
) : AppCompatDialog(context) {

    /** True once the choice has been reported, so the cancel path fires once. */
    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = DialogUploadSourceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.apply {
            // The scrim lives in the layout as rgba(0,0,0,0.5) so it matches the
            // HarmonyOS modal exactly; the window must not add its own shade.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        // The scrim is an explicit view, so outside taps must not also dismiss.
        setCanceledOnTouchOutside(false)

        binding.tileAlbum.setOnClickListener { settle(onPickImages) }
        binding.tileFile.setOnClickListener { settle(onPickFiles) }
        binding.dialogRoot.setOnClickListener { cancel() }
    }

    private fun settle(action: () -> Unit) {
        if (settled) return
        settled = true
        dismiss()
        action()
    }

    override fun cancel() {
        if (!settled) {
            settled = true
            onCancelled()
        }
        super.cancel()
    }
}
