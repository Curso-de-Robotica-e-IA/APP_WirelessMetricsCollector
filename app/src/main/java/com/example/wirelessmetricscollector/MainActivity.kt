package com.example.wirelessmetricscollector

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
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
import androidx.compose.material3.OutlinedButton
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
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Periferico(val nome: String, val device: BluetoothDevice, val rssi: Int)

// Uma leitura da coleta
data class Leitura(
    val horaTexto: String,     // horário legível, para a tela
    val epochMs: Long,         // relógio do celular (ms), para alinhar com o robô
    val monoNs: Long,          // relógio monotônico (ns), para medir intervalos
    val rssi: Int,
    val txPhy: Int,
    val rxPhy: Int,
    val mtu: Int,              // MTU negociado na conexão
    val tamanhoPacote: Int,    // bytes por mensagem nesta coleta
    val bytesJanela: Long,     // bytes recebidos desde a leitura anterior
    val janelaMs: Double,      // duração da janela (ms)
    val vazaoKbps: Double,     // velocidade de envio na janela (kbps)
    val pacotesTotal: Long,    // pacotes recebidos desde o início da coleta
    val pacotesFaltando: Long  // pacotes perdidos (saltos na sequência) desde o início
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
        // Característica por onde o advertiser envia os pacotes (notificação)
        val DADOS_UUID: UUID = UUID.fromString("6e4b2f1b-9c3d-4a7e-b5f2-8d1c0e9a7b64")
        // Característica por onde o scanner manda "começar" / "parar"
        val CONTROLE_UUID: UUID = UUID.fromString("6e4b2f1c-9c3d-4a7e-b5f2-8d1c0e9a7b64")
        // Descritor padrão do Bluetooth para ligar notificações
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val ID_FABRICANTE = 0xFFFF   // identificador reservado para testes
        const val LINHAS_NA_TELA = 30
        const val PASTA_LOGS = "WirelessMetricsCollector"
        const val MTU_PEDIDO = 517

        const val CMD_PARAR: Byte = 0
        const val CMD_INICIAR: Byte = 1

        // Limite do BLE para o tamanho de um valor, independente do MTU
        const val MAX_ATRIBUTO = 512

        // Opções de tamanho do pacote (bytes por mensagem).
        // O máximo é o menor entre MTU - 3 e 512.
        val TAMANHOS_PACOTE = listOf(20, 50, 100, 200, 244, 512)
    }

    private val status = mutableStateOf("Aguardando permissões...")
    private val permissoesOk = mutableStateOf(false)
    // "inicio", "configPeriferico", "configScanner", "rodando", "preparando", "inspecionar", "coletando"
    private val tela = mutableStateOf("inicio")
    private val nomePeriferico = mutableStateOf("")
    private val encontrados = mutableStateMapOf<String, Periferico>()

    // Janela de aviso: par (título, mensagem). null = nenhuma janela aberta
    private val aviso = mutableStateOf<Pair<String, String>?>(null)

    // Integração ROS 2
    private val publicarRos = mutableStateOf(true)
    private val rosUrl = mutableStateOf("ws://localhost:9090")
    private val rosTopico = mutableStateOf("/tb1/ble")
    private val rosStatus = mutableStateOf("")
    private var ros: RosPublisher? = null

    // Configuração da coleta
    private val intervaloTexto = mutableStateOf("0.25")   // o que está digitado na caixa, em segundos
    private val salvarLog = mutableStateOf(true)
    private var intervaloMs = 250L                        // intervalo validado, em milissegundos
    private val tamanhoPacote = mutableStateOf(512)       // bytes por mensagem (escolhido na tela)

    // Estado da coleta
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var coletando = false
    @Volatile private var leituraEmAndamento = false
    private var aguardandoInicio = false   // pediu "começar" ao advertiser e espera a confirmação
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

    // Contagem dos pacotes recebidos (atualizada na thread do Bluetooth)
    private val trava = Any()
    @Volatile private var recebendo = false
    private var bytesRecebidos = 0L
    private var pacotesRecebidos = 0L
    private var pacotesFaltando = 0L
    private var proximaSeq = 0L
    private var inicioRecebimentoNs = 0L
    // Valores congelados no momento da leitura de RSSI
    private var bytesNaLeitura = 0L
    private var pacotesNaLeitura = 0L
    private var faltandoNaLeitura = 0L
    // Fim da janela anterior (para calcular a vazão)
    private var bytesUltimaJanela = 0L
    private var nsUltimaJanela = 0L

    // Scanner (central)
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var caracDados: BluetoothGattCharacteristic? = null
    private var caracControle: BluetoothGattCharacteristic? = null
    @Volatile private var mtuAtual = 23
    private val mtuTela = mutableStateOf(23)
    private var nomeSelecionado = ""
    private var conectou = false            // a conexão chegou a subir?
    private var desconexaoManual = false    // a desconexão foi pedida pelo botão?
    @Volatile private var bleConectado = false   // estado atual da conexão BLE (vai no status_ble)

    // Advertiser (periférico)
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var caracDadosServidor: BluetoothGattCharacteristic? = null
    @Volatile private var dispositivoConectado: BluetoothDevice? = null
    @Volatile private var mtuServidor = 23
    @Volatile private var enviando = false
    @Volatile private var notificacaoPendente = false
    private var seqEnvio = 0L
    private var pacotesEnviados = 0L
    private var falhasEnvio = 0L
    @Volatile private var ultimoErro = ""   // diagnóstico: último erro no envio (aparece na tela)
    private var iniciosRecebidos = 0          // diagnóstico: quantos "começar" chegaram
    private var pacoteBase = ByteArray(0)

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

    // Maior pacote permitido para um MTU: o menor entre MTU - 3 e 512
    private fun pacoteMaximo(mtu: Int) = minOf(mtu - 3, MAX_ATRIBUTO)

    // Número com ponto decimal (para o CSV e o ROS não dependerem do idioma do celular)
    private fun num(x: Double, casas: Int = 2) = String.format(Locale.US, "%.${casas}f", x)

    // ================= ROS 2 =================

    // Abre a conexão com o rosbridge (se a opção estiver marcada)
    private fun iniciarRos() {
        encerrarRos()
        if (!publicarRos.value) return
        ros = RosPublisher(rosUrl.value.trim(), rosTopico.value.trim()) { estado ->
            runOnUiThread { rosStatus.value = "ROS: $estado" }
        }
        ros?.conectar()
    }

    // Fecha a conexão com o rosbridge
    private fun encerrarRos() {
        ros?.encerrar()
        ros = null
        rosStatus.value = ""
    }

    // Publica um evento no tópico ROS (não faz nada se o ROS estiver desligado)
    private fun enviarRos(
        evento: String,
        epochMs: Long = System.currentTimeMillis(),
        monoNs: Long = SystemClock.elapsedRealtimeNanos(),
        extras: (JSONObject) -> Unit = {}
    ) {
        val r = ros ?: return
        val dados = JSONObject()
            .put("evento", evento)
            .put("advertiser", nomeSelecionado)
            .put("status_ble", if (bleConectado) "conectado" else "desconectado")
            .put("epoch_ms", epochMs)
            .put("mono_ns", monoNs)
        extras(dados)
        r.publicar(dados)
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
        bleConectado = false
        mtuAtual = 23
        mtuTela.value = 23
        encontrados.clear()
        status.value = "Conectando a ${p.nome}..."
        Log.d("Coleta", "conectando a ${p.nome} ${p.device.address}")

        gatt = p.device.connectGatt(this, false, callbackGatt, BluetoothDevice.TRANSPORT_LE)
    }

    // Liga as notificações da característica de dados (escreve no descritor CCCD)
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun ligarNotificacoes(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD_UUID) ?: return false
        val valor = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, valor) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = valor
            g.writeDescriptor(d)
        }
    }

    // Manda um comando ao advertiser: [comando, tamanho (2 bytes)]
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun enviarComando(cmd: Byte, tamanho: Int): Boolean {
        val g = gatt ?: return false
        val c = caracControle ?: return false
        val valor = byteArrayOf(cmd, (tamanho and 0xFF).toByte(), ((tamanho shr 8) and 0xFF).toByte())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, valor, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = valor
            g.writeCharacteristic(c)
        }
    }

    // Pede ao advertiser para parar de enviar (tenta de novo se o Bluetooth estiver ocupado)
    private fun enviarParar(tentativa: Int = 0) {
        if (!bleConectado) return
        val ok = enviarComando(CMD_PARAR, 0)
        if (!ok && tentativa < 20) {
            handler.postDelayed({ enviarParar(tentativa + 1) }, 50)
        }
    }

    // Conta um pacote recebido do advertiser
    private fun contarPacote(valor: ByteArray) {
        if (!recebendo || valor.size < 4) return
        val seq = (valor[0].toLong() and 0xFF) or
            ((valor[1].toLong() and 0xFF) shl 8) or
            ((valor[2].toLong() and 0xFF) shl 16) or
            ((valor[3].toLong() and 0xFF) shl 24)
        synchronized(trava) {
            if (pacotesRecebidos == 0L) Log.d("Coleta", "scanner: primeiro pacote recebido (${valor.size} B)")
            bytesRecebidos += valor.size
            pacotesRecebidos++
            if (seq > proximaSeq) pacotesFaltando += seq - proximaSeq
            proximaSeq = seq + 1
        }
    }

    private val callbackGatt = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, novoEstado: Int) {
            Log.d("Coleta", "scanner: estado=$novoEstado status=$st")
            runOnUiThread {
                if (novoEstado == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                    // ---- Conectou: agora negocia o MTU ----
                    conectou = true
                    bleConectado = true
                    status.value = "Conectado a $nomeSelecionado. Negociando MTU..."
                    tela.value = "preparando"
                    g.requestMtu(MTU_PEDIDO)

                } else if (novoEstado == BluetoothProfile.STATE_DISCONNECTED || st != BluetoothGatt.GATT_SUCCESS) {
                    // ---- Desconectou ou falhou ----
                    bleConectado = false
                    aguardandoInicio = false
                    val eventoColeta = if (desconexaoManual) "desconectado_manual" else "conexao_perdida"
                    val infoColeta = encerrarColeta(eventoColeta, st)   // null se não havia coleta rodando
                    recebendo = false
                    g.close()
                    gatt = null
                    caracDados = null
                    caracControle = null
                    tela.value = "inicio"

                    // Publica o evento de conexão no ROS e fecha a ligação com o rosbridge
                    val eventoConexao = when {
                        !conectou -> "falha_conexao"
                        desconexaoManual -> "desconectado_manual"
                        else -> "conexao_perdida"
                    }
                    enviarRos(eventoConexao) {
                        it.put("codigo", st).put("motivo", motivo(st))
                    }
                    encerrarRos()

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

        // MTU negociado -> pede prioridade alta e procura os serviços
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            val valor = if (st == BluetoothGatt.GATT_SUCCESS) mtu else 23
            mtuAtual = valor
            Log.d("Coleta", "MTU negociado: $valor (status $st)")
            runOnUiThread {
                mtuTela.value = valor
                status.value = "MTU $valor. Procurando serviço de dados..."
            }
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            g.discoverServices()
        }

        // Serviços encontrados -> liga as notificações de dados
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val servico = g.getService(SERVICE_UUID)
            val dados = servico?.getCharacteristic(DADOS_UUID)
            val controle = servico?.getCharacteristic(CONTROLE_UUID)

            if (st != BluetoothGatt.GATT_SUCCESS || dados == null || controle == null) {
                runOnUiThread {
                    aviso.value = "Serviço de dados não encontrado" to
                        "O advertiser não tem o serviço de envio de pacotes.\n\n" +
                        "Instale a mesma versão do app nos dois celulares."
                    desconectar()
                }
                return
            }

            caracDados = dados
            caracControle = controle
            Log.d("Coleta", "scanner: serviço de dados encontrado, ligando notificações")
            if (!ligarNotificacoes(g, dados)) {
                Log.e("Coleta", "scanner: falha ao pedir para ligar as notificações")
                runOnUiThread {
                    aviso.value = "Erro" to "Não foi possível ligar as notificações de dados."
                    desconectar()
                }
            }
        }

        // Notificações ligadas -> conexão pronta para coletar
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            Log.d("Coleta", "scanner: notificações ligadas, status $st")
            runOnUiThread {
                if (st == BluetoothGatt.GATT_SUCCESS) {
                    status.value = "Conectado a $nomeSelecionado"
                    tela.value = "inspecionar"
                    aviso.value = "Conectado" to
                        "A conexão com \"$nomeSelecionado\" foi estabelecida com sucesso.\n\n" +
                        "MTU negociado: $mtuAtual bytes (pacote máximo: ${pacoteMaximo(mtuAtual)} bytes)."
                    enviarRos("conectado") { it.put("mtu", mtuAtual) }
                } else {
                    aviso.value = "Erro" to "Não foi possível ligar as notificações de dados (código $st)."
                    desconectar()
                }
            }
        }

        // Resposta do advertiser ao comando "começar" / "parar"
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            if (c.uuid != CONTROLE_UUID) return
            Log.d("Coleta", "scanner: comando confirmado pelo advertiser, status $st")
            runOnUiThread {
                if (!aguardandoInicio) return@runOnUiThread
                aguardandoInicio = false
                if (st == BluetoothGatt.GATT_SUCCESS) {
                    comecarTick()
                } else {
                    recebendo = false
                    status.value = "Conectado a $nomeSelecionado"
                    aviso.value = "Erro" to "O advertiser não confirmou o início do envio (código $st)."
                }
            }
        }

        // Chegou um pacote (Android 13 ou mais novo)
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            valor: ByteArray
        ) {
            if (c.uuid == DADOS_UUID) contarPacote(valor)
        }

        // Chegou um pacote (Android 12 ou mais antigo)
        @Deprecated("Usado só no Android 12 ou mais antigo")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return   // já contado acima
            if (c.uuid == DADOS_UUID) contarPacote(c.value ?: return)
        }

        // 1ª etapa da leitura: chegou o RSSI -> congela a contagem de bytes e pede o PHY
        @SuppressLint("MissingPermission")
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, st: Int) {
            if (st == BluetoothGatt.GATT_SUCCESS && coletando) {
                rssiAtual = rssi
                tempoMsAtual = System.currentTimeMillis()
                tempoNsAtual = SystemClock.elapsedRealtimeNanos()
                synchronized(trava) {
                    bytesNaLeitura = bytesRecebidos
                    pacotesNaLeitura = pacotesRecebidos
                    faltandoNaLeitura = pacotesFaltando
                }
                g.readPhy()
            } else {
                leituraEmAndamento = false
            }
        }

        // 2ª etapa da leitura: chegou o PHY -> calcula a vazão e completa a leitura
        override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, st: Int) {
            leituraEmAndamento = false
            if (st != BluetoothGatt.GATT_SUCCESS || !coletando) return

            val bytesJanela = bytesNaLeitura - bytesUltimaJanela
            val janelaMs = (tempoNsAtual - nsUltimaJanela) / 1_000_000.0
            // bytes * 8 = bits; bits por milissegundo = kilobits por segundo
            val vazaoKbps = if (janelaMs > 0) bytesJanela * 8.0 / janelaMs else 0.0
            bytesUltimaJanela = bytesNaLeitura
            nsUltimaJanela = tempoNsAtual

            val leitura = Leitura(
                horaTexto = formatoHora.format(Date(tempoMsAtual)),
                epochMs = tempoMsAtual,
                monoNs = tempoNsAtual,
                rssi = rssiAtual,
                txPhy = txPhy,
                rxPhy = rxPhy,
                mtu = mtuAtual,
                tamanhoPacote = tamanhoPacote.value,
                bytesJanela = bytesJanela,
                janelaMs = janelaMs,
                vazaoKbps = vazaoKbps,
                pacotesTotal = pacotesNaLeitura,
                pacotesFaltando = faltandoNaLeitura
            )
            Log.d(
                "Coleta",
                "leitura ${leitura.horaTexto} rssi=${leitura.rssi} phy=${nomePhy(txPhy)}/${nomePhy(rxPhy)} " +
                    "vazao=${num(vazaoKbps)}kbps"
            )

            runOnUiThread {
                if (!coletando) return@runOnUiThread   // coleta encerrada enquanto a leitura chegava
                todasLeituras.add(leitura)
                ultimasLeituras.add(0, leitura)
                if (ultimasLeituras.size > LINHAS_NA_TELA) {
                    ultimasLeituras.removeAt(ultimasLeituras.size - 1)
                }
                totalLeituras.value = todasLeituras.size

                // Publica a leitura no ROS
                enviarRos("leitura", leitura.epochMs, leitura.monoNs) {
                    it.put("hora", leitura.horaTexto)
                        .put("rssi_dbm", leitura.rssi)
                        .put("tx_phy", nomePhy(leitura.txPhy))
                        .put("rx_phy", nomePhy(leitura.rxPhy))
                        .put("mtu", leitura.mtu)
                        .put("tamanho_pacote", leitura.tamanhoPacote)
                        .put("bytes_janela", leitura.bytesJanela)
                        .put("janela_ms", leitura.janelaMs)
                        .put("vazao_kbps", leitura.vazaoKbps)
                        .put("pacotes_total", leitura.pacotesTotal)
                        .put("pacotes_faltando", leitura.pacotesFaltando)
                }
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

    // Botão "Iniciar coleta": valida, zera os contadores e pede ao advertiser para começar a enviar
    private fun iniciarColeta() {
        if (aguardandoInicio) return

        val segundos = intervaloTexto.value.replace(",", ".").toDoubleOrNull()
        if (segundos == null || segundos < 0.05) {
            aviso.value = "Intervalo inválido" to
                "Digite um número em segundos, a partir de 0.05 (exemplo: 0.25)."
            return
        }
        val maxPacote = pacoteMaximo(mtuAtual)
        if (tamanhoPacote.value > maxPacote) {
            aviso.value = "Pacote grande demais" to
                "Com MTU $mtuAtual, o pacote pode ter no máximo $maxPacote bytes."
            return
        }
        intervaloMs = (segundos * 1000).toLong()

        // Zera tudo da coleta anterior
        todasLeituras.clear()
        ultimasLeituras.clear()
        totalLeituras.value = 0
        puladas.value = 0
        leituraEmAndamento = false
        synchronized(trava) {
            bytesRecebidos = 0
            pacotesRecebidos = 0
            pacotesFaltando = 0
            proximaSeq = 0
        }
        bytesUltimaJanela = 0
        nsUltimaJanela = SystemClock.elapsedRealtimeNanos()
        inicioRecebimentoNs = nsUltimaJanela
        recebendo = true

        if (!enviarComando(CMD_INICIAR, tamanhoPacote.value)) {
            recebendo = false
            aviso.value = "Erro" to "Não foi possível pedir ao advertiser para começar o envio. Tente de novo."
            return
        }
        aguardandoInicio = true
        status.value = "Iniciando envio..."
    }

    // Advertiser confirmou: começa o relógio das leituras
    private fun comecarTick() {
        inicioColetaMs = System.currentTimeMillis()
        coletando = true
        proximoTick = SystemClock.uptimeMillis()
        handler.post(tick)

        Log.d(
            "Coleta",
            "coleta iniciada: intervalo=${intervaloMs}ms mtu=$mtuAtual pacote=${tamanhoPacote.value}B " +
                "salvarLog=${salvarLog.value}"
        )
        enviarRos("coleta_iniciada") {
            it.put("intervalo_ms", intervaloMs)
                .put("mtu", mtuAtual)
                .put("tamanho_pacote", tamanhoPacote.value)
                .put("salvar_log", salvarLog.value)
        }
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

    // Para a coleta, avisa o advertiser, publica no ROS, salva o CSV (se marcado) e devolve um resumo.
    // Devolve null se não havia coleta rodando.
    private fun encerrarColeta(evento: String, codigo: Int?): String? {
        // Marca o horário do término antes de qualquer outra coisa
        val agoraMs = System.currentTimeMillis()
        val agoraNs = SystemClock.elapsedRealtimeNanos()

        if (!pararColeta()) return null
        recebendo = false
        enviarParar()   // só envia se a conexão ainda estiver de pé

        val (bytesTotal, pacotesTotal, faltandoTotal) = synchronized(trava) {
            Triple(bytesRecebidos, pacotesRecebidos, pacotesFaltando)
        }
        val duracaoMs = (agoraNs - inicioRecebimentoNs) / 1_000_000.0
        val vazaoMedia = if (duracaoMs > 0) bytesTotal * 8.0 / duracaoMs else 0.0

        val fim = FimColeta(
            evento = evento,
            codigo = codigo,
            motivo = if (codigo == null) "Encerrada pelo botão" else motivo(codigo),
            epochMs = agoraMs,
            monoNs = agoraNs
        )

        // Publica o fim da coleta no ROS
        enviarRos("coleta_encerrada", fim.epochMs, fim.monoNs) {
            it.put("termino", fim.evento)
                .put("codigo", fim.codigo ?: JSONObject.NULL)
                .put("motivo", fim.motivo)
                .put("leituras", todasLeituras.size)
                .put("puladas", puladas.value)
                .put("mtu", mtuAtual)
                .put("tamanho_pacote", tamanhoPacote.value)
                .put("bytes_total", bytesTotal)
                .put("pacotes_total", pacotesTotal)
                .put("pacotes_faltando", faltandoTotal)
                .put("vazao_media_kbps", vazaoMedia)
        }

        val resumo = "Leituras: ${todasLeituras.size}\nPuladas: ${puladas.value}\n" +
            "MTU: $mtuAtual  |  Pacote: ${tamanhoPacote.value} B\n" +
            "Pacotes recebidos: $pacotesTotal  |  Faltando: $faltandoTotal\n" +
            "Bytes recebidos: $bytesTotal\n" +
            "Vazão média: ${num(vazaoMedia, 1)} kbps\n" +
            "Término: $evento"

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
        sb.append(
            "hora,epoch_ms,mono_ns,rssi_dbm,tx_phy,rx_phy,mtu,tamanho_pacote," +
                "bytes_janela,janela_ms,vazao_kbps,pacotes_total,pacotes_faltando,evento,codigo,motivo\n"
        )

        // Uma linha por leitura
        for (l in todasLeituras) {
            sb.append("${l.horaTexto},${l.epochMs},${l.monoNs},${l.rssi},")
            sb.append("${nomePhy(l.txPhy)},${nomePhy(l.rxPhy)},${l.mtu},${l.tamanhoPacote},")
            sb.append("${l.bytesJanela},${num(l.janelaMs, 1)},${num(l.vazaoKbps)},")
            sb.append("${l.pacotesTotal},${l.pacotesFaltando},leitura,,\n")
        }

        // Última linha: como a coleta terminou (colunas de medição vazias)
        val horaFim = formatoHora.format(Date(fim.epochMs))
        val motivoCsv = "\"" + fim.motivo.replace("\"", "\"\"") + "\""
        sb.append("$horaFim,${fim.epochMs},${fim.monoNs}")
        sb.append(",".repeat(10))
        sb.append(",${fim.evento},${fim.codigo ?: ""},$motivoCsv\n")

        return sb.toString()
    }

    private fun nomeArquivoCsv(): String {
        val inicio = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(inicioColetaMs))
        val nomeLimpo = nomeSelecionado.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "coleta_${nomeLimpo}_p${tamanhoPacote.value}_$inicio.csv"
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

        // Serviço do experimento com duas características:
        // - dados: o advertiser envia os pacotes por notificação
        // - controle: o scanner escreve "começar" / "parar"
        val servico = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val dados = BluetoothGattCharacteristic(
            DADOS_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        )
        dados.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        val controle = BluetoothGattCharacteristic(
            CONTROLE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        servico.addCharacteristic(dados)
        servico.addCharacteristic(controle)
        gattServer?.addService(servico)
        caracDadosServidor = dados

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

    // Envia o próximo pacote. O seguinte só sai quando este for despachado (onNotificationSent).
    // Envia o próximo pacote, registrando qualquer erro (o Android esconde erros dentro dos callbacks)
    private fun enviarProximo() {
        try {
            enviarProximoSemProtecao()
        } catch (e: Exception) {
            ultimoErro = e.toString()
            Log.e("Coleta", "advertiser: erro ao enviar", e)
            runOnUiThread { status.value = "ADVERTISER: erro ao enviar: $ultimoErro" }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun enviarProximoSemProtecao() {
        if (!enviando) {
            Log.w("Coleta", "advertiser: enviarProximo chamado com enviando=false")
            return
        }
        val d = dispositivoConectado
        val c = caracDadosServidor
        val s = gattServer
        if (d == null || c == null || s == null) {
            Log.e("Coleta", "advertiser: não dá para enviar (device=$d carac=$c server=$s)")
            ultimoErro = "sem destino (device=${d != null}, carac=${c != null}, server=${s != null})"
            runOnUiThread {
                status.value = "ADVERTISER: erro, não dá para enviar " +
                    "(device=${d != null}, carac=${c != null}, server=${s != null})"
            }
            return
        }

        // Pacote = número de sequência (4 bytes) + enchimento até o tamanho escolhido
        val pacote = pacoteBase.copyOf()
        for (i in 0 until 4) {
            pacote[i] = ((seqEnvio shr (8 * i)) and 0xFF).toByte()
        }

        notificacaoPendente = true
        val codigo: Int
        val ok: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            codigo = s.notifyCharacteristicChanged(d, c, false, pacote)
            ok = codigo == BluetoothStatusCodes.SUCCESS
        } else {
            c.value = pacote
            ok = s.notifyCharacteristicChanged(d, c, false)
            codigo = if (ok) 0 else -1
        }

        if (ok) {
            if (seqEnvio == 0L) Log.d("Coleta", "advertiser: primeiro pacote enviado (${pacote.size} B)")
            seqEnvio++
            pacotesEnviados++
        } else {
            // Falhou (fila cheia ou erro): tenta de novo em alguns milissegundos
            falhasEnvio++
            ultimoErro = "envio recusado pelo Android (código $codigo)"
            if (falhasEnvio <= 5 || falhasEnvio % 500 == 0L) {
                Log.w("Coleta", "advertiser: falha ao enviar pacote (código $codigo), falhas=$falhasEnvio")
            }
            notificacaoPendente = false
            handler.postDelayed({ if (!notificacaoPendente) enviarProximo() }, 5)
        }
    }

    // Trata os comandos do scanner
    private fun tratarComando(device: BluetoothDevice, valor: ByteArray) {
        Log.d("Coleta", "advertiser: comando recebido ${valor.joinToString(",")} de ${device.address}")
        if (valor.isEmpty()) return
        // Quem mandou o comando é o scanner: é para ele que os pacotes vão
        dispositivoConectado = device
        when (valor[0]) {
            CMD_INICIAR -> {
                iniciosRecebidos++
                if (valor.size < 3) {
                    ultimoErro = "comando começar com ${valor.size} bytes (esperado 3)"
                    return
                }
                val pedido = (valor[1].toInt() and 0xFF) or ((valor[2].toInt() and 0xFF) shl 8)
                val tamanho = pedido.coerceIn(4, pacoteMaximo(mtuServidor))
                pacoteBase = ByteArray(tamanho) { (it % 256).toByte() }
                seqEnvio = 0
                pacotesEnviados = 0
                falhasEnvio = 0
                ultimoErro = ""
                enviando = true
                Log.d("Coleta", "advertiser: começando envio, pacote=${tamanho}B mtu=$mtuServidor")
                runOnUiThread {
                    status.value = "ADVERTISER \"${nomePeriferico.value}\": enviando pacotes de $tamanho bytes " +
                        "(MTU $mtuServidor)..."
                }
                if (notificacaoPendente) {
                    Log.w("Coleta", "advertiser: havia notificação pendente, esperando o callback")
                }
                if (!notificacaoPendente) enviarProximo()
            }
            CMD_PARAR -> {
                enviando = false
                val total = pacotesEnviados
                val falhas = falhasEnvio
                val erro = ultimoErro
                Log.d("Coleta", "advertiser: envio parado, $total pacotes enviados, $falhas falhas, erro=$erro")
                runOnUiThread {
                    status.value = "ADVERTISER \"${nomePeriferico.value}\": envio parado " +
                        "($total pacotes enviados, $falhas falhas, " +
                        "\"começar\" recebidos: $iniciosRecebidos)" +
                        (if (erro.isNotEmpty()) "\nÚltimo erro: $erro" else "\nSem erros registrados")
                }
            }
        }
    }

    private val callbackServidor = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, st: Int, novoEstado: Int) {
            Log.d("Coleta", "advertiser: ${device.address} estado=$novoEstado status=$st")
            if (novoEstado == BluetoothProfile.STATE_CONNECTED) {
                dispositivoConectado = device
            } else if (novoEstado == BluetoothProfile.STATE_DISCONNECTED && device == dispositivoConectado) {
                // Só reage se quem desconectou foi o scanner (ignora relógio, fone etc.)
                enviando = false
                notificacaoPendente = false
                dispositivoConectado = null
                mtuServidor = 23
            }
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

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtuServidor = mtu
            Log.d("Coleta", "advertiser: MTU $mtu")
        }

        // Scanner ligando as notificações (escrita no CCCD)
        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        // Scanner mandando "começar" / "parar"
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (characteristic.uuid == CONTROLE_UUID && value != null) {
                try {
                    tratarComando(device, value)
                } catch (e: Exception) {
                    ultimoErro = e.toString()
                    Log.e("Coleta", "advertiser: erro ao tratar comando", e)
                    runOnUiThread { status.value = "ADVERTISER: erro no comando: $ultimoErro" }
                }
            }
        }

        // Pacote anterior despachado -> manda o próximo
        override fun onNotificationSent(device: BluetoothDevice, st: Int) {
            if (st != BluetoothGatt.GATT_SUCCESS) {
                Log.w("Coleta", "advertiser: notificação despachada com status $st")
            }
            notificacaoPendente = false
            if (enviando) enviarProximo()
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

                // Estado da conexão com o ROS (só aparece no modo Scanner com ROS ligado)
                if (rosStatus.value.isNotEmpty()) {
                    Text(
                        rosStatus.value,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (permissoesOk.value) {
                    when (tela.value) {

                        "inicio" -> {
                            Button(onClick = {
                                tela.value = "configPeriferico"
                                status.value = "Dê um nome a este advertiser"
                            }) { Text("Modo Advertiser") }

                            Button(onClick = {
                                tela.value = "configScanner"
                                status.value = "Configure o envio para o ROS 2"
                            }) { Text("Modo Scanner") }
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

                        "configScanner" -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = publicarRos.value,
                                    onCheckedChange = { publicarRos.value = it }
                                )
                                Text("Publicar no ROS 2")
                            }

                            if (publicarRos.value) {
                                OutlinedTextField(
                                    value = rosUrl.value,
                                    onValueChange = { rosUrl.value = it },
                                    label = { Text("Endereço do rosbridge") },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = rosTopico.value,
                                    onValueChange = { rosTopico.value = it },
                                    label = { Text("Tópico") },
                                    singleLine = true
                                )
                            }

                            Button(onClick = {
                                iniciarRos()
                                iniciarCentral()
                            }) { Text("Começar scan") }

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

                        "preparando" -> {
                            Button(onClick = { desconectar() }) { Text("Cancelar") }
                        }

                        "inspecionar" -> {
                            Text("INSPECIONAR", textAlign = TextAlign.Center)

                            Text(
                                "MTU negociado: ${mtuTela.value} bytes (pacote máximo: ${pacoteMaximo(mtuTela.value)} bytes)",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall
                            )

                            OutlinedTextField(
                                value = intervaloTexto.value,
                                onValueChange = { intervaloTexto.value = it },
                                label = { Text("Intervalo entre leituras (s)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )

                            // Escolha do tamanho do pacote: toque numa opção
                            Text("Tamanho do pacote (bytes)", textAlign = TextAlign.Center)
                            TAMANHOS_PACOTE.chunked(3).forEach { linha ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    linha.forEach { tamanho ->
                                        if (tamanho == tamanhoPacote.value) {
                                            // Opção selecionada: botão cheio
                                            Button(onClick = { tamanhoPacote.value = tamanho }) {
                                                Text("$tamanho")
                                            }
                                        } else {
                                            OutlinedButton(onClick = { tamanhoPacote.value = tamanho }) {
                                                Text("$tamanho")
                                            }
                                        }
                                    }
                                }
                            }
                            Text(
                                "Selecionado: ${tamanhoPacote.value} bytes",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall
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
                                "Intervalo: $intervaloMs ms  |  MTU: ${mtuTela.value}  |  " +
                                    "Pacote: ${tamanhoPacote.value} B  |  " +
                                    "Salvar log: ${if (salvarLog.value) "sim" else "não"}",
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
                                    "Vazão: ${num(ultima.vazaoKbps, 1)} kbps",
                                    style = MaterialTheme.typography.headlineMedium
                                )
                                Text(
                                    "PHY: TX ${nomePhy(ultima.txPhy)}  /  RX ${nomePhy(ultima.rxPhy)}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "Pacotes: ${ultima.pacotesTotal}  |  Faltando: ${ultima.pacotesFaltando}",
                                    textAlign = TextAlign.Center
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
                            Text(
                                "hora    RSSI    PHY    MTU    vazão",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                ultimasLeituras.forEach { l ->
                                    Text(
                                        "${l.horaTexto}    ${l.rssi} dBm    " +
                                            "${nomePhy(l.txPhy)}/${nomePhy(l.rxPhy)}    " +
                                            "${l.mtu}    ${num(l.vazaoKbps, 1)} kbps",
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

    // Ao fechar o app, para o envio e encerra a conexão com o ROS
    override fun onDestroy() {
        enviando = false
        encerrarRos()
        super.onDestroy()
    }
}