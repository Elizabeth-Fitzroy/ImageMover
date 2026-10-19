package com.example.imagemover.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.imagemover.data.repository.DriveFolderItem
import com.example.imagemover.data.repository.DriveRepository
import com.example.imagemover.data.storage.LocalStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 画面の表示状態を保持するデータクラス
 */
data class UiState(
    val selectedFolderUri: Uri? = null,
    val targetDriveFolderId: String = "root",
    val targetDriveFolderName: String = "マイドライブ直下",
    val statusText: String = "待機中",
    val logs: List<String> = emptyList(),
    val isProcessing: Boolean = false,
    val isSignedIn: Boolean = false,
    val userEmail: String? = null,
    val driveFolders: List<DriveFolderItem> = emptyList(),
    val isLoadingFolders: Boolean = false
)

/**
 * メイン画面のロジックおよび状態管理を行うViewModel
 */
class MainViewModel : ViewModel() {

    private var storageManager: LocalStorageManager? = null
    private var driveRepository: DriveRepository? = null

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    fun initStorageManager(manager: LocalStorageManager) {
        this.storageManager = manager
    }

    fun setDriveRepository(repository: DriveRepository, email: String) {
        this.driveRepository = repository
        _uiState.value = _uiState.value.copy(
            isSignedIn = true,
            userEmail = email
        )
        addLog("Googleドライブに接続しました: $email")
        // ログイン後に自動でフォルダ一覧を取得
        loadDriveFolders()
    }

    /**
     * Googleドライブ上のフォルダ一覧を読み込む
     */
    fun loadDriveFolders() {
        val repository = driveRepository ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingFolders = true)
            val folders = repository.listFolders()
            _uiState.value = _uiState.value.copy(
                driveFolders = folders,
                isLoadingFolders = false
            )
        }
    }

    /**
     * 新しいGoogleドライブフォルダを作成して選択状態にする
     */
    fun createAndSelectDriveFolder(folderName: String) {
        val repository = driveRepository ?: return
        if (folderName.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingFolders = true)
            val newFolder = repository.createFolder(folderName.trim())
            if (newFolder != null) {
                setTargetDriveFolder(newFolder.id, newFolder.name)
                addLog("新しいフォルダを作成しました: ${newFolder.name}")
                loadDriveFolders()
            } else {
                addLog("フォルダの作成に失敗しました")
                _uiState.value = _uiState.value.copy(isLoadingFolders = false)
            }
        }
    }

    /**
     * 保存先Googleドライブフォルダを選択
     */
    fun setTargetDriveFolder(folderId: String, folderName: String) {
        _uiState.value = _uiState.value.copy(
            targetDriveFolderId = folderId,
            targetDriveFolderName = folderName
        )
        addLog("Googleドライブ保存先を設定: $folderName")
    }

    fun setFolderUri(uri: Uri) {
        _uiState.value = _uiState.value.copy(selectedFolderUri = uri)
        addLog("監視ローカルフォルダを設定しました")
    }

    fun startMoveProcess() {
        val folderUri = _uiState.value.selectedFolderUri
        val manager = storageManager
        val repository = driveRepository

        if (folderUri == null || manager == null) {
            addLog("エラー: ローカルフォルダが選択されていません。")
            return
        }
        if (repository == null) {
            addLog("エラー: Googleドライブにサインインしていません。")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, statusText = "画像ファイルをスキャン中...")

            val files = manager.getImageFiles(folderUri)
            val total = files.size
            if (total == 0) {
                _uiState.value = _uiState.value.copy(isProcessing = false, statusText = "待機中")
                addLog("移動対象の画像ファイルが見つかりませんでした。")
                return@launch
            }

            var successCount = 0
            files.forEachIndexed { index, docFile ->
                val fileName = docFile.name ?: "image_${index}.jpg"
                _uiState.value = _uiState.value.copy(
                    statusText = "処理中 (${index + 1} / $total 枚)"
                )

                val inputStream = manager.openInputStream(docFile.uri)
                if (inputStream != null) {
                    val mimeType = docFile.type ?: "image/jpeg"

                    val driveFileId = repository.uploadImage(
                        fileName = fileName,
                        mimeType = mimeType,
                        inputStream = inputStream,
                        targetFolderId = _uiState.value.targetDriveFolderId
                    )

                    if (driveFileId != null) {
                        val deleted = manager.deleteFile(docFile)
                        if (deleted) {
                            successCount++
                            addLog("移動完了: $fileName")
                        } else {
                            addLog("アップロード成功・ローカル削除失敗: $fileName")
                        }
                    } else {
                        addLog("アップロード失敗のためローカルに保持: $fileName")
                    }
                } else {
                    addLog("ファイル読み込み失敗: $fileName")
                }
            }

            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                statusText = "完了 ($successCount / $total 枚移動)"
            )
            addLog("すべての処理が完了しました。移動成功: $successCount 枚 / 合計: $total 枚")
        }
    }

    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
        val newLog = "[$timestamp] $message"
        _uiState.value = _uiState.value.copy(
            logs = listOf(newLog) + _uiState.value.logs.take(100)
        )
    }
}
