#include <WiFi.h>
#include <WebServer.h>
#include <WebSocketsServer_Generic.h>
#include <ESPmDNS.h>
#include <Preferences.h>

#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BNO055.h>
#include <utility/imumaths.h>


enum DeviceMode {
  MODE_SETUP,
  MODE_NORMAL
};

DeviceMode deviceMode = MODE_SETUP;

const char* SETUP_AP_SSID = "FallDetector-Setup";
const char* SETUP_AP_PASSWORD = "setup12345";

const char* DEVICE_HOSTNAME = "fall-detector";
const char* DEVICE_SERVICE_NAME = "FallDetector";
const char* MDNS_WEBSOCKET_SERVICE = "fallws";
const uint16_t WEBSOCKET_PORT = 81;

const int MAX_WIFI_CREDENTIALS = 5;
const unsigned long WIFI_CONNECT_TIMEOUT_MS = 12000;

struct WifiCredential {
  String ssid;
  String password;
};

bool mdnsStarted = false;

Preferences preferences;

WebServer setupServer(80);
WebSocketsServer webSocket = WebSocketsServer(WEBSOCKET_PORT);

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

const float POSTURE_LYING_ENTER_DEG = 65.0f;
const float POSTURE_LYING_EXIT_DEG  = 45.0f;
const unsigned long FALL_CANDIDATE_TIMEOUT_MS = 10000;
const unsigned long LYING_MESSAGE_PERIOD_MS   = 1000;
const float STILL_GYRO_THRESHOLD = 20.0f;

float standGx = 0.0f;
float standGy = 0.0f;
float standGz = 9.81f;
bool standingBaselineSet = false;

bool lyingPostureState = false;
unsigned long lyingStart = 0;
unsigned long inactiveStart = 0;
unsigned long lastLyingMsgAt = 0;

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

//para que se manteng encendido por el step up booster que es caca
const int BOOST_KEY_PIN = A7;
unsigned long lastKeepAlive = 0;

void pulseBoostKey() {
  digitalWrite(BOOST_KEY_PIN, HIGH);
  delay(150);
  digitalWrite(BOOST_KEY_PIN, LOW);
}

void sendMessage(const String& type, const String& message) {
  String json = "{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}";
  webSocket.broadcastTXT(json);
  Serial.println(json);
}

String websocketMdnsUrl() {
  return "ws://" + String(DEVICE_HOSTNAME) + ".local:" + String(WEBSOCKET_PORT) + "/";
}

void stopMdnsService() {
  if (mdnsStarted) {
    MDNS.end();
    mdnsStarted = false;
    Serial.println("mDNS responder stopped");
  }
}

bool startMdnsService() {
  stopMdnsService();

  if (!MDNS.begin(DEVICE_HOSTNAME)) {
    Serial.println("Error setting up mDNS responder");
    return false;
  }

  mdnsStarted = true;

  MDNS.addService(MDNS_WEBSOCKET_SERVICE, "tcp", WEBSOCKET_PORT);
  MDNS.addServiceTxt(MDNS_WEBSOCKET_SERVICE, "tcp", "device", DEVICE_SERVICE_NAME);
  MDNS.addServiceTxt(MDNS_WEBSOCKET_SERVICE, "tcp", "path", "/");
  MDNS.addServiceTxt(MDNS_WEBSOCKET_SERVICE, "tcp", "protocol", "websocket");

  Serial.println("mDNS responder started");
  Serial.print("mDNS hostname: ");
  Serial.print(DEVICE_HOSTNAME);
  Serial.println(".local");
  Serial.print("mDNS service: _");
  Serial.print(MDNS_WEBSOCKET_SERVICE);
  Serial.println("._tcp.local");
  Serial.print("WebSocket URL: ");
  Serial.println(websocketMdnsUrl());

  return true;
}

bool cooldownActive() {
  if (lastEventSentAt == 0) {
    return false;
  }

  return (millis() - lastEventSentAt) < EVENT_COOLDOWN_MS;
}

