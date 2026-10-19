package com.example.imagemover.data.repository

import com.google.api.client.http.InputStreamContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Collections

/**
 * フォルダ情報を表すデータクラス
 */
data class DriveFolderItem(
    val id: String,
    val name: String
)

/**
 * Google Drive API を操作するリポジトリクラス
 */
class DriveRepository(private val driveService: Drive) {

    /**
     * Googleドライブ内のフォルダ一覧を取得する
     */
    suspend fun listFolders(): List<DriveFolderItem> = withContext(Dispatchers.IO) {
        try {
            val query = "mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .setPageSize(50)
                .execute()

            result.files?.map { file ->
                DriveFolderItem(id = file.id, name = file.name)
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 新しいフォルダを作成する
     */
    suspend fun createFolder(folderName: String, parentFolderId: String = "root"): DriveFolderItem? = withContext(Dispatchers.IO) {
        try {
            val fileMetadata = File().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
                parents = Collections.singletonList(parentFolderId)
            }
            val folder = driveService.files().create(fileMetadata)
                .setFields("id, name")
                .execute()
            DriveFolderItem(id = folder.id, name = folder.name)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 画像ストリームを指定したGoogleドライブのフォルダへアップロードする
     */
    suspend fun uploadImage(
        fileName: String,
        mimeType: String,
        inputStream: InputStream,
        targetFolderId: String = "root"
    ): String? = withContext(Dispatchers.IO) {
        try {
            val fileMetadata = File().apply {
                name = fileName
                parents = Collections.singletonList(targetFolderId)
            }

            val mediaContent = InputStreamContent(mimeType, inputStream)

            val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()

            uploadedFile.id
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                inputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
