package com.example.imagemover

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.imagemover.data.repository.DriveFolderItem
import com.example.imagemover.data.repository.DriveRepository
import com.example.imagemover.data.storage.LocalStorageManager
import com.example.imagemover.ui.MainViewModel
import com.example.imagemover.ui.theme.ImageMoverTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.util.Collections

/**
 * 設定保存用のキー定数
 */
private const val PREFS_NAME = "image_mover_prefs"
private const val KEY_LOCAL_URI = "saved_local_uri"
private const val KEY_DRIVE_FOLDER_ID = "saved_drive_folder_id"
private const val KEY_DRIVE_FOLDER_NAME = "saved_drive_folder_name"

/**
 * メイン画面のアクティビティ
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ImageMoverTheme {
                val viewModel: MainViewModel = viewModel()
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                    // 1. ローカルストレージマネージャーの初期化
                    viewModel.initStorageManager(LocalStorageManager(context))

                    // 2. 保存済みローカルフォルダの自動復元
                    val savedLocalUriString = prefs.getString(KEY_LOCAL_URI, null)
                    if (!savedLocalUriString.isNullOrBlank()) {
                        try {
                            val uri = Uri.parse(savedLocalUriString)
                            viewModel.setFolderUri(uri)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    // 3. 保存済みGoogleドライブ保存先フォルダの自動復元
                    val savedDriveId = prefs.getString(KEY_DRIVE_FOLDER_ID, "root") ?: "root"
                    val savedDriveName = prefs.getString(KEY_DRIVE_FOLDER_NAME, "マイドライブ直下") ?: "マイドライブ直下"
                    viewModel.setTargetDriveFolder(savedDriveId, savedDriveName)

                    // 4. Googleアカウントの前回ログイン情報の自動復元（サイレントサインイン）
                    val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
                    if (lastAccount != null) {
                        connectDriveService(context, lastAccount, viewModel)
                    }
                }

                MainScreen(viewModel = viewModel)
            }
        }
    }
}

/**
 * Google Drive API クライアントを初期化してViewModelに渡す
 */
private fun connectDriveService(context: Context, account: GoogleSignInAccount, viewModel: MainViewModel) {
    try {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            Collections.singleton(DriveScopes.DRIVE)
        ).apply {
            selectedAccount = account.account
        }

        val driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("ImageMover").build()

        viewModel.setDriveRepository(
            repository = DriveRepository(driveService),
            email = account.email ?: "連携完了"
        )
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * メイン画面のUIコンポーネント
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var showFolderDialog by remember { mutableStateOf(false) }

    // 1. ローカルフォルダ選択用ランチャー（SAF）
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
                // 端末内にローカルフォルダURIを永続保存
                prefs.edit().putString(KEY_LOCAL_URI, it.toString()).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            viewModel.setFolderUri(it)
        }
    }

    // 2. Google Sign-In 用ランチャー
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.result
                if (account != null) {
                    connectDriveService(context, account, viewModel)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Googleサインインに失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // フォルダ選択ダイアログ
    if (showFolderDialog) {
        DriveFolderSelectDialog(
            folders = state.driveFolders,
            isLoading = state.isLoadingFolders,
            onDismiss = { showFolderDialog = false },
            onSelect = { id, name ->
                // ドライブフォルダ設定を端末内に保存
                prefs.edit().putString(KEY_DRIVE_FOLDER_ID, id).putString(KEY_DRIVE_FOLDER_NAME, name).apply()
                viewModel.setTargetDriveFolder(id, name)
                showFolderDialog = false
            },
            onCreateFolder = { folderName ->
                viewModel.createAndSelectDriveFolder(folderName)
                showFolderDialog = false
            },
            onRefresh = { viewModel.loadDriveFolders() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ImageMover") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ① Googleドライブ連携カード
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "1. Googleドライブ設定", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (state.isSignedIn) "アカウント: ${state.userEmail}" else "アカウント: 未連携",
                        color = if (state.isSignedIn) MaterialTheme.colorScheme.primary else Color.Gray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail()
                                .requestScopes(Scope(DriveScopes.DRIVE))
                                .build()
                            val client = GoogleSignIn.getClient(context, gso)
                            googleSignInLauncher.launch(client.signInIntent)
                        },
                        enabled = !state.isProcessing
                    ) {
                        Text(if (state.isSignedIn) "アカウントを変更" else "Googleアカウントでログイン")
                    }

                    if (state.isSignedIn) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(text = "保存先フォルダ:", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = state.targetDriveFolderName,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.loadDriveFolders()
                                showFolderDialog = true
                            },
                            enabled = !state.isProcessing
                        ) {
                            Text("📁 保存先フォルダをタップして選択")
                        }
                    }
                }
            }

            // ② 監視ローカルフォルダ選択カード
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "2. 監視ローカルフォルダ", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.selectedFolderUri?.lastPathSegment ?: "未選択",
                        color = if (state.selectedFolderUri != null) MaterialTheme.colorScheme.primary else Color.Gray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        enabled = !state.isProcessing
                    ) {
                        Text("ローカルフォルダを選択")
                    }
                }
            }

            // ③ メインアクション：移動（同期）実行ボタン
            Button(
                onClick = { viewModel.startMoveProcess() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !state.isProcessing && state.selectedFolderUri != null && state.isSignedIn
            ) {
                Text(if (state.isProcessing) "移動処理を実行中..." else "今すぐ移動（同期）を実行")
            }

            // ④ ステータス表示
            Text(
                text = "状態: ${state.statusText}",
                style = MaterialTheme.typography.titleSmall
            )

            // ⑤ 実行ログ履歴（黒背景・ハイコントラスト）
            Text(text = "ログ履歴:", style = MaterialTheme.typography.titleSmall)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                if (state.logs.isEmpty()) {
                    item {
                        Text(
                            text = "ログはまだありません",
                            color = Color(0xFF888888),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    items(state.logs) { log ->
                        Text(
                            text = log,
                            color = Color(0xFFE0E0E0),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Googleドライブ内のフォルダ一覧からタップで選択するダイアログ
 */
@Composable
fun DriveFolderSelectDialog(
    folders: List<DriveFolderItem>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (id: String, name: String) -> Unit,
    onCreateFolder: (name: String) -> Unit,
    onRefresh: () -> Unit
) {
    var showCreateField by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存先フォルダを選択") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        item {
                            ListItem(
                                headlineContent = { Text("🏠 マイドライブ直下") },
                                modifier = Modifier.clickable { onSelect("root", "マイドライブ直下") }
                            )
                            HorizontalDivider()
                        }
                        items(folders) { folder ->
                            ListItem(
                                headlineContent = { Text("📁 " + folder.name) },
                                modifier = Modifier.clickable { onSelect(folder.id, folder.name) }
                            )
                            HorizontalDivider()
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (showCreateField) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("新規フォルダ名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (newFolderName.isNotBlank()) {
                                onCreateFolder(newFolderName)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("作成して保存先に設定")
                    }
                } else {
                    TextButton(onClick = { showCreateField = true }) {
                        Text("＋ 新しいフォルダを作成")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) {
                Text("再読込")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}