float angleBetweenDeg(float ax, float ay, float az, float bx, float by, float bz) {
  float amag = sqrt(ax * ax + ay * ay + az * az);
  float bmag = sqrt(bx * bx + by * by + bz * bz);

  if (amag < 0.01f || bmag < 0.01f) {
    return 0.0f;
  }

  float dot = (ax * bx + ay * by + az * bz) / (amag * bmag);
  dot = constrain(dot, -1.0f, 1.0f);

  return acos(dot) * 180.0f / PI;
}

void calibrateStandingPosture() {
  const int samples = 60;
  float sx = 0.0f;
  float sy = 0.0f;
  float sz = 0.0f;

  delay(500);

  for (int i = 0; i < samples; i++) {
    imu::Vector<3> gravity = bno.getVector(Adafruit_BNO055::VECTOR_GRAVITY);
    sx += gravity.x();
    sy += gravity.y();
    sz += gravity.z();
    delay(20);
  }

  standGx = sx / samples;
  standGy = sy / samples;
  standGz = sz / samples;
  standingBaselineSet = true;

  Serial.print("Standing gravity baseline: ");
  Serial.print(standGx, 3);
  Serial.print(", ");
  Serial.print(standGy, 3);
  Serial.print(", ");
  Serial.println(standGz, 3);
}

bool updateLyingPostureState(float postureAngleDeg) {
  if (!standingBaselineSet) {
    return false;
  }

  if (!lyingPostureState && postureAngleDeg >= POSTURE_LYING_ENTER_DEG) {
    lyingPostureState = true;
  } else if (lyingPostureState && postureAngleDeg <= POSTURE_LYING_EXIT_DEG) {
    lyingPostureState = false;
  }

  return lyingPostureState;
}

