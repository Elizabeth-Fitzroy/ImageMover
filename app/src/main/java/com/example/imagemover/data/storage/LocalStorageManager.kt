package com.example.imagemover.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream

/**
 * ローカルストレージ（SAF: Storage Access Framework）の操作を担当するクラス
 */
class LocalStorageManager(private val context: Context) {

    /**
     * 指定されたフォルダ配下の画像ファイル一覧を取得する
     */
    fun getImageFiles(folderUri: Uri): List<DocumentFile> {
        val rootDoc = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
        // 画像MIMEタイプまたは拡張子でフィルタリング
        return rootDoc.listFiles().filter { doc ->
            doc.isFile && (doc.type?.startsWith("image/") == true ||
                    doc.name?.endsWith(".jpg", ignoreCase = true) == true ||
                    doc.name?.endsWith(".png", ignoreCase = true) == true ||
                    doc.name?.endsWith(".jpeg", ignoreCase = true) == true)
        }
    }

    /**
     * 指定ファイルのInputStream（読み込みストリーム）を取得する
     */
    fun openInputStream(uri: Uri): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }

    /**
     * アップロード成功後にローカルファイルを削除する
     * @return 削除が成功したかどうか
     */
    fun deleteFile(documentFile: DocumentFile): Boolean {
        return try {
            documentFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
