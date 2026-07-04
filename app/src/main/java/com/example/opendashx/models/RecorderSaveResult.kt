package com.example.opendashx.models

import java.io.File

data class RecorderSaveResult(
    val ok: Boolean,
    val file: File?,
    val message: String,
    val directory: String,
    val fileName: String,
    val framesWritten: Long,
    val fileSizeBytes: Long,
    val exception: String?
)