void resetFallCandidate() {
  possibleFall = false;
  fallTime = 0;
  lyingStart = 0;
  inactiveStart = 0;
  lyingPostureState = false;
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

String jsonEscape(const String& input) {
  String output = "";

  for (unsigned int i = 0; i < input.length(); i++) {
    char c = input.charAt(i);

    if (c == '\"') {
      output += "\\\"";
    } else if (c == '\\') {
      output += "\\\\";
    } else if (c == '\n') {
      output += "\\n";
    } else if (c == '\r') {
      output += "\\r";
    } else if (c == '\t') {
      output += "\\t";
    } else {
      output += c;
    }
  }

  return output;
}

int loadWifiCredentials(WifiCredential creds[], int maxCreds) {
  preferences.begin("wifi", true);

  int savedCount = preferences.getInt("count", 0);

  if (savedCount < 0) {
    savedCount = 0;
  }

  if (savedCount > maxCreds) {
    savedCount = maxCreds;
  }

  int outCount = 0;

  for (int i = 0; i < savedCount && outCount < maxCreds; i++) {
    String ssidKey = "s" + String(i);
    String passKey = "p" + String(i);

    String ssid = preferences.getString(ssidKey.c_str(), "");
    String password = preferences.getString(passKey.c_str(), "");

    ssid.trim();

    if (ssid.length() > 0) {
      creds[outCount].ssid = ssid;
      creds[outCount].password = password;
      outCount++;
    }
  }

  preferences.end();

  return outCount;
}

int getSavedWifiCredentialCount() {
  WifiCredential creds[MAX_WIFI_CREDENTIALS];
  return loadWifiCredentials(creds, MAX_WIFI_CREDENTIALS);
}

String savedWifiSsidsJson() {
  WifiCredential creds[MAX_WIFI_CREDENTIALS];
  int count = loadWifiCredentials(creds, MAX_WIFI_CREDENTIALS);

  String json = "[";

  for (int i = 0; i < count; i++) {
    if (i > 0) {
      json += ",";
    }

    json += "\"";
    json += jsonEscape(creds[i].ssid);
    json += "\"";
  }

  json += "]";

  return json;
}

void saveWifiCredentials(WifiCredential creds[], int count) {
  if (count < 0) {
    count = 0;
  }

  if (count > MAX_WIFI_CREDENTIALS) {
    count = MAX_WIFI_CREDENTIALS;
  }

  preferences.begin("wifi", false);
  preferences.clear();

  int savedCount = 0;

  for (int i = 0; i < count && savedCount < MAX_WIFI_CREDENTIALS; i++) {
    String ssid = creds[i].ssid;
    ssid.trim();

    if (ssid.length() == 0) {
      continue;
    }

    String ssidKey = "s" + String(savedCount);
    String passKey = "p" + String(savedCount);

    preferences.putString(ssidKey.c_str(), ssid);
    preferences.putString(passKey.c_str(), creds[i].password);

    savedCount++;
  }

  preferences.putInt("count", savedCount);
  preferences.end();

  Serial.print("Saved WiFi credential count: ");
  Serial.println(savedCount);
}

void saveWifiCredentials(const String& ssid, const String& password) {
  WifiCredential creds[1];
  creds[0].ssid = ssid;
  creds[0].password = password;

  saveWifiCredentials(creds, 1);
}

void clearWifiCredentials() {
  preferences.begin("wifi", false);
  preferences.clear();
  preferences.end();
}

int parseProvisionedCredentials(WifiCredential creds[], int maxCreds) {
  int outCount = 0;

  int requestedCount = setupServer.arg("count").toInt();

  if (requestedCount > 0) {
    for (int i = 0; i < requestedCount && outCount < maxCreds; i++) {
      String ssidArg = "ssid" + String(i);
      String passwordArg = "password" + String(i);

      String ssid = setupServer.arg(ssidArg);
      String password = setupServer.arg(passwordArg);

      ssid.trim();

      if (ssid.length() == 0) {
        continue;
      }

      creds[outCount].ssid = ssid;
      creds[outCount].password = password;
      outCount++;
    }

    return outCount;
  }

  String singleSsid = setupServer.arg("ssid");
  String singlePassword = setupServer.arg("password");

  singleSsid.trim();

  if (singleSsid.length() > 0 && outCount < maxCreds) {
    creds[outCount].ssid = singleSsid;
    creds[outCount].password = singlePassword;
    outCount++;
  }

  return outCount;
}

void startSetupMode() {
  deviceMode = MODE_SETUP;

  WiFi.mode(WIFI_AP);
  WiFi.softAP(SETUP_AP_SSID, SETUP_AP_PASSWORD);

  Serial.println();
  Serial.println("SETUP MODE");
  Serial.print("AP SSID: ");
  Serial.println(SETUP_AP_SSID);
  Serial.print("AP IP: ");
  Serial.println(WiFi.softAPIP());

  setupServer.on("/status", HTTP_GET, []() {
    String json = "{";
    json += "\"type\":\"status\",";
    json += "\"mode\":\"setup\",";
    json += "\"deviceName\":\"FallDetector\",";
    json += "\"mdnsHost\":\"" + String(DEVICE_HOSTNAME) + ".local\",";
    json += "\"mdnsService\":\"_" + String(MDNS_WEBSOCKET_SERVICE) + "._tcp.local\",";
    json += "\"apIp\":\"" + WiFi.softAPIP().toString() + "\",";
    json += "\"maxNetworks\":" + String(MAX_WIFI_CREDENTIALS) + ",";
    json += "\"savedNetworkCount\":" + String(getSavedWifiCredentialCount()) + ",";
    json += "\"savedSsids\":" + savedWifiSsidsJson();
    json += "}";

    setupServer.send(200, "application/json", json);
  });

  setupServer.on("/provision", HTTP_POST, []() {
    WifiCredential creds[MAX_WIFI_CREDENTIALS];

    int count = parseProvisionedCredentials(creds, MAX_WIFI_CREDENTIALS);

    if (count == 0) {
      setupServer.send(
        400,
        "application/json",
        "{\"status\":\"error\",\"message\":\"Missing WiFi credentials\"}"
      );
      return;
    }

    saveWifiCredentials(creds, count);

    String json = "{";
    json += "\"status\":\"ok\",";
    json += "\"message\":\"Credentials saved. Rebooting.\",";
    json += "\"savedNetworkCount\":" + String(count);
    json += "}";

    setupServer.send(200, "application/json", json);

    delay(1000);
    ESP.restart();
  });

  setupServer.on("/reset", HTTP_POST, []() {
    clearWifiCredentials();
    setupServer.send(200, "application/json", "{\"status\":\"ok\",\"message\":\"Credentials cleared\"}");
    delay(1000);
    ESP.restart();
  });

  setupServer.begin();
}

bool tryConnectToWifiCredential(const WifiCredential& cred, int index, int total) {
  Serial.print("Trying WiFi ");
  Serial.print(index + 1);
  Serial.print("/");
  Serial.print(total);
  Serial.print(": ");
  Serial.println(cred.ssid);

  WiFi.disconnect(true);
  delay(300);

  WiFi.mode(WIFI_STA);
  WiFi.begin(cred.ssid.c_str(), cred.password.c_str());

  unsigned long start = millis();

  while (WiFi.status() != WL_CONNECTED && millis() - start < WIFI_CONNECT_TIMEOUT_MS) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("Connected to WiFi: ");
    Serial.println(cred.ssid);
    Serial.print("ESP32 IP address: ");
    Serial.println(WiFi.localIP());
    return true;
  }

  Serial.print("Failed to connect to WiFi: ");
  Serial.println(cred.ssid);

  return false;
}

