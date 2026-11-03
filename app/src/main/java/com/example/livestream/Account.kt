package com.example.livestream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Register / log in with email + password, then confirm the 6-digit code that was emailed.
// current = account name when logged in, "" for guest.
// onDone(token, name): a new session, or ("", "") after logging out.
@Composable
fun AccountDialog(server: String, current: String, onDone: (String, String) -> Unit, onClose: () -> Unit) {
    var mode by remember { mutableStateOf("login") } // login | register | verify
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun call(path: String, body: JSONObject) {
        if (server.isBlank()) { msg = "Set the Server URL first"; return }
        scope.launch {
            busy = true; msg = ""
            try {
                val o = withContext(Dispatchers.IO) { Api.accountCall(server, path, body) }
                if (o.has("token")) onDone(o.getString("token"), o.optString("name"))
                else if (o.has("error")) msg = o.getString("error")
                else if (o.optBoolean("verify")) {
                    mode = "verify"
                    msg = if (path == "resend") "A new code was sent to $email" else "We emailed a 6-digit code to $email"
                }
            } catch (e: Exception) {
                msg = "Can't reach server"
            }
            busy = false
        }
    }
    val em = email.trim()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (current.isNotEmpty()) "Signed in" else when (mode) { "register" -> "Create account"; "verify" -> "Verify your email"; else -> "Log in" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.isNotEmpty()) {
                    Text("You are logged in as $current. This name is yours alone.")
                } else if (mode == "verify") {
                    OutlinedTextField(code, { code = it.filter { c -> c.isDigit() }.take(6) }, label = { Text("6-digit code") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ call("verify", JSONObject().put("email", em).put("code", code)) }, enabled = !busy && code.length == 6) { Text("Verify") }
                        OutlinedButton({ call("resend", JSONObject().put("email", em)) }, enabled = !busy) { Text("Send a new code") }
                    }
                } else {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(pass, { pass = it }, label = { Text("Password (6+ characters)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (mode == "register") OutlinedTextField(display, { display = it }, singleLine = true,
                        label = { Text("Display name (3-16: letters, numbers, _)") }, modifier = Modifier.fillMaxWidth())
                    if (mode == "register")
                        Button({ call("register", JSONObject().put("email", em).put("password", pass).put("name", display.trim())) },
                            enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Register") }
                    else
                        Button({ call("login", JSONObject().put("email", em).put("password", pass)) },
                            enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Log in") }
                    TextButton({ mode = if (mode == "login") "register" else "login"; msg = "" }) {
                        Text(if (mode == "login") "No account? Register" else "Have an account? Log in")
                    }
                }
                if (msg.isNotEmpty()) Text(msg, color = if (msg.startsWith("We emailed") || msg.startsWith("A new code")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (current.isNotEmpty()) TextButton({ onDone("", "") }) { Text("Log out") }
            else TextButton(onClose) { Text("Close") }
        },
        dismissButton = { if (current.isNotEmpty()) TextButton(onClose) { Text("Close") } }
    )
}
