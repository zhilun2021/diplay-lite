package com.shilapi.diplay.lite.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * vc248（FEAT-247-01 §7.2）：最小自定义 ContentProvider —— API24+ 安装走 content://。
 *
 * 零 AndroidX 约束下不引入 FileProvider；这里只开放 [UpdateConfig.updateDir] 下的**直接子文件**，
 * 防目录穿越；`exported=false` + `grantUriPermissions=true`，安装器按一次性读权限打开。
 */
class ApkContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("仅支持只读")
        val context = context ?: throw FileNotFoundException("无上下文")
        val dir = UpdateConfig.updateDir(context)
        val name = uri.lastPathSegment ?: throw FileNotFoundException("缺少文件名")
        val file = File(dir, name)
        if (file.parentFile?.canonicalPath != dir.canonicalPath || !file.isFile) {
            throw FileNotFoundException("不允许访问该文件")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = MIME_APK

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val MIME_APK = "application/vnd.android.package-archive"

        /** authority 与 Manifest 的 `${applicationId}.update` 保持一致。 */
        fun uriFor(context: Context, file: File): Uri =
            Uri.parse("content://${context.packageName}.update/${file.name}")
    }
}
