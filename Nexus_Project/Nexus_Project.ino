/**
 * NEXUS Industrial Motor Control - ESP32
 * Features: MQTT Telemetry, State Sync, and Maintenance Mode.
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

  Serial.println("HMI Command Received: " + message);

  if (message == "STOP") {
    systemActive = false;
    maintenanceMode = false; // Emergency stop resets maintenance for safety
    digitalWrite(LED_PIN, LOW);
  }
  else if (message == "START") {
    if (!maintenanceMode) { // Only start if NOT in maintenance
      systemActive = true;
    }
  }
  else if (message == "MAINTENANCE_IN") {
    systemActive = false;    // Force stop motor for maintenance
    maintenanceMode = true;
  }
  else if (message == "MAINTENANCE_OUT") {
    maintenanceMode = false; // Release maintenance lock
  }

  lcd.clear(); // Clear LCD to refresh status headers
}

void setup() {
  Serial.begin(115200);

  // WiFi Connection
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println("\nWiFi Connected");

  client.setServer(mqtt_server, 1883);
  client.setCallback(callback);

  // Hardware Init
  pinMode(LED_PIN, OUTPUT);
  ESP32PWM::allocateTimer(0);
  latheMotor.attach(SERVO_PIN, 500, 2400);
  Wire.begin(21, 22);
  lcd.init();
  lcd.backlight();
  dht.setup(DHT_PIN, DHTesp::DHT22);
  mpu.begin();

  // Startup Screen
  lcd.setCursor(0, 0); lcd.print(" NEXUS SYSTEM v1.0 ");
  lcd.setCursor(0, 2); lcd.print(" STATUS: INITIALIZING");
  delay(2000);
  lcd.clear();
}

void loop() {
  // MQTT Reconnection Logic
  if (!client.connected()) {
    if (client.connect("NexusClient_Motor01")) {
      client.subscribe("nexus/motor/1/control");
    }
  }
  client.loop();

  // Sensors Processing
  TempAndHumidity data = dht.getTempAndHumidity();
  float currentSim = (analogRead(POT_PIN) / 4095.0) * 15.0; // Simulated Amperage (0-15A)
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);

  // --- Motor & UI Execution ---
  if (systemActive && !maintenanceMode) {
    digitalWrite(LED_PIN, HIGH);

    // Smooth Non-blocking Servo Sweep
    static unsigned long lastMove = 0;
    if(millis() - lastMove > 15) {
      lastMove = millis();
      servoPos += servoStep;
      if(servoPos >= 180 || servoPos <= 0) servoStep = -servoStep;
      latheMotor.write(servoPos);
    }

    // Operating UI
    lcd.setCursor(0, 0); lcd.print("T:"); lcd.print(data.temperature, 1); lcd.print("C  H:"); lcd.print(data.humidity, 0); lcd.print("%");
    lcd.setCursor(0, 1); lcd.print("Curr:"); lcd.print(currentSim, 1); lcd.print("A  ");
    lcd.setCursor(0, 2); lcd.print("VibX:"); lcd.print(a.acceleration.x, 2);
    lcd.setCursor(0, 3); lcd.print("STATE: OPERATIONAL ");
  }
  else {
    digitalWrite(LED_PIN, LOW);
    latheMotor.write(90); // Idle Servo position

    // Halted UI
    lcd.setCursor(0, 0); lcd.print("   SYSTEM HALTED   ");
    lcd.setCursor(0, 3);
    if(maintenanceMode) lcd.print("STATE: MAINTENANCE ");
    else lcd.print("STATE: STOPPED     ");
  }

  // --- Telemetry Sync (Every 2000ms) ---
  if (millis() - lastTelemetry > 2000) {
    lastTelemetry = millis();

    String stateStr = "STOPPED";
    if (maintenanceMode) stateStr = "MAINTENANCE";
    else if (systemActive) stateStr = "OPERATING";

    // Build optimized JSON string
    String payload = "{\"id\":1,\"temp\":" + String(data.temperature, 2) +
                     ",\"humi\":" + String(data.humidity, 2) +
                     ",\"curr\":" + String(currentSim, 2) +
                     ",\"vib\":" + String(a.acceleration.x, 2) +
                     ",\"state\":\"" + stateStr + "\"}";

    client.publish("nexus/motor/1/telemetry", payload.c_str());
  }
}