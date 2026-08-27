package top.e404.emorepo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import top.e404.emorepo.config.SetupInput
import top.e404.emorepo.config.validateHttpsRemote
import top.e404.emorepo.config.validated

@Composable
fun OnboardingScreen(state: EmoRepoState) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var remoteUrl by rememberSaveable { mutableStateOf(state.settings.remoteUrl) }
    var token by rememberSaveable { mutableStateOf("") }
    var authorName by rememberSaveable { mutableStateOf(state.settings.authorName) }
    var authorEmail by rememberSaveable { mutableStateOf(state.settings.authorEmail) }
    var deviceId by rememberSaveable { mutableStateOf(state.settings.deviceId) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("开始使用表情仓", style = MaterialTheme.typography.headlineMedium)
            Text("步骤 ${step + 1} / 3", style = MaterialTheme.typography.labelLarge)
            when (step) {
                0 -> RepositorySetupStep(
                    remoteUrl = remoteUrl,
                    token = token,
                    onRemoteUrlChange = { remoteUrl = it },
                    onTokenChange = { token = it },
                )
                1 -> IdentitySetupStep(
                    authorName = authorName,
                    authorEmail = authorEmail,
                    deviceId = deviceId,
                    onAuthorNameChange = { authorName = it },
                    onAuthorEmailChange = { authorEmail = it },
                    onDeviceIdChange = { deviceId = it },
                )
                else -> SetupSummary(
                    remoteUrl = remoteUrl.trim(),
                    authorName = authorName.trim(),
                    authorEmail = authorEmail.trim(),
                    deviceId = deviceId.trim(),
                    hasToken = token.isNotBlank(),
                )
            }

            localError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            state.message?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    onClick = state::dismissMessage,
                ) {
                    Text(message, modifier = Modifier.padding(12.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (step > 0) {
                    TextButton(onClick = { step--; localError = null }, enabled = !state.busy) {
                        Text("上一步")
                    }
                } else {
                    Spacer(Modifier)
                }
                Button(
                    enabled = !state.busy,
                    onClick = {
                        localError = runCatching {
                            if (step == 0) validateHttpsRemote(remoteUrl.trim())
                            else SetupInput(remoteUrl, authorName, authorEmail, deviceId).validated()
                        }.exceptionOrNull()?.message
                        if (localError == null) {
                            if (step < 2) step++
                            else state.completeSetup(
                                SetupInput(remoteUrl, authorName, authorEmail, deviceId),
                                token,
                            )
                        }
                    },
                ) {
                    Text(if (step == 2) "克隆仓库" else "下一步")
                }
            }
        }
        if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

@Composable
private fun RepositorySetupStep(
    remoteUrl: String,
    token: String,
    onRemoteUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
) {
    Text("仓库", style = MaterialTheme.typography.titleLarge)
    Text("填写已有表情仓库的 HTTPS 地址。公开仓库可以不填 Token。")
    OutlinedTextField(
        value = remoteUrl,
        onValueChange = onRemoteUrlChange,
        label = { Text("HTTPS 远端地址") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    OutlinedTextField(
        value = token,
        onValueChange = onTokenChange,
        label = { Text("Token（可选）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
private fun IdentitySetupStep(
    authorName: String,
    authorEmail: String,
    deviceId: String,
    onAuthorNameChange: (String) -> Unit,
    onAuthorEmailChange: (String) -> Unit,
    onDeviceIdChange: (String) -> Unit,
) {
    Text("身份和设备", style = MaterialTheme.typography.titleLarge)
    Text("Git 身份用于创建提交；设备 ID 用于区分不同设备的最近使用记录。")
    OutlinedTextField(
        value = authorName,
        onValueChange = onAuthorNameChange,
        label = { Text("Git 作者名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = authorEmail,
        onValueChange = onAuthorEmailChange,
        label = { Text("Git 作者邮箱") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )
    OutlinedTextField(
        value = deviceId,
        onValueChange = onDeviceIdChange,
        label = { Text("设备 ID") },
        supportingText = { Text("仅允许字母、数字、- 和 _，长度 1 到 48") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SetupSummary(
    remoteUrl: String,
    authorName: String,
    authorEmail: String,
    deviceId: String,
    hasToken: Boolean,
) {
    Text("确认配置", style = MaterialTheme.typography.titleLarge)
    SummaryRow("远端", remoteUrl)
    SummaryRow("认证", if (hasToken) "已提供 Token" else "公开访问")
    SummaryRow("Git 作者", "$authorName <$authorEmail>")
    SummaryRow("设备 ID", deviceId)
    Text("克隆成功前不会进入主界面。Token 只会加密保存在 Android Keystore 中。")
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
