#include <WiFi.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <DHTesp.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <ESP32Servo.h>

// Pins and Config
#define DHT_PIN    15
#define POT_PIN    34
#define LED_PIN    12
#define SERVO_PIN  13
#define I2C_ADDR   0x27

const char* ssid = "Wokwi-GUEST";
const char* password = "";
const char* mqtt_server = "broker.hivemq.com";

WiFiClient espClient;
PubSubClient client(espClient);
LiquidCrystal_I2C lcd(I2C_ADDR, 20, 4);
DHTesp dht;
Adafruit_MPU6050 mpu;
Servo latheMotor;

bool maintenanceMode = false;

// ESTADO INICIAL: Falso (Parado aguardando comando da IHM)
bool systemActive = false;
unsigned long lastMsg = 0;

// Variáveis para o movimento suave do motor
int servoPos = 0;
int servoStep = 20;

// Handle commands from Java
void callback(char* topic, byte* payload, unsigned int length) {
  String message = "";
  for (int i = 0; i < length; i++) message += (char)payload[i];

  Serial.println("Comando IHM Recebido: " + message);

  if (message == "STOP") {
    systemActive = false;
    maintenanceMode = false; // Sai da manutenção se parar
    digitalWrite(LED_PIN, LOW);
    latheMotor.write(90);
    lcd.clear();
  }
  else if (message == "START") {
    systemActive = true;
    maintenanceMode = false;
    lcd.clear();
  }
  else if (message == "MAINTENANCE_IN") {
    systemActive = false;
    maintenanceMode = true;
    lcd.clear();
  }
  else if (message == "MAINTENANCE_OUT") {
    maintenanceMode = false;
    lcd.clear();
  }
}

void setup_wifi() {
  Serial.print("Conectando ao WiFi");
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWiFi Conectado!");
}

void reconnect() {
  while (!client.connected()) {
    Serial.print("Tentando conexao MQTT...");
    if (client.connect("NexusClient_Motor01")) {
      Serial.println("Conectado!");
      client.subscribe("nexus/motor/1/control"); // Escutando a IHM
    } else {
      Serial.print("Falhou, rc=");
      Serial.print(client.state());
      Serial.println(" tentando novamente em 5 segundos");
      delay(5000);
    }
  }
}

void setup() {
  Serial.begin(115200);
  setup_wifi();
  client.setServer(mqtt_server, 1883);
  client.setCallback(callback);

  pinMode(LED_PIN, OUTPUT);
  ESP32PWM::allocateTimer(0);
  latheMotor.attach(SERVO_PIN, 500, 2400);

  Wire.begin(21, 22);
  lcd.init();
  lcd.backlight();
  dht.setup(DHT_PIN, DHTesp::DHT22);
  mpu.begin();

  // Tela de Inicialização
  lcd.clear();
  lcd.setCursor(0, 1); lcd.print("  NEXUS SYSTEM OK   ");
  lcd.setCursor(0, 2); lcd.print(" AGUARDANDO COMANDO ");
  delay(2000);
  lcd.clear();
}

void loop() {
  if (!client.connected()) reconnect();
  client.loop();

  // Leitura dos sensores
  TempAndHumidity data = dht.getTempAndHumidity();
  float currentSim = (analogRead(POT_PIN) / 4095.0) * 15.0;
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);

  // Lógica de Tela e Controle do Motor
  if (!systemActive) {
    lcd.setCursor(0, 0); lcd.print("      WARNING!      ");
    lcd.setCursor(0, 1); lcd.print("   SYSTEM HALTED    ");
    lcd.setCursor(0, 2); lcd.print("  REMOTE STOP RECV  ");
    lcd.setCursor(0, 3); lcd.print("STATUS: STOPPED     ");
  } else {
    digitalWrite(LED_PIN, HIGH);

    // Movimento do Servo (vai e volta sem travar o processador)
    servoPos += servoStep;
    if(servoPos >= 180 || servoPos <= 0) servoStep = -servoStep;
    latheMotor.write(servoPos);
    delay(15);

    // Atualização do LCD (com espaços no final para limpar caracteres antigos)
    lcd.setCursor(0, 0); lcd.print("T:"); lcd.print(data.temperature, 1); lcd.print("C  ");
    lcd.setCursor(10, 0); lcd.print("H:"); lcd.print(data.humidity, 1); lcd.print("%  ");
    lcd.setCursor(0, 1); lcd.print("Current: "); lcd.print(currentSim, 1); lcd.print("A   ");
    lcd.setCursor(0, 2); lcd.print("Vib X: "); lcd.print(a.acceleration.x, 2); lcd.print("    ");
    lcd.setCursor(0, 3); lcd.print("STATUS: OPERATIONAL ");
  }

  // Envio de Telemetria (Acontece independente do motor estar rodando)
    long now = millis();
    if (now - lastMsg > 2000) {
      lastMsg = now;

      float t = isnan(data.temperature) ? 0.0 : data.temperature;
      float h = isnan(data.humidity) ? 0.0 : data.humidity;

      // CRIAMOS UMA STRING DE STATUS PARA O JAVA ENTENDER
      String motorStatus = "STOPPED";
          if (maintenanceMode) {
            motorStatus = "MAINTENANCE";
          } else if (systemActive) {
            motorStatus = "OPERATING";
          }

      // ADICIONAMOS "state" NO JSON
      String payload = "{\"id\":1,\"temp\":" + String(t, 2) +
                       ",\"humi\":" + String(h, 2) +
                       ",\"curr\":" + String(currentSim, 2) +
                       ",\"vib\":" + String(a.acceleration.x, 2) +
                       ",\"state\":\"" + motorStatus + "\"}"; // <--- ADICIONADO AQUI

      client.publish("nexus/motor/1/telemetry", payload.c_str());
    }
}