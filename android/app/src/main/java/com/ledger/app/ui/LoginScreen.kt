package com.ledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledger.app.viewmodel.AuthUiState

private val TABS = listOf("登录", "创建账本", "加入账本")

@Composable
fun LoginScreen(
    state: AuthUiState,
    initialBaseUrl: String,
    onLogin: (username: String, password: String) -> Unit,
    onRegister: (username: String, password: String, displayName: String, ledgerName: String) -> Unit,
    onJoin: (username: String, password: String, displayName: String, inviteCode: String) -> Unit,
    onBaseUrlChange: (String) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var baseUrl by remember { mutableStateOf(initialBaseUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var ledgerName by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("💰", fontSize = 32.sp)
            }
        }
        Text(
            "记账",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            "两个人的家庭账本",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "服务器地址",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; onBaseUrlChange(it) },
                    placeholder = { Text("如 http://你的域名:8000/") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Column(modifier = Modifier.padding(bottom = 20.dp)) {
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = Color.Transparent
                ) {
                    TABS.forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }

                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    LabeledField("用户名", username) { username = it }
                    LabeledField("密码", password, isPassword = true, topPadding = 12.dp) { password = it }

                    if (tab != 0) {
                        LabeledField("昵称", displayName, topPadding = 12.dp) { displayName = it }
                    }
                    if (tab == 1) {
                        LabeledField("账本名称, 如 我们家", ledgerName, topPadding = 12.dp) { ledgerName = it }
                    }
                    if (tab == 2) {
                        LabeledField("账本邀请码", inviteCode, topPadding = 12.dp) { inviteCode = it }
                    }

                    state.error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                    Button(
                        onClick = {
                            when (tab) {
                                0 -> onLogin(username, password)
                                1 -> onRegister(username, password, displayName, ledgerName)
                                else -> onJoin(username, password, displayName, inviteCode)
                            }
                        },
                        enabled = !state.loading,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                    ) {
                        Text(TABS[tab])
                    }

                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(top = 16.dp).align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().padding(top = topPadding)
    )
}
