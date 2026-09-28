# WirelessMetricsCollector

App Android para **medir a qualidade de uma conexão Bluetooth Low Energy (BLE) entre dois celulares**.

Um celular anuncia (**Advertiser**) e o outro se conecta a ele (**Scanner**). Durante a conexão, o Scanner registra periodicamente o **RSSI** (força do sinal) e o **PHY** (camada física em uso), com timestamp, e salva tudo em **CSV**. O app foi pensado para experimentos com celulares montados em robôs móveis.

---

## Sumário

- [Requisitos](#requisitos)
- [Compilar e instalar](#compilar-e-instalar)
- [Os dois modos](#os-dois-modos)
- [Fluxo de uso](#fluxo-de-uso)
- [Telas](#telas)
- [Como funciona a coleta](#como-funciona-a-coleta)
- [Arquivo CSV](#arquivo-csv)
- [Códigos de desconexão](#códigos-de-desconexão)
- [Parâmetros fixos](#parâmetros-fixos)
- [Estrutura do código](#estrutura-do-código)
- [Permissões](#permissões)
- [Limitações conhecidas](#limitações-conhecidas)
- [Próximos passos](#próximos-passos)

---

## Requisitos

| Item | Valor |
|---|---|
| Android mínimo | 8.0 (API 26) |
| Testado em | Android 15 e 16 (Samsung SM-A146M e SM-S721B) |
| Hardware | Bluetooth Low Energy nos dois celulares |
| Linguagem / interface | Kotlin + Jetpack Compose |
| Ambiente de desenvolvimento | Android Studio (Ubuntu) |

O emulador do Android **não simula Bluetooth**. Os testes precisam ser feitos em celulares reais.

---

## Compilar e instalar

1. Nos celulares, ative as **Opções do desenvolvedor** e a **Depuração USB**, e conecte-os ao computador por cabo USB.
2. Confira se eles aparecem como `device`:

   ```bash
   adb devices
   ```

3. Na raiz do projeto (a pasta que contém o `gradlew`), compile e instale em **todos** os celulares conectados:

   ```bash
   ./gradlew installDebug
   ```

4. (Opcional) Abra o app pelo terminal:

   ```bash
   adb -s <serial> shell am start -n com.example.wirelessmetricscollector/.MainActivity
   ```

5. Ao abrir o app pela primeira vez, conceda as permissões de **Dispositivos próximos** e **Localização** (Precisa, durante o uso do app).

### Obter os CSVs

Os arquivos ficam no celular **Scanner**:

```bash
adb -s <serial> shell ls /sdcard/Download/WirelessMetricsCollector/
adb -s <serial> pull /sdcard/Download/WirelessMetricsCollector/ ~/coletas_ble/
```

---

## Os dois modos

O mesmo app roda nos dois celulares. Ao abrir, escolhe-se o papel de cada um.

### Modo Advertiser (periférico)

- Recebe um **nome** (até 12 caracteres) digitado pelo usuário.
- **Anuncia** o UUID do experimento e o nome, e abre um **servidor GATT** que aceita conexões.
- Mostra quando um scanner conecta e quando desconecta, com o motivo.
- **Reinicia o anúncio automaticamente** após cada desconexão, para poder ser encontrado de novo.
- Não coleta nem salva dados.

### Modo Scanner (central)

- Procura **apenas** advertisers do experimento (filtro pelo UUID) e lista cada um pelo nome, com o RSSI do anúncio.
- Ao tocar num advertiser, **conecta** a ele.
- Na tela **Inspecionar**, configura e inicia a coleta.
- Mostra RSSI e PHY ao vivo e salva o CSV ao final.

> Na terminologia BLE, **Advertiser/Scanner** são os papéis *antes* da conexão. Depois de conectados, os papéis passam a se chamar **Peripheral/Central**. As medições da conexão são feitas pelo **Central** (o antigo Scanner).

---

## Fluxo de uso

```
ADVERTISER                          SCANNER
Modo Advertiser                     Modo Scanner
  → digitar nome                      → lista de advertisers (nome + RSSI)
  → Começar a anunciar                → tocar no advertiser → conecta
  → "anunciando..."                   → tela INSPECIONAR
                                          - intervalo entre leituras (s)
                                          - [x] Salvar log em CSV
                                          - Iniciar coleta / Desconectar
                                      → tela de COLETA
                                          - RSSI e PHY atuais
                                          - leituras e puladas
                                          - últimas 30 leituras
                                          - Encerrar coleta
```

---

## Telas

| Tela | Conteúdo |
|---|---|
| **Início** | Botões "Modo Advertiser" e "Modo Scanner" |
| **Configurar advertiser** | Campo de nome, "Começar a anunciar", "Voltar" |
| **Rodando** | Advertiser: status do anúncio. Scanner: lista de advertisers encontrados |
| **Inspecionar** | Intervalo de coleta, caixa "Salvar log em CSV", "Iniciar coleta", "Desconectar" |
| **Coletando** | RSSI e PHY atuais, contadores, "Encerrar coleta", lista com rolagem das últimas 30 leituras |

**Avisos em janela:** conexão estabelecida, falha ao conectar, conexão perdida e coleta encerrada. Os avisos de falha e de queda trazem o motivo e o código do Android.

---

## Como funciona a coleta

- A cada **intervalo** configurado (mínimo de 0,05 s; padrão de 0,25 s), o app pede o **RSSI** da conexão (`readRemoteRssi`). Quando ele chega, pede o **PHY** (`readPhy`). As duas etapas formam uma leitura.
- O intervalo segue **horários fixos** (início + n × intervalo), para não acumular atraso em coletas longas.
- Se a leitura anterior ainda não terminou quando chega a hora da próxima, a leitura é **pulada** e contada. O número de puladas indica se o intervalo está curto demais para o aparelho.
- O **timestamp** de cada leitura é o instante em que o RSSI chega ao app.
- A tela mostra as 30 leituras mais recentes; **todas** as leituras ficam em memória e vão para o CSV.

**A coleta termina de duas formas:**

1. **Manual:** pelo botão "Encerrar coleta". A conexão continua ativa.
2. **Automática:** quando a conexão cai.

Nos dois casos o CSV é salvo (se a opção estiver marcada) e aparece um resumo com o total de leituras, as puladas, o tipo de término e o caminho do arquivo.

---

## Arquivo CSV

**Local:** `Download/WirelessMetricsCollector/` no celular Scanner (gravado via MediaStore, sem permissão extra).

**Nome:** `coleta_<advertiser>_<AAAA-MM-DD>_<HH-MM-SS>.csv`, com o horário de início da coleta.

| Coluna | Descrição |
|---|---|
| `hora` | Horário legível (`HH:mm:ss.SSS`) |
| `epoch_ms` | Relógio do celular, em ms. Serve para alinhar com outros sistemas (ex.: ROS) |
| `mono_ns` | Relógio monotônico (`elapsedRealtimeNanos`), em ns. Serve para medir intervalos com precisão |
| `rssi_dbm` | RSSI da conexão, em dBm |
| `tx_phy` / `rx_phy` | PHY de transmissão e de recepção (`1M`, `2M`, `Coded`) |
| `evento` | `leitura` nas linhas de dados; na última linha, como a coleta terminou |
| `codigo` | Código do Android no término (vazio nas leituras e no término manual) |
| `motivo` | Explicação do término, entre aspas |

### Valores de `evento`

| Valor | Significado |
|---|---|
| `leitura` | Leitura normal |
| `encerrada_manual` | Encerrada pelo botão |
| `conexao_perdida` | A conexão caiu durante a coleta |
| `desconectado_manual` | Desconectado pelo botão durante a coleta (caso raro) |

O arquivo é salvo **mesmo sem leituras**: se a conexão cair logo no início, ele contém apenas a linha de término.

### Exemplo

```
hora,epoch_ms,mono_ns,rssi_dbm,tx_phy,rx_phy,evento,codigo,motivo
10:00:00.262,1790503200262,81234567890123,-58,1M,1M,leitura,,
10:00:00.509,1790503200509,81234814567890,-91,1M,1M,leitura,,
10:00:04.931,1790503204931,81239236789012,,,,conexao_perdida,8,"Os aparelhos perderam o sinal (distância, obstáculo ou interferência)."
```

### Leitura no pandas

```python
import pandas as pd

df = pd.read_csv("coleta_Robo2_2026-09-27_10-00-00.csv")
leituras = df[df.evento == "leitura"]
fim = df[df.evento != "leitura"].iloc[0]
print(fim.evento, fim.codigo, fim.motivo)
```

---

## Códigos de desconexão

| Código | Significado |
|---|---|
| 0 | Desconexão normal |
| 8 | Perda de sinal (supervision timeout) |
| 19 | O outro aparelho encerrou |
| 22 | Este aparelho encerrou |
| 34 | O outro aparelho parou de responder |
| 62 | A conexão não chegou a ser estabelecida |
| 133 | Erro genérico da pilha Bluetooth do Android |
| 147 | Tempo esgotado ao conectar |

---

## Parâmetros fixos

| Parâmetro | Valor | Onde |
|---|---|---|
| UUID do serviço | `6e4b2f1a-9c3d-4a7e-b5f2-8d1c0e9a7b64` | `SERVICE_UUID` |
| Identificador de fabricante (nome no anúncio) | `0xFFFF` (reservado para testes) | `ID_FABRICANTE` |
| Modo de anúncio | `ADVERTISE_MODE_LOW_LATENCY` | `comecarAnuncio()` |
| Potência de transmissão | `ADVERTISE_TX_POWER_HIGH` | `comecarAnuncio()` |
| Modo de scan | `SCAN_MODE_LOW_LATENCY` | `iniciarCentral()` |
| Conexão | `autoConnect = false`, `TRANSPORT_LE` | `selecionar()` |
| Linhas na tela | 30 | `LINHAS_NA_TELA` |
| Pasta dos logs | `WirelessMetricsCollector` | `PASTA_LOGS` |

A potência de transmissão e os modos de anúncio e de scan afetam diretamente o RSSI medido. Registre-os na metodologia dos experimentos.

O nome do advertiser vai na **resposta de scan** (*scan response*), como *manufacturer data*, porque o anúncio principal (31 bytes) já é ocupado quase todo pelo UUID de 128 bits.

---

## Estrutura do código

Todo o código está em `app/src/main/java/com/example/wirelessmetricscollector/MainActivity.kt`.

| Grupo | Funções | Papel |
|---|---|---|
| Permissões | `pedirPermissoes` | Pede Bluetooth e localização ao abrir |
| Scanner | `iniciarCentral`, `callbackScan`, `selecionar` | Scan filtrado e início da conexão |
| Conexão | `callbackGatt.onConnectionStateChange`, `desconectar` | Conexão, queda, avisos |
| Leitura | `callbackGatt.onReadRemoteRssi`, `callbackGatt.onPhyRead` | Leitura em duas etapas: RSSI e depois PHY |
| Coleta | `iniciarColeta`, `tick`, `pararColeta`, `encerrarColeta` | Relógio da coleta, contagem de puladas, encerramento |
| CSV | `conteudoCsv`, `nomeArquivoCsv`, `salvarCsv`, `salvarNaPastaDownloads` | Montagem e gravação do arquivo |
| Advertiser | `iniciarPeriferico`, `comecarAnuncio`, `callbackServidor`, `callbackAnuncio` | Servidor GATT, anúncio e reanúncio |
| Utilitários | `motivo`, `nomePhy`, `adapterLigado` | Tradução de códigos e verificação do Bluetooth |
| Interface | `onCreate` (`setContent`) | Telas controladas pelo estado `tela` |

### Classes de dados

| Classe | Conteúdo |
|---|---|
| `Periferico` | Advertiser encontrado no scan: nome, aparelho, RSSI |
| `Leitura` | Uma linha de coleta: horário, `epochMs`, `monoNs`, RSSI, PHY TX/RX |
| `FimColeta` | Linha de término: evento, código, motivo, horários |

### Estados de tela (`tela`)

`inicio` → `configPeriferico` → `rodando` (Advertiser)
`inicio` → `rodando` → `inspecionar` ⇄ `coletando` (Scanner)

---

## Permissões

Declaradas em `AndroidManifest.xml`:

| Permissão | Uso |
|---|---|
| `BLUETOOTH_SCAN` (`neverForLocation`) | Procurar advertisers |
| `BLUETOOTH_CONNECT` | Conectar e ler RSSI e PHY |
| `BLUETOOTH_ADVERTISE` | Anunciar no modo Advertiser |
| `ACCESS_FINE_LOCATION` | Ler SSID/BSSID do Wi-Fi (coleta de Wi-Fi planejada) |
| `ACCESS_WIFI_STATE` | Ler informações do Wi-Fi (planejado) |
| `ACCESS_NETWORK_STATE` | Receber mudanças de rede (planejado) |

Também declarado: `uses-feature android.hardware.bluetooth_le` (obrigatório).

---

## Limitações conhecidas

- **O RSSI é medido só no Scanner (Central).** O Android não expõe o RSSI da conexão no lado periférico.
- **O PHY tende a ficar em 1M/1M**, porque o app ainda não solicita 2M ou Coded.
- **O RSSI reflete o último evento de conexão**, até um intervalo de conexão (7,5 a 50 ms) antes do timestamp.
- **Na queda por perda de sinal (código 8)**, o Android só declara a conexão perdida após o *supervision timeout* (alguns segundos). O sinal pode ter sumido antes do horário registrado na linha de término.
- **A coleta roda com o app aberto.** Ainda não há *foreground service*, então o sistema pode interromper a coleta com a tela apagada.

---

## Próximos passos

- [ ] Publicar as leituras num **tópico ROS 2** via rosbridge (WebSocket com OkHttp, túnel `adb reverse` pelo cabo USB)
- [ ] Coletar a **força do sinal Wi-Fi** junto com o BLE
- [ ] Escolher a **pasta** do CSV pela tela
- [ ] Opção de **solicitar PHY** 2M ou Coded
- [ ] **Foreground service**, para coletar com a tela apagada
