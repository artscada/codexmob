package com.codex.android.ui.github

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.codex.github.GitHubApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GitHub 仓库管理界面。
 *
 * 提供：
 * - 仓库信息展示
 * - 分支管理（切换/创建）
 * - Git 操作（status, diff, commit, push, pull）
 * - 提交历史
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubRepoScreen(
    repoFullName: String,
    repoLocalPath: String,
    onBack: () -> Unit,
    onOpenPRs: () -> Unit,
    onOpenIssues: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiClient = remember { GitHubApiClient(context) }

    var activeTab by remember { mutableIntStateOf(0) }

    // Git status
    var gitStatus by remember { mutableStateOf("") }
    var statusLoading by remember { mutableStateOf(false) }

    // Commit
    var commitMessage by remember { mutableStateOf("") }
    var isCommitting by remember { mutableStateOf(false) }
    var commitResult by remember { mutableStateOf<String?>(null) }

    // Diff
    var gitDiff by remember { mutableStateOf("") }
    var diffLoading by remember { mutableStateOf(false) }

    // Branches
    var branches by remember { mutableStateOf<List<GitHubApiClient.BranchInfo>>(emptyList()) }
    var currentBranch by remember { mutableStateOf("") }
    var branchLoading by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var isCreatingBranch by remember { mutableStateOf(false) }

    // Commits
    var commits by remember { mutableStateOf<List<GitHubApiClient.CommitInfo>>(emptyList()) }
    var commitsLoading by remember { mutableStateOf(false) }

    // Operations
    var isPushing by remember { mutableStateOf(false) }
    var isPulling by remember { mutableStateOf(false) }
    var opResult by remember { mutableStateOf<String?>(null) }

    val owner = repoFullName.split("/").getOrElse(0) { "" }
    val repo = repoFullName.split("/").getOrElse(1) { "" }

    fun refreshStatus() {
        statusLoading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val dir = File(repoLocalPath)
                    if (!dir.isDirectory()) return@withContext "错误: 本地仓库目录不存在"
                    val pb = ProcessBuilder("sh", "-c", "cd ${dir.absolutePath} && git status 2>&1")
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    proc.inputStream.bufferedReader().readText().trim()
                } catch (e: Exception) {
                    "获取状态失败: ${e.message}"
                }
            }
            gitStatus = result
            statusLoading = false

            // Get current branch
            withContext(Dispatchers.IO) {
                try {
                    val dir = File(repoLocalPath)
                    val pb = ProcessBuilder("sh", "-c", "cd ${dir.absolutePath} && git branch --show-current")
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    currentBranch = proc.inputStream.bufferedReader().readText().trim()
                } catch (_: Exception) {}
            }
        }
    }

    fun runGitCommand(command: String): String {
        return try {
            val dir = File(repoLocalPath)
            val pb = ProcessBuilder("sh", "-c", "cd ${dir.absolutePath} && $command 2>&1")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            "执行失败: ${e.message}"
        }
    }

    LaunchedEffect(Unit) {
        refreshStatus()
        // Load branches
        branchLoading = true
        apiClient.listBranches(owner, repo).onSuccess {
            branches = it
        }
        branchLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(repoFullName, fontSize = 16.sp)
                        if (currentBranch.isNotBlank()) {
                            Text(
                                "分支: $currentBranch",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // Pull button
                    IconButton(
                        onClick = {
                            isPulling = true
                            opResult = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runGitCommand("git pull")
                                }
                                opResult = result
                                isPulling = false
                                refreshStatus()
                            }
                        },
                        enabled = !isPulling
                    ) {
                        if (isPulling) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Download, "拉取")
                        }
                    }
                    // Push button
                    IconButton(
                        onClick = {
                            isPushing = true
                            opResult = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runGitCommand("git push")
                                }
                                opResult = result
                                isPushing = false
                                refreshStatus()
                            }
                        },
                        enabled = !isPushing
                    ) {
                        if (isPushing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Upload, "推送")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab Row
            TabRow(selectedTabIndex = activeTab) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                    TabText("状态")
                }
                Tab(selected = activeTab == 1, onClick = {
                    activeTab = 1
                    if (gitDiff.isEmpty()) {
                        diffLoading = true
                        scope.launch {
                            gitDiff = withContext(Dispatchers.IO) {
                                runGitCommand("git diff")
                            }.ifEmpty { "无变更" }
                            diffLoading = false
                        }
                    }
                }) {
                    TabText("变更")
                }
                Tab(selected = activeTab == 2, onClick = {
                    activeTab = 2
                }) {
                    TabText("提交")
                }
                Tab(selected = activeTab == 3, onClick = {
                    activeTab = 3
                    if (branches.isEmpty()) {
                        branchLoading = true
                        scope.launch {
                            apiClient.listBranches(owner, repo).onSuccess {
                                branches = it
                            }
                            branchLoading = false
                        }
                    }
                }) {
                    TabText("分支")
                }
            }

            when (activeTab) {
                0 -> StatusTab(
                    gitStatus = gitStatus,
                    statusLoading = statusLoading,
                    onRefresh = { refreshStatus() },
                    onOpenPRs = onOpenPRs,
                    onOpenIssues = onOpenIssues
                )
                1 -> DiffTab(
                    gitDiff = gitDiff,
                    diffLoading = diffLoading,
                    onRefresh = {
                        diffLoading = true
                        scope.launch {
                            gitDiff = withContext(Dispatchers.IO) {
                                runGitCommand("git diff")
                            }.ifEmpty { "无变更" }
                            diffLoading = false
                        }
                    }
                )
                2 -> CommitTab(
                    commitMessage = commitMessage,
                    onMessageChange = { commitMessage = it },
                    isCommitting = isCommitting,
                    commitResult = commitResult,
                    onCommit = {
                        if (commitMessage.isBlank()) return@CommitTab
                        isCommitting = true
                        commitResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runGitCommand("git add -A")
                                runGitCommand("git commit -m \"${commitMessage.replace("\"", "\\\"")}\"")
                            }
                            commitResult = result
                            isCommitting = false
                            commitMessage = ""
                            refreshStatus()
                        }
                    }
                )
                3 -> BranchesTab(
                    branches = branches,
                    currentBranch = currentBranch,
                    branchLoading = branchLoading,
                    newBranchName = newBranchName,
                    onNewBranchChange = { newBranchName = it },
                    isCreatingBranch = isCreatingBranch,
                    onCreateBranch = {
                        if (newBranchName.isBlank()) return@BranchesTab
                        isCreatingBranch = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runGitCommand("git branch $newBranchName")
                            }
                            val result2 = withContext(Dispatchers.IO) {
                                runGitCommand("git checkout $newBranchName")
                            }
                            opResult = result + "\n" + result2
                            isCreatingBranch = false
                            newBranchName = ""
                            refreshStatus()
                            apiClient.listBranches(owner, repo).onSuccess {
                                branches = it
                            }
                        }
                    },
                    onCheckoutBranch = { name ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runGitCommand("git checkout $name")
                            }
                            refreshStatus()
                        }
                    }
                )
            }

            // Operation result message
            opResult?.let { result ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0A0A0F)
                ) {
                    Text(
                        result,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF4AF626),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusTab(
    gitStatus: String,
    statusLoading: Boolean,
    onRefresh: () -> Unit,
    onOpenPRs: () -> Unit,
    onOpenIssues: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("工作区状态", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, "刷新")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenPRs) {
                Icon(Icons.Default.AccountTree, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pull Requests")
            }
            OutlinedButton(onClick = onOpenIssues) {
                Icon(Icons.Default.BugReport, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Issues")
            }
        }

        Spacer(Modifier.height(12.dp))

        if (statusLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0A0A0F)
            ) {
                Text(
                    gitStatus.ifEmpty { "工作区无变更" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF4AF626),
                    modifier = Modifier.fillMaxSize().padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun DiffTab(
    gitDiff: String,
    diffLoading: Boolean,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("文件变更", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, "刷新")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (diffLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0A0A0F)
            ) {
                LazyColumn(modifier = Modifier.padding(12.dp)) {
                    item {
                        Text(
                            gitDiff,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF4AF626)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitTab(
    commitMessage: String,
    onMessageChange: (String) -> Unit,
    isCommitting: Boolean,
    commitResult: String?,
    onCommit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("提交变更", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = commitMessage,
            onValueChange = onMessageChange,
            label = { Text("提交信息") },
            placeholder = { Text("描述本次变更...") },
            modifier = Modifier.fillMaxWidth().height(120.dp),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit() })
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onCommit,
            enabled = !isCommitting && commitMessage.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isCommitting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(isCommitting.let { if (it) "提交中..." else "提交" })
        }

        commitResult?.let {
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0A0A0F)
            ) {
                Text(
                    it,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF4AF626),
                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun BranchesTab(
    branches: List<GitHubApiClient.BranchInfo>,
    currentBranch: String,
    branchLoading: Boolean,
    newBranchName: String,
    onNewBranchChange: (String) -> Unit,
    isCreatingBranch: Boolean,
    onCreateBranch: () -> Unit,
    onCheckoutBranch: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("分支管理", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))

        // Create new branch
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newBranchName,
                onValueChange = onNewBranchChange,
                placeholder = { Text("新分支名") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !isCreatingBranch
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onCreateBranch,
                enabled = !isCreatingBranch && newBranchName.isNotBlank()
            ) {
                if (isCreatingBranch) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Text("创建")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("已有分支", fontWeight = FontWeight.Medium, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))

        if (branchLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(branches) { branch ->
                    Surface(
                        onClick = {
                            if (branch.name != currentBranch) onCheckoutBranch(branch.name)
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (branch.name == currentBranch)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (branch.name == currentBranch) Icons.Default.CheckCircle else Icons.Default.AccountTree,
                                null,
                                modifier = Modifier.size(18.dp),
                                tint = if (branch.name == currentBranch) Color(0xFF2ED573)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(branch.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(branch.sha.take(7), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (branch.name == currentBranch) {
                                Text("当前", fontSize = 11.sp, color = Color(0xFF2ED573))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabText(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = 8.dp))
}
