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

bool systemActive = true; // Control flag
unsigned long lastMsg = 0;

// Handle commands from Java
void callback(char* topic, byte* payload, unsigned int length) {
  String message = "";
  for (int i = 0; i < length; i++) message += (char)payload[i];

  if (message == "STOP") {
    systemActive = false;
    digitalWrite(LED_PIN, LOW);
    latheMotor.write(90);
  }
  else if (message == "START") {
    systemActive = true;
    lcd.clear();
  }
}

void setup_wifi() {
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) delay(500);
}

void reconnect() {
  while (!client.connected()) {
    if (client.connect("NexusClient_Motor01")) {
      client.subscribe("nexus/motor/1/control"); // Listening for Java commands
    } else {
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
  lcd.clear();
}

void loop() {
  if (!client.connected()) reconnect();
  client.loop();

  TempAndHumidity data = dht.getTempAndHumidity();
  float currentSim = (analogRead(POT_PIN) / 4095.0) * 15.0;
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);

  if (!systemActive) {
    lcd.setCursor(0, 0); lcd.print("      WARNING!      ");
    lcd.setCursor(0, 1); lcd.print("   SYSTEM HALTED    ");
    lcd.setCursor(0, 2); lcd.print("  REMOTE STOP RECV  ");
    lcd.setCursor(0, 3); lcd.print("STATUS: EMERGENCY   ");
  } else {
    digitalWrite(LED_PIN, HIGH);
    // Smooth rotation
    for(int pos = 0; pos <= 180; pos += 20) { latheMotor.write(pos); delay(5); }

    lcd.setCursor(0, 0); lcd.print("T:"); lcd.print(data.temperature, 1);
    lcd.setCursor(10, 0); lcd.print("H:"); lcd.print(data.humidity, 1);
    lcd.setCursor(0, 1); lcd.print("Current: "); lcd.print(currentSim, 1); lcd.print("A");
    lcd.setCursor(0, 2); lcd.print("Vib X: "); lcd.print(a.acceleration.x, 2);
    lcd.setCursor(0, 3); lcd.print("STATUS: OPERATIONAL ");
  }

  // Telemetry continues even if stopped (for monitoring)
  long now = millis();
  if (now - lastMsg > 2000) {
    lastMsg = now;
    String payload = "{\"id\":1,\"temp\":" + String(data.temperature) +
                     ",\"humi\":" + String(data.humidity) +
                     ",\"curr\":" + String(currentSim) +
                     ",\"vib\":" + String(a.acceleration.x) + "}";
    client.publish("nexus/motor/1/telemetry", payload.c_str());
  }
}