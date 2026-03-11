#include <WiFi.h>
#include <WebServer.h>
#include <WebSocketsClient_Generic.h>
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
float energyBuffer[ENERGY_WINDOW];
int energyIndex = 0;

bool possibleFall = false;
unsigned long fallTime = 0;

const char index_html[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html>
<head><title>BNO055 Fall Detection</title></head>
<h2>Output consolaa</h2>

  <script>
    var gateway = `ws://${location.hostname}:81/`;
    var websocket;

    function initWebSocket() {
      websocket = new WebSocket(gateway);

      websocket.onopen = () => console.log("WebSocket connected");

      websocket.onclose = () => {
        console.log("WebSocket disconnected");
        setTimeout(initWebSocket, 2000);
      };

      websocket.onmessage = (event) => {
        console.log(event.data);
      };
    }

    window.addEventListener('load', initWebSocket);
  </script>
</body>
</html>
)rawliteral";

void setup() {
  Serial.begin(115200);

  if (!bno.begin()) {
    Serial.println("BNO055 not detected");
    while (1);
  }

  delay(1000);
  bno.setExtCrystalUse(true);

  WiFi.begin(ssid, password);

  Serial.print("Connecting");

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();
  Serial.print("IP Address: ");
  Serial.println(WiFi.localIP());

  server.on("/", HTTP_GET, []() {
    server.send(200, "text/html", index_html);
  });

  server.begin();
  webSocket.begin();
}

void loop() {
  server.handleClient();
  webSocket.loop();

  imu::Vector<3> linear = bno.getVector(Adafruit_BNO055::VECTOR_LINEARACCEL);
  imu::Vector<3> gyro   = bno.getVector(Adafruit_BNO055::VECTOR_GYROSCOPE);
  imu::Vector<3> euler  = bno.getVector(Adafruit_BNO055::VECTOR_EULER);

  float accMag = sqrt(linear.x() * linear.x() + linear.y() * linear.y() + linear.z() * linear.z());

  float gyroMag = sqrt(gyro.x()*gyro.x() +gyro.y()*gyro.y() + gyro.z()*gyro.z());

  float fallEnergy = accMag * accMag + 0.01f * gyroMag * gyroMag;

  energyBuffer[energyIndex] = fallEnergy;
  energyIndex = (energyIndex + 1) % ENERGY_WINDOW;

  float totalEnergy = 0;
  for (int i = 0; i < ENERGY_WINDOW; i++) {
    totalEnergy += energyBuffer[i];
  }

  bool lyingDown =abs(euler.y()) > 60 || abs(euler.z()) > 60;

  if (totalEnergy > ENERGY_THRESHOLD && gyroMag > GYRO_THRESHOLD) {
    if (!possibleFall) {
      webSocket.broadcastTXT("Energy spike detected");
      Serial.println("Energy spike detected");
    }

    possibleFall = true;
    fallTime = millis();
  }

  if (possibleFall) {
    if (lyingDown) {
      webSocket.broadcastTXT("Lying posture detected");
      Serial.println("Lying posture detected");
    }

    if (lyingDown && accMag < INACTIVE_THRESHOLD) {
      if (millis() - fallTime > inactivityTime) {
        webSocket.broadcastTXT("***** FALL DETECTED *****");
        webSocket.broadcastTXT("***** FALL DETECTED *****");
        webSocket.broadcastTXT("***** FALL DETECTED *****");
        webSocket.broadcastTXT("***** FALL DETECTED *****");
        webSocket.broadcastTXT("***** FALL DETECTED *****");
        webSocket.broadcastTXT("***** FALL DETECTED *****");
        Serial.println("***** FALL DETECTED *****");

        possibleFall = false;
      }
    }
  }

  String debugMsg ="acc=" + String(accMag, 2) + " gyro=" + String(gyroMag, 2) + " energy=" + String(totalEnergy, 1);

  webSocket.broadcastTXT(debugMsg);

  delay(40);
}
