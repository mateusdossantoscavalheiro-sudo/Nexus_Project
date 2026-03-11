#include <WiFi.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <DHTesp.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <ESP32Servo.h>

// --- Configuration & Pinout ---
#define DHT_PIN    15
#define POT_PIN    34
#define LED_PIN    12
#define SERVO_PIN  13
#define I2C_ADDR   0x27

// --- Network Credentials (Wokwi Standard) ---
const char* ssid = "Wokwi-GUEST";
const char* password = "";
const char* mqtt_server = "broker.hivemq.com"; // Public broker for testing

WiFiClient espClient;
PubSubClient client(espClient);
LiquidCrystal_I2C lcd(I2C_ADDR, 20, 4);
DHTesp dht;
Adafruit_MPU6050 mpu;
Servo latheMotor;

// --- Lathe Simulation Variables ---
int motorSpeed = 0;
unsigned long lastMsg = 0;

void setup_wifi() {
  delay(10);
  Serial.println("Connecting to Nexus Network...");
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWiFi Connected. IP: " + WiFi.localIP().toString());
}

void reconnect() {
  while (!client.connected()) {
    Serial.print("Attempting MQTT connection...");
    if (client.connect("NexusClient_Motor01")) { // Device ID for scalability
      Serial.println("Connected to Broker");
    } else {
      delay(5000);
    }
  }
}

void setup() {
  Serial.begin(115200);
  setup_wifi();
  client.setServer(mqtt_server, 1883);

  pinMode(LED_PIN, OUTPUT);
  ESP32PWM::allocateTimer(0);
  latheMotor.setPeriodHertz(50);
  latheMotor.attach(SERVO_PIN, 500, 2400);

  Wire.begin(21, 22);
  lcd.init();
  lcd.backlight();
  lcd.setCursor(4, 0);
  lcd.print("NEXUS SYSTEM");
  lcd.setCursor(1, 2);
  lcd.print("V2: MQTT ENABLED");

  dht.setup(DHT_PIN, DHTesp::DHT22);
  if (!mpu.begin()) Serial.println("MPU6050 Error!");

  delay(2000);
  lcd.clear();
}

void loop() {
  if (!client.connected()) reconnect();
  client.loop();

  // --- Data Acquisition ---
  TempAndHumidity data = dht.getTempAndHumidity();
  int potValue = analogRead(POT_PIN);
  float currentSim = (potValue / 4095.0) * 15.0;

  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);

  // --- Lathe Logic & Hardware Action ---
  // LED stays ON while system is operational
  digitalWrite(LED_PIN, HIGH);

  // Smooth Lathe rotation simulation (0 to 180 oscillation)
  for(int pos = 0; pos <= 180; pos += 5) {
    latheMotor.write(pos);
    delay(15);
  }

  // --- HMI Update (LCD) ---
  lcd.setCursor(0, 0);
  lcd.print("T:"); lcd.print(data.temperature, 1); lcd.print("C ");
  lcd.print("H:"); lcd.print(data.humidity, 1); lcd.print("%");

  lcd.setCursor(0, 1);
  lcd.print("Current: "); lcd.print(currentSim, 1); lcd.print("A");

  lcd.setCursor(0, 2);
  lcd.print("Vib X: "); lcd.print(a.acceleration.x, 2);

  lcd.setCursor(0, 3);
  lcd.print("NET: CONNECTED");

  // --- Data Export (MQTT Payload) ---
  // Sends data every 2 seconds to avoid flooding
  long now = millis();
  if (now - lastMsg > 2000) {
    lastMsg = now;

    // Updated JSON to include Humidity (humi)
    String payload = "{\"id\":1,\"temp\":";
    payload += data.temperature;
    payload += ",\"humi\":";
    payload += data.humidity;
    payload += ",\"curr\":";
    payload += currentSim;
    payload += ",\"vib\":";
    payload += a.acceleration.x;
    payload += "}";

    client.publish("nexus/motor/1/telemetry", payload.c_str());
    Serial.println("Payload Sent: " + payload);
  }
}