<div align="center">
  
  # ＮＥＸＵＳ ＰＲＯＪＥＣＴ // ＥＳＰ３２
  
  **`[ S I S T E M A   D E   M O N I T O R A M E N T O   I N D U S T R I A L ]`**

  ![System](https://img.shields.io/badge/STATUS-OPERATIONAL-00FF00?style=for-the-badge&logo=codeforces&logoColor=black)
  ![Core](https://img.shields.io/badge/CORE-ESP32-black?style=for-the-badge&logo=espressif&logoColor=white)
  ![Security](https://img.shields.io/badge/SECURITY-LEVEL_ALPHA-red?style=for-the-badge&logo=kalilinux&logoColor=white)
</div>

---

> ⚠️ **WARNING:** ACESSO RESTRITO AO NÚCLEO DO SISTEMA NEXUS.  
> MONITORAMENTO DE TEMPERATURA, CORRENTE E VIBRAÇÃO EM TEMPO REAL.  
> `ENCRYPTED SESSION ESTABLISHED...`

## 📡 1. ESPECIFICAÇÕES DE HARDWARE (VIRTUALIZADO)
O ecossistema foi desenvolvido em um ambiente de simulação integrado diretamente no **IntelliJ IDEA**, eliminando latência externa e dependência de navegadores.

* **Microcontrolador:** ESP32
* **I/O Interface:** Display LCD 20x4 (I2C)
* **Sensores de Telemetria:** * **Clima:** DHT22
    * **Aceleração/Giroscópio:** MPU6050
    * **Corrente:** Simulada via ADC (Potenciômetro)
* **Atuadores (Resposta Tática):** Servo Motor e LED Status

---

## 📂 2. TREE STRUCTURE // FILE SYSTEM
Estrutura de diretórios otimizada para manter o core limpo e separar os binários gerados na compilação.

```text
NEXUS_ROOT/
├── 📁 .idea/          # Configurações do IntelliJ (ignorado pelo Git)
├── 📁 build/          # Binários criptografados (.bin, .elf)
├── 📁 Nexus_Project/  # Source Code em C++ (.ino)
├── ⚙️ diagram.json    # Mapeamento físico de hardware
├── ⚙️ wokwi.toml      # Linker de simulação (Memory Map)
├── 🛡️ .gitignore      # Filtro de segurança de repositório
└── 🛠️ arduino-cli.exe # Engine local de compilação

---

## ⚡ 3. PROTOCOLO DE INICIALIZAÇÃO (DEPLOYMENT)
Para replicar o ambiente Nexus do zero, execute a sequência de injeção abaixo no terminal PowerShell integrado do IntelliJ.

### [A] UPDATE & CORE INSTALL
Prepara o sistema host para reconhecer a arquitetura alvo (ESP32):
```powershell
.\arduino-cli core update-index
.\arduino-cli core install esp32:esp32

### [B] LIBRARY INJECTION
Download automático das dependências de telemetria e controle de atuadores:
```powershell
.\arduino-cli lib install "LiquidCrystal I2C"
.\arduino-cli lib install "DHT sensor library for ESPx"
.\arduino-cli lib install "Adafruit MPU6050"
.\arduino-cli lib install "Adafruit Unified Sensor"
.\arduino-cli lib install "ESP32Servo"

---

## 🛠️ 4. COMPILAÇÃO DE FIRMWARE
Sempre que o código fonte for alterado, o núcleo deve ser recompilado para injetar os novos parâmetros na pasta `/build`. [cite_start]Este processo gera os arquivos binários necessários para a execução no simulador. [cite: 68]

**Comando de Execução Mestre:**
```powershell
.\arduino-cli compile --fqbn esp32:esp32:esp32 --output-dir ./build ./Nexus_Project/Nexus_Project.ino

## 🖥️ 5. REAL-TIME SIMULATION (WOKWI)
A integração entre o código compilado e o simulador visual ocorre através do arquivo wokwi.toml. O sistema lê os arquivos estáticos gerados na pasta de build e executa o boot virtual do hardware. 

**Terminal Logs Simulados (Exemplo):**
```powershell
[SYS_INIT] Carregando firmware de ./build/Nexus_Project.ino.bin...
[SYS_INIT] Depuração ELF ativada.
[INFO] Sensores DHT22 e MPU6050 Online.
[WARN] Módulo de Vibração Ativo. Lendo Eixo X.
[STATUS] NEXUS SYSTEM OPERATIONAL.