bool connectToSavedWifi() {
  WifiCredential creds[MAX_WIFI_CREDENTIALS];
  int count = loadWifiCredentials(creds, MAX_WIFI_CREDENTIALS);

  if (count == 0) {
    Serial.println("No saved WiFi credentials in multi-network format");
    return false;
  }

  Serial.print("Saved WiFi credential count: ");
  Serial.println(count);

  WiFi.persistent(false);

  WiFi.mode(WIFI_STA);

  WiFi.setHostname(DEVICE_HOSTNAME);

  for (int i = 0; i < count; i++) {
    if (tryConnectToWifiCredential(creds[i], i, count)) {
      return true;
    }
  }

  Serial.println("All saved WiFi credentials failed");
  return false;
}

void startNormalMode() {
  deviceMode = MODE_NORMAL;

  webSocket.begin();
  bool mdnsOk = startMdnsService();

  sendMessage("status", "ESP32 started");
  sendMessage("status", "IP: " + WiFi.localIP().toString());
  if (mdnsOk) {
    sendMessage("status", "DNS: " + websocketMdnsUrl());
    sendMessage("status", "Service: _" + String(MDNS_WEBSOCKET_SERVICE) + "._tcp.local");
  } else {
    sendMessage("status", "mDNS failed; use IP: " + WiFi.localIP().toString());
  }
  sendMessage("status", "Boot complete");
}

