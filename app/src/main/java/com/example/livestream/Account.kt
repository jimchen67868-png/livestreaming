package com.example.livestream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
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
    val ctx = LocalContext.current
    val sp = ctx.getSharedPreferences("s", 0)
    // an email that is waiting for its code is remembered, so closing the app does not lose it
    val pending = sp.getString("pending_email", "") ?: ""
    val deviceId = sp.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also { sp.edit().putString("device_id", it).apply() }
    var mode by remember { mutableStateOf(if (pending.isNotEmpty() && current.isEmpty()) "verify" else "login") } // login | register | verify
    var email by remember { mutableStateOf(pending) }
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
                if (o.has("token")) { sp.edit().remove("pending_email").apply(); onDone(o.getString("token"), o.optString("name")) }
                else if (o.has("error")) msg = o.getString("error")
                else if (o.optBoolean("verify")) {
                    mode = "verify"
                    sp.edit().putString("pending_email", body.optString("email")).apply()
                    msg = if (path == "resend" && o.optBoolean("sent")) "A new code was sent to $email"
                    else if (path == "resend") "Your code was already sent and is valid for 15 minutes. Check your email and spam folder."
                    else "Enter the 6-digit code emailed to $email (valid for 15 minutes)"
                }
            } catch (e: Exception) {
                msg = "Can't reach $server (${e.javaClass.simpleName}: ${e.message})"
            }
            busy = false
        }
    }
    val em = email.trim()
    var coverRev by remember { mutableStateOf(0) } // bumps after a new cover is saved, so the preview reloads
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true; msg = ""
            val r = withContext(Dispatchers.Default) { encodeSquare(ctx, uri, 240, 38000) }
            if (r == null) msg = "Could not use that picture"
            else if (LocalGuard.suspicious(r.second)) msg = "This picture looks inappropriate and cannot be used"
            else {
                val err = withContext(Dispatchers.IO) { Api.uploadCover(server, r.first) }
                if (err.isEmpty()) { coverRev++; msg = "Cover saved" } else msg = err
            }
            busy = false
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true; msg = ""
            val r = withContext(Dispatchers.Default) { encodeSquare(ctx, uri, 160, 28000) }
            if (r == null) msg = "Could not use that picture"
            else {
                val err = withContext(Dispatchers.IO) { Api.uploadAvatar(server, r.first) }
                if (err.isEmpty()) { Avatars.put(current, r.second.asImageBitmap()); msg = "Photo saved" } else msg = err
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (current.isNotEmpty()) "Signed in" else when (mode) { "register" -> "Create account"; "verify" -> "Verify your email"; else -> "Log in" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.isNotEmpty()) {
                    Avatar(current, 80.dp)
                    Text("You are logged in as $current. This name is yours alone.")
                    OutlinedButton({ pickPhoto.launch("image/*") }, enabled = !busy) { Text("Change profile photo") }
                    Text("Live cover (shown on Home when you go live, and used for every live)", style = MaterialTheme.typography.bodySmall)
                    val cover by produceState<ImageBitmap?>(null, current, coverRev) {
                        value = withContext(Dispatchers.IO) {
                            Api.fetchBytes(server, "cover?u=" + java.net.URLEncoder.encode(current, "UTF-8"))?.let {
                                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                            }
                        }
                    }
                    cover?.let { Image(it, null, Modifier.size(120.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
                    OutlinedButton({ pickCover.launch("image/*") }, enabled = !busy) { Text(if (cover == null) "Set live cover" else "Change live cover") }
                    Text("The cover can only be changed when you are not live.", style = MaterialTheme.typography.bodySmall)
                } else if (mode == "verify") {
                    OutlinedTextField(code, { code = it.filter { c -> c.isDigit() }.take(6) }, label = { Text("6-digit code") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ call("verify", JSONObject().put("email", em).put("code", code)) }, enabled = !busy && code.length == 6) { Text("Verify") }
                        OutlinedButton({ call("resend", JSONObject().put("email", em)) }, enabled = !busy) { Text("Code expired? Get new code") }
                    }
                    TextButton({ sp.edit().remove("pending_email").apply(); mode = "login"; code = ""; msg = "" }) { Text("Use a different email") }
                } else {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(pass, { pass = it }, label = { Text("Password (6+ characters)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (mode == "register") OutlinedTextField(display, { display = it }, singleLine = true,
                        label = { Text("Display name (3-16: letters, numbers, _)") }, modifier = Modifier.fillMaxWidth())
                    if (mode == "register")
                        Button({ call("register", JSONObject().put("email", em).put("password", pass).put("name", display.trim()).put("device", deviceId)) },
                            enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Register") }
                    else
                        Button({ call("login", JSONObject().put("email", em).put("password", pass)) },
                            enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Log in") }
                    TextButton({ mode = if (mode == "login") "register" else "login"; msg = "" }) {
                        Text(if (mode == "login") "No account? Register" else "Have an account? Log in")
                    }
                }
                if (msg.isNotEmpty()) Text(msg, color = if (msg.startsWith("Enter the 6-digit") || msg == "Photo saved" || msg == "Cover saved" || msg.startsWith("A new code") || msg.startsWith("Your code was")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (current.isNotEmpty()) TextButton({ onDone("", "") }) { Text("Log out") }
            else TextButton(onClose) { Text("Close") }
        },
        dismissButton = { if (current.isNotEmpty()) TextButton(onClose) { Text("Close") } }
    )
}
