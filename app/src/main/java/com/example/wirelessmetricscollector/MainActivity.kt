package com.example.wirelessmetricscollector

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Periferico(val nome: String, val device: BluetoothDevice, val rssi: Int)

// Uma leitura da coleta
data class Leitura(
    val horaTexto: String,   // horário legível, para a tela
    val epochMs: Long,       // relógio do celular (ms), para alinhar com o robô
    val monoNs: Long,        // relógio monotônico (ns), para medir intervalos
    val rssi: Int,
    val txPhy: Int,
    val rxPhy: Int
)

// Como e quando a coleta terminou
data class FimColeta(
    val evento: String,      // "encerrada_manual", "conexao_perdida" ou "desconectado_manual"
    val codigo: Int?,        // código do Android (null quando foi pelo botão)
    val motivo: String,
    val epochMs: Long,
    val monoNs: Long
)

class MainActivity : ComponentActivity() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e4b2f1a-9c3d-4a7e-b5f2-8d1c0e9a7b64")
        const val ID_FABRICANTE = 0xFFFF   // identificador reservado para testes
        const val LINHAS_NA_TELA = 30
        const val PASTA_LOGS = "WirelessMetricsCollector"
    }

    private val status = mutableStateOf("Aguardando permissões...")
    private val permissoesOk = mutableStateOf(false)
    private val tela = mutableStateOf("inicio")   // "inicio", "configPeriferico", "rodando", "inspecionar", "coletando"
    private val nomePeriferico = mutableStateOf("")
    private val encontrados = mutableStateMapOf<String, Periferico>()

    // Janela de aviso: par (título, mensagem). null = nenhuma janela aberta
    private val aviso = mutableStateOf<Pair<String, String>?>(null)

    // Configuração da coleta
    private val intervaloTexto = mutableStateOf("0.25")   // o que está digitado na caixa, em segundos
    private val salvarLog = mutableStateOf(true)
    private var intervaloMs = 250L                        // intervalo validado, em milissegundos

    // Estado da coleta
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var coletando = false
    @Volatile private var leituraEmAndamento = false
    private var proximoTick = 0L
    private var rssiAtual = 0
    private var tempoMsAtual = 0L
    private var tempoNsAtual = 0L
    private val ultimasLeituras = mutableStateListOf<Leitura>()   // só as últimas, para a tela
    private val todasLeituras = ArrayList<Leitura>()               // todas, para o CSV
    private val totalLeituras = mutableStateOf(0)
    private val puladas = mutableStateOf(0)
    private var inicioColetaMs = 0L
    private val formatoHora = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Scanner (central)
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var nomeSelecionado = ""
    private var conectou = false            // a conexão chegou a subir?
    private var desconexaoManual = false    // a desconexão foi pedida pelo botão?

    // Advertiser (periférico)
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    private val permissoes = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private val pedirPermissoes = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultado ->
        val todas = resultado.values.all { it }
        permissoesOk.value = todas
        status.value = if (todas) "Escolha o modo" else "Faltou permissão: $resultado"
    }

    private fun adapterLigado() =
        getSystemService(BluetoothManager::class.java).adapter?.takeIf { it.isEnabled }

    // Traduz o código de status do Android para uma explicação
    private fun motivo(codigo: Int): String = when (codigo) {
        0 -> "Desconexão normal."
        8 -> "Os aparelhos perderam o sinal (distância, obstáculo ou interferência)."
        19 -> "O outro aparelho encerrou a conexão."
        22 -> "Este aparelho encerrou a conexão."
        34 -> "O outro aparelho parou de responder durante a conexão."
        62 -> "A conexão não chegou a ser estabelecida. O advertiser pode ter parado de anunciar ou estar fora de alcance."
        133 -> "Erro genérico da pilha Bluetooth do Android. Tente de novo; se persistir, desligue e ligue o Bluetooth."
        147 -> "Tempo esgotado ao tentar conectar."
        else -> "Motivo desconhecido."
    }

    // Traduz o código do PHY para texto
    private fun nomePhy(phy: Int): String = when (phy) {
        BluetoothDevice.PHY_LE_1M -> "1M"
        BluetoothDevice.PHY_LE_2M -> "2M"
        BluetoothDevice.PHY_LE_CODED -> "Coded"
        else -> "?($phy)"
    }

    // ================= SCANNER (central) =================

    @SuppressLint("MissingPermission")
    private fun iniciarCentral() {
        scanner = adapterLigado()?.bluetoothLeScanner
        val s = scanner
        if (s == null) {
            status.value = "Bluetooth desligado ou indisponível"
            return
        }

        val filtro = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        encontrados.clear()
        s.startScan(listOf(filtro), settings, callbackScan)
        tela.value = "rodando"
        status.value = "SCANNER: toque no advertiser para conectar"
    }

    private val callbackScan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val bytes = result.scanRecord?.getManufacturerSpecificData(ID_FABRICANTE)
            val nome = bytes?.toString(Charsets.UTF_8) ?: "(sem nome) ${result.device.address}"
            encontrados[nome] = Periferico(nome, result.device, result.rssi)
            Log.d("Coleta", "scanner $nome RSSI=${result.rssi}")
        }

        override fun onScanFailed(errorCode: Int) {
            status.value = "Scan falhou: código $errorCode"
        }
    }

    @SuppressLint("MissingPermission")
    private fun selecionar(p: Periferico) {
        scanner?.stopScan(callbackScan)
        nomeSelecionado = p.nome
        conectou = false
        desconexaoManual = false
        encontrados.clear()
        status.value = "Conectando a ${p.nome}..."
        Log.d("Coleta", "conectando a ${p.nome} ${p.device.address}")

        gatt = p.device.connectGatt(this, false, callbackGatt, BluetoothDevice.TRANSPORT_LE)
    }

    private val callbackGatt = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, novoEstado: Int) {
            Log.d("Coleta", "scanner: estado=$novoEstado status=$st")
            runOnUiThread {
                if (novoEstado == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                    // ---- Conectou com sucesso ----
                    conectou = true
                    status.value = "Conectado a $nomeSelecionado"
                    tela.value = "inspecionar"
                    aviso.value = "Conectado" to
                            "A conexão com \"$nomeSelecionado\" foi estabelecida com sucesso."

                } else if (novoEstado == BluetoothProfile.STATE_DISCONNECTED || st != BluetoothGatt.GATT_SUCCESS) {
                    // ---- Desconectou ou falhou ----
                    val evento = if (desconexaoManual) "desconectado_manual" else "conexao_perdida"
                    val infoColeta = encerrarColeta(evento, st)   // null se não havia coleta rodando
                    g.close()
                    gatt = null
                    tela.value = "inicio"

                    when {
                        !conectou -> {
                            status.value = "Falha ao conectar a $nomeSelecionado"
                            aviso.value = "Não foi possível conectar" to
                                    "Não foi possível conectar a \"$nomeSelecionado\".\n\n" +
                                    "Motivo: ${motivo(st)}\n\n(código $st)"
                        }
                        desconexaoManual -> {
                            status.value = "Desconectado de $nomeSelecionado"
                            if (infoColeta != null) {
                                aviso.value = "Coleta encerrada" to infoColeta
                            }
                        }
                        else -> {
                            status.value = "Conexão com $nomeSelecionado perdida"
                            aviso.value = "Conexão perdida" to
                                    "A conexão com \"$nomeSelecionado\" caiu.\n\n" +
                                    "Motivo: ${motivo(st)}\n\n(código $st)" +
                                    (infoColeta?.let { "\n\n--- Coleta ---\n$it" } ?: "")
                        }
                    }
                }
            }
        }

        // 1ª etapa da leitura: chegou o RSSI -> pede o PHY
        @SuppressLint("MissingPermission")
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, st: Int) {
            if (st == BluetoothGatt.GATT_SUCCESS && coletando) {
                rssiAtual = rssi
                tempoMsAtual = System.currentTimeMillis()
                tempoNsAtual = SystemClock.elapsedRealtimeNanos()
                g.readPhy()
            } else {
                leituraEmAndamento = false
            }
        }

        // 2ª etapa da leitura: chegou o PHY -> leitura completa
        override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, st: Int) {
            leituraEmAndamento = false
            if (st != BluetoothGatt.GATT_SUCCESS || !coletando) return

            val leitura = Leitura(
                horaTexto = formatoHora.format(Date(tempoMsAtual)),
                epochMs = tempoMsAtual,
                monoNs = tempoNsAtual,
                rssi = rssiAtual,
                txPhy = txPhy,
                rxPhy = rxPhy
            )
            Log.d("Coleta", "leitura ${leitura.horaTexto} rssi=${leitura.rssi} phy=${nomePhy(txPhy)}/${nomePhy(rxPhy)}")

            runOnUiThread {
                if (!coletando) return@runOnUiThread   // coleta encerrada enquanto a leitura chegava
                todasLeituras.add(leitura)
                ultimasLeituras.add(0, leitura)
                if (ultimasLeituras.size > LINHAS_NA_TELA) {
                    ultimasLeituras.removeAt(ultimasLeituras.size - 1)
                }
                totalLeituras.value = todasLeituras.size
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun desconectar() {
        desconexaoManual = true
        status.value = "Desconectando..."
        gatt?.disconnect()
    }

    // ================= COLETA =================

    private fun iniciarColeta() {
        val segundos = intervaloTexto.value.replace(",", ".").toDoubleOrNull()
        if (segundos == null || segundos < 0.05) {
            aviso.value = "Intervalo inválido" to
                    "Digite um número em segundos, a partir de 0.05 (exemplo: 0.25)."
            return
        }
        intervaloMs = (segundos * 1000).toLong()

        // Zera tudo da coleta anterior
        todasLeituras.clear()
        ultimasLeituras.clear()
        totalLeituras.value = 0
        puladas.value = 0
        inicioColetaMs = System.currentTimeMillis()
        leituraEmAndamento = false

        coletando = true
        proximoTick = SystemClock.uptimeMillis()
        handler.post(tick)

        Log.d("Coleta", "coleta iniciada: intervalo=${intervaloMs}ms salvarLog=${salvarLog.value}")
        status.value = "Coletando de $nomeSelecionado"
        tela.value = "coletando"
    }

    // Roda a cada intervalo: dispara uma leitura (ou pula, se a anterior não terminou)
    private val tick = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (!coletando) return

            if (leituraEmAndamento) {
                puladas.value++
            } else {
                leituraEmAndamento = true
                val ok = gatt?.readRemoteRssi() ?: false
                if (!ok) {
                    leituraEmAndamento = false
                    puladas.value++
                }
            }

            proximoTick += intervaloMs
            handler.postAtTime(this, proximoTick)
        }
    }

    // Para o relógio da coleta. Devolve true se havia uma coleta rodando.
    private fun pararColeta(): Boolean {
        if (!coletando) return false
        coletando = false
        leituraEmAndamento = false
        handler.removeCallbacks(tick)
        Log.d("Coleta", "coleta parada: ${todasLeituras.size} leituras, ${puladas.value} puladas")
        return true
    }

    // Para a coleta, salva o CSV (se marcado) e devolve um resumo.
    // Devolve null se não havia coleta rodando.
    private fun encerrarColeta(evento: String, codigo: Int?): String? {
        // Marca o horário do término antes de qualquer outra coisa
        val agoraMs = System.currentTimeMillis()
        val agoraNs = SystemClock.elapsedRealtimeNanos()

        if (!pararColeta()) return null

        val fim = FimColeta(
            evento = evento,
            codigo = codigo,
            motivo = if (codigo == null) "Encerrada pelo botão" else motivo(codigo),
            epochMs = agoraMs,
            monoNs = agoraNs
        )

        val resumo = "Leituras: ${todasLeituras.size}\nPuladas: ${puladas.value}\nTérmino: $evento"

        if (!salvarLog.value) return "$resumo\n\nLog não salvo (opção desmarcada)."

        return try {
            val caminho = salvarCsv(fim)
            Log.d("Coleta", "CSV salvo em $caminho")
            "$resumo\n\nArquivo salvo em:\n$caminho"
        } catch (e: Exception) {
            Log.e("Coleta", "erro ao salvar CSV", e)
            "$resumo\n\nErro ao salvar o arquivo: ${e.message}"
        }
    }

    // ================= SALVAR CSV =================

    private fun conteudoCsv(fim: FimColeta): String {
        val sb = StringBuilder()
        sb.append("hora,epoch_ms,mono_ns,rssi_dbm,tx_phy,rx_phy,evento,codigo,motivo\n")

        // Uma linha por leitura
        for (l in todasLeituras) {
            sb.append("${l.horaTexto},${l.epochMs},${l.monoNs},${l.rssi},")
            sb.append("${nomePhy(l.txPhy)},${nomePhy(l.rxPhy)},leitura,,\n")
        }

        // Última linha: como a coleta terminou
        val horaFim = formatoHora.format(Date(fim.epochMs))
        val motivoCsv = "\"" + fim.motivo.replace("\"", "\"\"") + "\""
        sb.append("$horaFim,${fim.epochMs},${fim.monoNs},,,,")
        sb.append("${fim.evento},${fim.codigo ?: ""},$motivoCsv\n")

        return sb.toString()
    }

    private fun nomeArquivoCsv(): String {
        val inicio = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(inicioColetaMs))
        val nomeLimpo = nomeSelecionado.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "coleta_${nomeLimpo}_$inicio.csv"
    }

    // Salva o CSV e devolve o caminho onde ficou
    private fun salvarCsv(fim: FimColeta): String {
        val nome = nomeArquivoCsv()
        val conteudo = conteudoCsv(fim)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            salvarNaPastaDownloads(nome, conteudo)
        } else {
            // Android 9 ou anterior: pasta do próprio app
            val pasta = File(getExternalFilesDir(null), PASTA_LOGS).apply { mkdirs() }
            val arquivo = File(pasta, nome)
            arquivo.writeText(conteudo)
            arquivo.absolutePath
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun salvarNaPastaDownloads(nome: String, conteudo: String): String {
        val pastaRelativa = Environment.DIRECTORY_DOWNLOADS + "/" + PASTA_LOGS

        val valores = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, pastaRelativa)
        }

        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
            ?: throw Exception("não foi possível criar o arquivo")

        contentResolver.openOutputStream(uri)?.use { saida ->
            saida.write(conteudo.toByteArray(Charsets.UTF_8))
        } ?: throw Exception("não foi possível abrir o arquivo para escrita")

        return "$pastaRelativa/$nome"
    }

    // ================= ADVERTISER (periférico) =================

    @SuppressLint("MissingPermission")
    private fun iniciarPeriferico() {
        advertiser = adapterLigado()?.bluetoothLeAdvertiser
        if (advertiser == null) {
            status.value = "Não foi possível anunciar (Bluetooth desligado?)"
            return
        }

        val manager = getSystemService(BluetoothManager::class.java)
        gattServer = manager.openGattServer(this, callbackServidor)
        gattServer?.addService(
            BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        )

        comecarAnuncio()
        tela.value = "rodando"
    }

    @SuppressLint("MissingPermission")
    private fun comecarAnuncio() {
        val adv = advertiser ?: return
        adv.stopAdvertising(callbackAnuncio)   // garante que não há anúncio antigo rodando

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        // Anúncio principal: só o UUID do experimento
        val anuncio = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()

        // Resposta de scan: o nome escolhido
        val resposta = AdvertiseData.Builder()
            .addManufacturerData(ID_FABRICANTE, nomePeriferico.value.toByteArray(Charsets.UTF_8))
            .build()

        adv.startAdvertising(settings, anuncio, resposta, callbackAnuncio)
    }

    private val callbackServidor = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, st: Int, novoEstado: Int) {
            Log.d("Coleta", "advertiser: ${device.address} estado=$novoEstado status=$st")
            runOnUiThread {
                if (novoEstado == BluetoothProfile.STATE_CONNECTED) {
                    status.value = "ADVERTISER \"${nomePeriferico.value}\": scanner conectado"
                    aviso.value = "Scanner conectado" to
                            "Um scanner se conectou a este advertiser."
                } else if (novoEstado == BluetoothProfile.STATE_DISCONNECTED) {
                    status.value = "ADVERTISER \"${nomePeriferico.value}\": scanner desconectou. Anunciando de novo..."
                    if (st != 0) {
                        aviso.value = "Scanner desconectou" to
                                "Motivo: ${motivo(st)}\n\n(código $st)"
                    }
                    comecarAnuncio()
                }
            }
        }
    }

    private val callbackAnuncio = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            if (!status.value.contains("desconectou")) {
                status.value = "ADVERTISER \"${nomePeriferico.value}\": anunciando..."
            }
        }

        override fun onStartFailure(errorCode: Int) {
            status.value = "Falha ao anunciar: código $errorCode"
        }
    }

    // ================= TELA =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(status.value, textAlign = TextAlign.Center)

                if (permissoesOk.value) {
                    when (tela.value) {

                        "inicio" -> {
                            Button(onClick = {
                                tela.value = "configPeriferico"
                                status.value = "Dê um nome a este advertiser"
                            }) { Text("Modo Advertiser") }

                            Button(onClick = { iniciarCentral() }) { Text("Modo Scanner") }
                        }

                        "configPeriferico" -> {
                            OutlinedTextField(
                                value = nomePeriferico.value,
                                onValueChange = { nomePeriferico.value = it.take(12) },
                                label = { Text("Nome (até 12 letras)") }
                            )
                            Button(
                                onClick = { iniciarPeriferico() },
                                enabled = nomePeriferico.value.isNotBlank()
                            ) { Text("Começar a anunciar") }

                            Button(onClick = {
                                tela.value = "inicio"
                                status.value = "Escolha o modo"
                            }) { Text("Voltar") }
                        }

                        "rodando" -> {
                            encontrados.values.forEach { p ->
                                Button(onClick = { selecionar(p) }) {
                                    Text("${p.nome}  |  RSSI=${p.rssi} dBm")
                                }
                            }
                        }

                        "inspecionar" -> {
                            Text("INSPECIONAR", textAlign = TextAlign.Center)

                            OutlinedTextField(
                                value = intervaloTexto.value,
                                onValueChange = { intervaloTexto.value = it },
                                label = { Text("Intervalo entre leituras (s)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = salvarLog.value,
                                    onCheckedChange = { salvarLog.value = it }
                                )
                                Text("Salvar log em CSV")
                            }

                            Button(onClick = { iniciarColeta() }) { Text("Iniciar coleta") }
                            Button(onClick = { desconectar() }) { Text("Desconectar") }
                        }

                        "coletando" -> {
                            Text(
                                "Intervalo: $intervaloMs ms  |  Salvar log: ${if (salvarLog.value) "sim" else "não"}",
                                textAlign = TextAlign.Center
                            )

                            val ultima = ultimasLeituras.firstOrNull()
                            if (ultima == null) {
                                Text("Aguardando a primeira leitura...")
                            } else {
                                Text(
                                    "RSSI: ${ultima.rssi} dBm",
                                    style = MaterialTheme.typography.headlineMedium
                                )
                                Text(
                                    "PHY: TX ${nomePhy(ultima.txPhy)}  /  RX ${nomePhy(ultima.rxPhy)}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            Text("Leituras: ${totalLeituras.value}   |   Puladas: ${puladas.value}")

                            Button(onClick = {
                                val info = encerrarColeta("encerrada_manual", null)
                                tela.value = "inspecionar"
                                status.value = "Conectado a $nomeSelecionado"
                                if (info != null) {
                                    aviso.value = "Coleta encerrada" to info
                                }
                            }) { Text("Encerrar coleta") }

                            // Lista das últimas leituras, com rolagem própria
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                ultimasLeituras.forEach { l ->
                                    Text(
                                        "${l.horaTexto}    ${l.rssi} dBm    ${nomePhy(l.txPhy)}/${nomePhy(l.rxPhy)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Janela de aviso (aparece por cima da tela quando aviso != null)
            aviso.value?.let { (titulo, mensagem) ->
                AlertDialog(
                    onDismissRequest = { aviso.value = null },
                    confirmButton = {
                        TextButton(onClick = { aviso.value = null }) { Text("OK") }
                    },
                    title = { Text(titulo) },
                    text = { Text(mensagem) }
                )
            }
        }
        pedirPermissoes.launch(permissoes)
    }
}