void runFallDetection() {
  webSocket.loop();

  imu::Vector<3> linear  = bno.getVector(Adafruit_BNO055::VECTOR_LINEARACCEL);
  imu::Vector<3> gyro    = bno.getVector(Adafruit_BNO055::VECTOR_GYROSCOPE);
  imu::Vector<3> euler   = bno.getVector(Adafruit_BNO055::VECTOR_EULER);
  imu::Vector<3> gravity = bno.getVector(Adafruit_BNO055::VECTOR_GRAVITY);

  uint32_t nowMs = millis();

  float accMag = sqrt(
    linear.x() * linear.x() +
    linear.y() * linear.y() +
    linear.z() * linear.z()
  );

  float gyroMag = sqrt(
    gyro.x() * gyro.x() +
    gyro.y() * gyro.y() +
    gyro.z() * gyro.z()
  );

  float fallEnergy = accMag * accMag + 0.01f * gyroMag * gyroMag;

  energyBuffer[energyIndex] = fallEnergy;
  energyIndex = (energyIndex + 1) % ENERGY_WINDOW;

  float totalEnergy = 0;
  for (int i = 0; i < ENERGY_WINDOW; i++) {
    totalEnergy += energyBuffer[i];
  }

  float postureAngle = angleBetweenDeg(
    gravity.x(), gravity.y(), gravity.z(),
    standGx, standGy, standGz
  );

  bool lyingDown = updateLyingPostureState(postureAngle);
  bool inactive = (accMag < INACTIVE_THRESHOLD) && (gyroMag < STILL_GYRO_THRESHOLD);
  bool impactDetected = (totalEnergy > ENERGY_THRESHOLD) && (gyroMag > GYRO_THRESHOLD);

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

  if (impactDetected && !possibleFall) {
    possibleFall = true;
    fallTime = nowMs;
    lyingStart = 0;
    inactiveStart = 0;

    sendMessage("event", "Energy spike detected");

    if (!eventTriggered && !cooldownActive()) {
      startEventCapture(nowMs);
    }
  }


  if (possibleFall) {
    if (nowMs - fallTime > FALL_CANDIDATE_TIMEOUT_MS) {
      resetFallCandidate();
      sendMessage("event", "Fall candidate expired");
    } else {
      if (lyingDown) {
        if (lyingStart == 0) {
          lyingStart = nowMs;
        }

        if (nowMs - lastLyingMsgAt > LYING_MESSAGE_PERIOD_MS) {
          sendMessage("event", "Lying posture detected");
          lastLyingMsgAt = nowMs;
        }
      } else {
        lyingStart = 0;
      }

      if (lyingDown && inactive) {
        if (inactiveStart == 0) {
          inactiveStart = nowMs;
        }

        if (nowMs - inactiveStart >= inactivityTime) {
          sendMessage("fall", "FALL DETECTED");
          resetFallCandidate();
        }
      } else {
        inactiveStart = 0;
      }
    }
  }

  if (eventCapturing) {
    if (eventCount < EVENT_SAMPLES) {
      eventBuffer[eventCount++] = s;
    }

    if (eventCount >= EVENT_SAMPLES) {
      finishEventCapture();
    }
  }
  addToRingBuffer(s);

  uint8_t calSys, calGyro, calAccel, calMag;
  bno.getCalibration(&calSys, &calGyro, &calAccel, &calMag);

  String debugMsg =
    "acc=" + String(accMag, 2) +
    " gyro=" + String(gyroMag, 2) +
    " energy=" + String(totalEnergy, 1) +
    " posture=" + String(postureAngle, 1) +
    " lying=" + String(lyingDown ? 1 : 0) +
    " inactive=" + String(inactive ? 1 : 0) +
    " cal=" + String(calSys) + "/" + String(calGyro) + "/" + String(calAccel) + "/" + String(calMag);

  sendMessage("debug", debugMsg);

  delay(SAMPLE_PERIOD_MS);
}

void setup() {
  // Tema de mantenerlo encendido
  pinMode(BOOST_KEY_PIN, OUTPUT);
  digitalWrite(BOOST_KEY_PIN, LOW);

  delay(1000);
  pulseBoostKey();
  // todo esto

  Serial.begin(115200);

  if (!bno.begin()) {
    Serial.println("BNO055 no detectado");
    while (1);
  }

  delay(1000);
  bno.setExtCrystalUse(true);

  calibrateStandingPosture();

  if (connectToSavedWifi()) {
    startNormalMode();
  } else {
    startSetupMode();
  }
}

void loop() {
  if (deviceMode == MODE_SETUP) {
    setupServer.handleClient();
  }

  if (deviceMode == MODE_NORMAL) {
    if (WiFi.status() != WL_CONNECTED) {
      Serial.println("Wifi se ha perdido, reiniciando y provando todas las wifi guardadas, si no fuanciona va a modo hotspot otra vez");
      stopMdnsService();
      delay(1000);
      ESP.restart();
    }

    runFallDetection();

    // lo de mantenerlo encendido
    if (millis() - lastKeepAlive > 15000) {
    lastKeepAlive = millis();
    pulseBoostKey();
  }
  }
}