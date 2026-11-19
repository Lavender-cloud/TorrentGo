package com.example.torrentgo

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import kotlinx.coroutines.*
import java.io.File

data class Item(
    val id: String, val name: String, val progress: Float,
    val down: Long, val up: Long, val peers: Int, val state: String, val paused: Boolean
)

class Engine(private val dir: File) {
    private val sm = SessionManager().apply { start() }
    private val handles = linkedMapOf<String, TorrentHandle>()

    fun addMagnet(uri: String, onError: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val data = sm.fetchMagnet(uri, 60, dir)   // resolves metadata from DHT
            if (data == null) { onError("Couldn't fetch metadata (no peers?)"); return@launch }
            addInfo(TorrentInfo.bdecode(data))
        }
    }

    fun addFile(file: File) = addInfo(TorrentInfo(file))

    private fun addInfo(ti: TorrentInfo) {
        sm.download(ti, dir)
        sm.find(ti.infoHash())?.let { handles[ti.infoHash().toHex()] = it }
    }

    fun toggle(id: String) = handles[id]?.let { if (it.status().flags().and_(
        com.frostwire.jlibtorrent.swig.torrent_flags.paused).nonZero()) it.resume() else it.pause() }

    fun remove(id: String) { handles.remove(id)?.let { sm.remove(it) } }

    fun snapshot(): List<Item> = handles.map { (id, h) ->
        val s = h.status()
        Item(id, s.name(), s.progress(), s.downloadPayloadRate().toLong(),
            s.uploadPayloadRate().toLong(), s.numPeers(), s.state().name,
            s.flags().and_(com.frostwire.jlibtorrent.swig.torrent_flags.paused).nonZero())
    }

    fun stop() = sm.stop()
}

class MainActivity : ComponentActivity() {
    private lateinit var engine: Engine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        engine = Engine(dir)
        val incoming = intent?.data?.toString()?.takeIf { it.startsWith("magnet:") }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen(engine, dir, incoming) }
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); engine.stop() }
}

@Composable
fun Screen(engine: Engine, dir: File, incoming: String?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var magnet by remember { mutableStateOf(incoming ?: "") }
    var items by remember { mutableStateOf(emptyList<Item>()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (incoming != null) engine.addMagnet(incoming) { error = it }
        while (true) { items = engine.snapshot(); delay(1000) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val tmp = File(ctx.cacheDir, "add.torrent")
        ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        engine.addFile(tmp)
    }

    Column(Modifier.padding(16.dp).statusBarsPadding()) {
        Text("TorrentGo", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(magnet, { magnet = it }, Modifier.fillMaxWidth(),
            label = { Text("Paste magnet link") }, maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ if (magnet.isNotBlank()) { engine.addMagnet(magnet.trim()) { error = it }; magnet = "" } }) { Text("Add magnet") }
            OutlinedButton({ picker.launch("*/*") }) { Text(".torrent file") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Saving to: ${dir.absolutePath}", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(t.name.ifBlank { "Loading…" }, maxLines = 2)
                        LinearProgressIndicator({ t.progress }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Text("${(t.progress * 100).toInt()}% • ↓ ${t.down / 1024} KB/s • ↑ ${t.up / 1024} KB/s • ${t.peers} peers • ${t.state}",
                            style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ engine.toggle(t.id) }) { Text(if (t.paused) "Resume" else "Pause") }
                            TextButton({ engine.remove(t.id) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}
