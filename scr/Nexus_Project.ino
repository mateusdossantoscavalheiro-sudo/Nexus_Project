#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <DHTesp.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <ESP32Servo.h>

// Definições de Pinos (Conforme seu JSON e testes de sucesso)
#define DHT_PIN    15
#define POT_PIN    34
#define LED_PIN    12  // O pino que "funfou" legal!
#define SERVO_PIN  13
#define I2C_ADDR   0x27

LiquidCrystal_I2C lcd(I2C_ADDR, 20, 4);
DHTesp dht;
Adafruit_MPU6050 mpu;
Servo meuServo;

void setup() {
  Serial.begin(115200);
  
  // Configuração LED
  pinMode(LED_PIN, OUTPUT);
  
  // Configuração Servo
  ESP32PWM::allocateTimer(0);
  meuServo.setPeriodHertz(50);
  meuServo.attach(SERVO_PIN, 500, 2400);

  // Inicialização LCD e Barramento I2C
  Wire.begin(21, 22);
  lcd.init();
  lcd.backlight();
  lcd.setCursor(4, 0);
  lcd.print("NEXUS SYSTEM");
  lcd.setCursor(2, 2);
  lcd.print("INICIALIZANDO...");

  // Inicialização Sensores
  dht.setup(DHT_PIN, DHTesp::DHT22);
  if (!mpu.begin()) {
    Serial.println("MPU6050 nao encontrado!");
  }

  delay(2000);
  lcd.clear();
}

void loop() {
  // --- 1. LEITURA DOS DADOS ---
  TempAndHumidity data = dht.getTempAndHumidity();
  int potValue = analogRead(POT_PIN);
  float correnteSimulada = (potValue / 4095.0) * 15.0; // Escala 0-15A
  
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);

  // --- 2. AÇÃO DO HARDWARE (LED e SERVO) ---
  digitalWrite(LED_PIN, HIGH); // Liga sinalizador
  meuServo.write(180);         // Gira motor para posição A
  
  // --- 3. EXIBIÇÃO NO LCD ---
  lcd.setCursor(0, 0);
  lcd.print("Temp: "); lcd.print(data.temperature, 1); lcd.print(" C  ");
  
  lcd.setCursor(0, 1);
  lcd.print("Corrente: "); lcd.print(correnteSimulada, 1); lcd.print(" A ");
  
  lcd.setCursor(0, 2);
  lcd.print("Vibra X: "); lcd.print(a.acceleration.x, 2);
  
  lcd.setCursor(0, 3);
  lcd.print("STATUS: ATIVO");

  delay(1000); // Mantém por 1 segundo

  // Pequeno movimento de retorno para testar dinâmica
  digitalWrite(LED_PIN, LOW);  // Pisca sinalizador
  meuServo.write(0);           // Volta motor para posição B
  delay(1000);
}