/**
 * NEXUS Industrial Motor Control - ESP32
 * Features: MQTT Telemetry, State Sync, and Dynamic Context Switching.
 * Version: 2.5 (Context Aware)
 */
#include <WiFi.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <DHTesp.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <ESP32Servo.h>

// --- IO Configuration ---
#define DHT_PIN    15
#define POT_PIN    34
#define LED_PIN    12
#define SERVO_PIN  13
#define I2C_ADDR   0x27

// --- Network Settings ---
const char* ssid = "Wokwi-GUEST";
const char* password = "";
const char* mqtt_server = "broker.hivemq.com";

WiFiClient espClient;
PubSubClient client(espClient);
LiquidCrystal_I2C lcd(I2C_ADDR, 20, 4);
DHTesp dht;
Adafruit_MPU6050 mpu;
Servo latheMotor;

// --- Global State Variables ---
int deviceId = 1;             // DYNAMIC ID: Changed via SWITCH_ID command
bool systemActive = false;    // Motor running state
bool maintenanceMode = false; // Maintenance lock state
unsigned long lastTelemetry = 0;
int servoPos = 0;
int servoStep = 20;

/**
 * Handle incoming MQTT commands from the Java Server
 */
void callback(char* topic, byte* payload, unsigned int length) {
  String message = "";
  for (int i = 0; i < length; i++) message += (char)payload[i];

  Serial.println("[MQTT] Command Received: " + message);

  // 1. CONTEXT SWITCH LOGIC (SWITCH_ID:X)
  if (message.startsWith("SWITCH_ID:")) {
    if (systemActive) {
      Serial.println("[SAFETY] Rejecting ID switch: Motor is currently OPERATING!");
      return;
    }
    int newId = message.substring(10).toInt();
    if (newId > 0) {
      deviceId = newId;
      Serial.print("[NEXUS] Identity Switched! Activating ID: ");
      Serial.println(deviceId);

      // Force resubscribe to the new specific control topic
      String specificTopic = "nexus/motor/" + String(deviceId) + "/control";
      client.subscribe(specificTopic.c_str());
    }
  }
  // 2. OPERATIONAL LOGIC
  else if (message == "STOP") {
    systemActive = false;
    maintenanceMode = false;
    digitalWrite(LED_PIN, LOW);
  }
  else if (message == "START") {
    if (!maintenanceMode) systemActive = true;
  }
  else if (message == "MAINTENANCE_IN") {
    systemActive = false;
    maintenanceMode = true;
  }
  else if (message == "MAINTENANCE_OUT") {
    maintenanceMode = false;
  }

  lcd.clear();
}

void reconnect() {
  while (!client.connected()) {
    Serial.print("Attempting MQTT connection...");
    String clientId = "NexusClient_Dynamic_" + String(random(0xffff), HEX);
    if (client.connect(clientId.c_str())) {
      Serial.println("connected");

      // Global control topic (for SWITCH_ID)
      client.subscribe("nexus/motor/control");

      // Specific control topic (based on current ID)
      String specificTopic = "nexus/motor/" + String(deviceId) + "/control";
      client.subscribe(specificTopic.c_str());
    } else {
      delay(5000);
    }
  }
}

void setup() {
  Serial.begin(115200);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }

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

  lcd.setCursor(0, 0); lcd.print(" NEXUS SYSTEM v2.5 ");
  lcd.setCursor(0, 2); lcd.print(" STATUS: ONLINE ");
  delay(2000);
  lcd.clear();
}

void loop() {
  if (!client.connected()) reconnect();
  client.loop();

  TempAndHumidity data = dht.getTempAndHumidity();
  float currentSim = (analogRead(POT_PIN) / 4095.0) * 15.0;
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);

  if (systemActive && !maintenanceMode) {
    digitalWrite(LED_PIN, HIGH);
    static unsigned long lastMove = 0;
    if(millis() - lastMove > 15) {
      lastMove = millis();
      servoPos += servoStep;
      if(servoPos >= 180 || servoPos <= 0) servoStep = -servoStep;
      latheMotor.write(servoPos);
    }

    // Operating UI - Display dynamic ID
    lcd.setCursor(0, 0); lcd.print("ID:"); lcd.print(deviceId);
    lcd.setCursor(6, 0); lcd.print("T:"); lcd.print(data.temperature, 1); lcd.print("C");
    lcd.setCursor(0, 1); lcd.print("Curr:"); lcd.print(currentSim, 1); lcd.print("A");
    lcd.setCursor(0, 3); lcd.print("STATE: OPERATIONAL ");
  }
  else {
    digitalWrite(LED_PIN, LOW);
    latheMotor.write(90);
    lcd.setCursor(0, 0); lcd.print("ID: "); lcd.print(deviceId);
    lcd.setCursor(0, 1); lcd.print("   SYSTEM HALTED   ");
    lcd.setCursor(0, 3);
    lcd.print(maintenanceMode ? "STATE: MAINTENANCE " : "STATE: STOPPED     ");
  }

  // --- Telemetry Sync (Every 2000ms) ---
  if (millis() - lastTelemetry > 2000) {
    lastTelemetry = millis();

    String stateStr = "STOPPED";
    if (maintenanceMode) stateStr = "MAINTENANCE";
    else if (systemActive) stateStr = "OPERATING";

    // Build JSON using dynamic deviceId
    String payload = "{\"id\":" + String(deviceId) + ",\"temp\":" + String(data.temperature, 2) +
                     ",\"humi\":" + String(data.humidity, 2) +
                     ",\"curr\":" + String(currentSim, 2) +
                     ",\"vib\":" + String(a.acceleration.x, 2) +
                     ",\"state\":\"" + stateStr + "\"}";

    // Publish to a dynamic topic based on ID
    String topic = "nexus/motor/" + String(deviceId) + "/telemetry";
    client.publish(topic.c_str(), payload.c_str());
  }
}