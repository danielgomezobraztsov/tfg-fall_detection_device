#include <WiFi.h>
#include <WebServer.h>
#include <WebSocketsServer_Generic.h>

#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BNO055.h>
#include <utility/imumaths.h>

const char* ssid     = "MOVISTAR_5148";
const char* password = "BTn7GpY2Cfj4wzs9LmqK";

WebServer server(80);
WebSocketsServer webSocket = WebSocketsServer(81);

Adafruit_BNO055 bno = Adafruit_BNO055(55);

float ENERGY_THRESHOLD   = 250;
float GYRO_THRESHOLD     = 150;
float INACTIVE_THRESHOLD = 0.5;

unsigned long inactivityTime = 3000;

#define ENERGY_WINDOW 10
float energyBuffer[ENERGY_WINDOW] = {0};
int energyIndex = 0;

bool possibleFall = false;
unsigned long fallTime = 0;

void sendMessage(const String& type, const String& message) {
  String json = "{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}";
  webSocket.broadcastTXT(json);
  Serial.println(json);
}

void setup() {
  Serial.begin(115200);

  if (!bno.begin()) {
    Serial.println("BNO055 not detected");
    while (1);
  }

  delay(1000);
  bno.setExtCrystalUse(true);

  WiFi.begin(ssid, password);
  Serial.print("Connecting to WiFi");

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();
  Serial.println("WiFi connected");
  Serial.print("ESP32 IP address: ");
  Serial.println(WiFi.localIP());

  server.on("/", HTTP_GET, []() {
    server.send(200, "text/plain", "ESP32 Fall Detector is running");
  });

  server.begin();
  webSocket.begin();

  sendMessage("status", "ESP32 started");
  sendMessage("status", "IP: " + WiFi.localIP().toString());
  sendMessage("status", "Boot complete");
}

void loop() {
  server.handleClient();
  webSocket.loop();

  imu::Vector<3> linear = bno.getVector(Adafruit_BNO055::VECTOR_LINEARACCEL);
  imu::Vector<3> gyro   = bno.getVector(Adafruit_BNO055::VECTOR_GYROSCOPE);
  imu::Vector<3> euler  = bno.getVector(Adafruit_BNO055::VECTOR_EULER);

  float accMag = sqrt(linear.x() * linear.x() + linear.y() * linear.y() + linear.z() * linear.z());

  float gyroMag = sqrt(gyro.x() * gyro.x() + gyro.y() * gyro.y() + gyro.z() * gyro.z());
  
  float fallEnergy = accMag * accMag + 0.01f * gyroMag * gyroMag;

  energyBuffer[energyIndex] = fallEnergy;
  energyIndex = (energyIndex + 1) % ENERGY_WINDOW;

  float totalEnergy = 0;
  for (int i = 0; i < ENERGY_WINDOW; i++) {
    totalEnergy += energyBuffer[i];
  }

  bool lyingDown = abs(euler.y()) > 60 || abs(euler.z()) > 60;

  if (totalEnergy > ENERGY_THRESHOLD && gyroMag > GYRO_THRESHOLD) {
    if (!possibleFall) {
      sendMessage("event", "Energy spike detected");
    }
    possibleFall = true;
    fallTime = millis();
  }

  if (possibleFall) {
    if (lyingDown) {
      sendMessage("event", "Lying posture detected");
    }

    if (lyingDown && accMag < INACTIVE_THRESHOLD) {
      if (millis() - fallTime > inactivityTime) {
        sendMessage("fall", "FALL DETECTED");
        possibleFall = false;
      }
    }
  }

  String debugMsg =
    "acc=" + String(accMag, 2) +
    " gyro=" + String(gyroMag, 2) +
    " energy=" + String(totalEnergy, 1);

  sendMessage("debug", debugMsg);

  
  delay(200); // slower update rate so the app is easier to read
}
