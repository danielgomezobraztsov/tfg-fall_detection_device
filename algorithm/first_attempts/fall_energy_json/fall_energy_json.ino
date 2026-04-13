#include <WiFi.h>
#include <WebSocketsServer_Generic.h>

#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BNO055.h>
#include <utility/imumaths.h>

const char* ssid     = "";
const char* password = "";

WebSocketsServer webSocket = WebSocketsServer(81);

Adafruit_BNO055 bno = Adafruit_BNO055(55);

float ENERGY_THRESHOLD   = 250;
float GYRO_THRESHOLD     = 150;
float INACTIVE_THRESHOLD = 0.5;

unsigned long inactivityTime = 3000;

#define ENERGY_WINDOW 20
float energyBuffer[ENERGY_WINDOW] = {0};
int energyIndex = 0;

bool possibleFall = false;
unsigned long fallTime = 0;

const int SAMPLE_PERIOD_MS = 40;
const int SAMPLE_RATE_HZ   = 1000 / SAMPLE_PERIOD_MS; // de momento 25hz ya vere si lo toco

const int PRE_TRIGGER_SAMPLES  = 38;
const int POST_TRIGGER_SAMPLES = 38;
const int EVENT_SAMPLES = PRE_TRIGGER_SAMPLES + POST_TRIGGER_SAMPLES;

const unsigned long EVENT_COOLDOWN_MS = 5000;

struct Sample {
  uint32_t t;
  float ax, ay, az;
  float gx, gy, gz;
  float pitch, roll;
  float accMag;
  float gyroMag;
  float fallEnergy;
  float totalEnergy;
};

Sample ringBuffer[PRE_TRIGGER_SAMPLES];
int ringHead = 0;
int ringCount = 0;

Sample eventBuffer[EVENT_SAMPLES];
int eventCount = 0;

bool eventTriggered = false;
bool eventCapturing = false;
uint32_t eventId = 0;
uint32_t triggerTimestamp = 0;
unsigned long lastEventSentAt = 0;

void sendMessage(const String& type, const String& message) {
  String json = "{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}";
  webSocket.broadcastTXT(json);
  Serial.println(json);
}

bool cooldownActive() {
  return (millis() - lastEventSentAt) < EVENT_COOLDOWN_MS;
}

void addToRingBuffer(const Sample& s) {
  ringBuffer[ringHead] = s;
  ringHead = (ringHead + 1) % PRE_TRIGGER_SAMPLES;
  if (ringCount < PRE_TRIGGER_SAMPLES) {
    ringCount++;
  }
}

void copyPreTriggerToEventBuffer() {
  eventCount = 0;

  int startIndex = (ringHead - ringCount + PRE_TRIGGER_SAMPLES) % PRE_TRIGGER_SAMPLES;

  for (int i = 0; i < ringCount && eventCount < EVENT_SAMPLES; i++) {
    int idx = (startIndex + i) % PRE_TRIGGER_SAMPLES;
    eventBuffer[eventCount++] = ringBuffer[idx];
  }
}

String sampleToJson(const Sample& s, uint32_t t0) {
  String json = "{";
  json += "\"dt\":" + String((int32_t)(s.t - t0));
  json += ",\"ax\":" + String(s.ax, 3);
  json += ",\"ay\":" + String(s.ay, 3);
  json += ",\"az\":" + String(s.az, 3);
  json += ",\"gx\":" + String(s.gx, 3);
  json += ",\"gy\":" + String(s.gy, 3);
  json += ",\"gz\":" + String(s.gz, 3);
  json += ",\"pitch\":" + String(s.pitch, 2);
  json += ",\"roll\":" + String(s.roll, 2);
  json += ",\"accMag\":" + String(s.accMag, 3);
  json += ",\"gyroMag\":" + String(s.gyroMag, 3);
  json += ",\"fallEnergy\":" + String(s.fallEnergy, 3);
  json += ",\"totalEnergy\":" + String(s.totalEnergy, 3);
  json += "}";
  return json;
}

void sendEventWindow() {
  if (eventCount <= 0) return;

  uint32_t t0 = eventBuffer[0].t;

  String json = "{";
  json += "\"type\":\"event_window\"";
  json += ",\"eventId\":" + String(eventId);
  json += ",\"sampleRateHz\":" + String(SAMPLE_RATE_HZ);
  json += ",\"triggerTimestamp\":" + String((int32_t)(triggerTimestamp - t0));
  json += ",\"sampleCount\":" + String(eventCount);
  json += ",\"samples\":[";

  for (int i = 0; i < eventCount; i++) {
    json += sampleToJson(eventBuffer[i], t0);
    if (i < eventCount - 1) json += ",";
  }

  json += "]}";

  webSocket.broadcastTXT(json);
  Serial.println(json);

  sendMessage("event", "event_window_sent");
}

void startEventCapture(uint32_t nowMs) {
  eventTriggered = true;
  eventCapturing = true;
  eventId++;
  triggerTimestamp = nowMs;

  copyPreTriggerToEventBuffer();
  sendMessage("event", "trigger_start");
}

void finishEventCapture() {
  sendEventWindow();

  lastEventSentAt = millis();
  eventTriggered = false;
  eventCapturing = false;
  eventCount = 0;
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

  webSocket.begin();

  sendMessage("status", "ESP32 started");
  sendMessage("status", "IP: " + WiFi.localIP().toString());
  sendMessage("status", "Boot complete");
}

void loop() {
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

  uint32_t nowMs = millis();

  Sample s;
  s.t = nowMs;
  s.ax = linear.x();
  s.ay = linear.y();
  s.az = linear.z();
  s.gx = gyro.x();
  s.gy = gyro.y();
  s.gz = gyro.z();
  s.pitch = euler.y();
  s.roll = euler.z();
  s.accMag = accMag;
  s.gyroMag = gyroMag;
  s.fallEnergy = fallEnergy;
  s.totalEnergy = totalEnergy;

  addToRingBuffer(s);

  if (totalEnergy > ENERGY_THRESHOLD && gyroMag > GYRO_THRESHOLD) {
    if (!possibleFall) {
      sendMessage("event", "Energy spike detected");
    }
    possibleFall = true;
    fallTime = millis();

    if (!eventTriggered && !cooldownActive()) {
      startEventCapture(nowMs);
    }
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

  if (eventCapturing) {
    if (nowMs >= triggerTimestamp && eventCount < EVENT_SAMPLES) {
      eventBuffer[eventCount++] = s;
    }

    if (eventCount >= EVENT_SAMPLES) {
      finishEventCapture();
    }
  }

  String debugMsg =
    "acc=" + String(accMag, 2) +
    " gyro=" + String(gyroMag, 2) +
    " energy=" + String(totalEnergy, 1);

  sendMessage("debug", debugMsg);

  delay(40);
}