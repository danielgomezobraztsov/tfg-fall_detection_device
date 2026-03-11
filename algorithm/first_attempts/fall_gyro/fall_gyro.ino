#include <WiFi.h>
#include <WebServer.h>
#include <WebSocketsClient_Generic.h>
#include <WebSocketsServer_Generic.h>

#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BNO055.h>
#include <utility/imumaths.h>

const char* ssid = "";
const char* password = "";
WebServer server(80);
WebSocketsServer webSocket = WebSocketsServer(81);

Adafruit_BNO055 bno = Adafruit_BNO055(55);

const float FREEFALL_THRESH = 0.5;
const float IMPACT_THRESH   = 3.5;
const float GYRO_THRESH     = 150;
const float INACTIVE_THRESH = 0.5;

const unsigned long IMPACT_TIMEOUT  = 800;
const unsigned long ROT_TIMEOUT     = 800;
const unsigned long POSTURE_TIMEOUT = 1000;
const unsigned long INACTIVE_TIME   = 1000;

enum FallState {
  NORMAL,
  FREEFALL,
  IMPACT,
  ROTATION,
  POSTURE
};

FallState fallState = NORMAL;
unsigned long stateStart = 0;

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

  // WIFI
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

  float linMag = sqrt(linear.x() * linear.x() + linear.y() * linear.y() + linear.z() * linear.z());
  float gyMag = sqrt(gyro.x() * gyro.x() + gyro.y() * gyro.y() + gyro.z() * gyro.z());

  unsigned long now = millis();

  switch (fallState) {
    case NORMAL:
      if (linMag < FREEFALL_THRESH) {
        webSocket.broadcastTXT("Freefall detected");
        Serial.println("Freefall detected");

        fallState = FREEFALL;
        stateStart = now;
      }
      break;

    case FREEFALL:
      if (linMag > IMPACT_THRESH) {
        webSocket.broadcastTXT("Impact detected:  "+ String(linMag,2));
        Serial.println("Impact detected:  "+ String(linMag,2));

        fallState = IMPACT;
        stateStart = now;
      }

      if (now - stateStart > IMPACT_TIMEOUT) {
        fallState = NORMAL;
      }

      break;

    case IMPACT:
      if (gyMag > GYRO_THRESH) {
        webSocket.broadcastTXT("High rotation");
        Serial.println("High rotation");

        fallState = ROTATION;
        stateStart = now;
      }

      if (now - stateStart > ROT_TIMEOUT) {
        fallState = NORMAL;
      }

      break;

    case ROTATION: {
      bool lying = abs(euler.y()) > 60 || abs(euler.z()) > 60;

      if (lying) {
        webSocket.broadcastTXT("Lying posture detected");
        Serial.println("Lying posture detected");

        fallState = POSTURE;
        stateStart = now;
      }

      if (now - stateStart > POSTURE_TIMEOUT) {
        fallState = NORMAL;
      }

      break;
    }

    case POSTURE:
      if (linMag < INACTIVE_THRESH && (now - stateStart > INACTIVE_TIME)) {
        webSocket.broadcastTXT("***** FALL DETECTED *****");
        webSocket.broadcastTXT("***** FALL DETECTED *****");
        webSocket.broadcastTXT("***** FALL DETECTED *****");
        webSocket.broadcastTXT("***** FALL DETECTED *****");
        webSocket.broadcastTXT("***** FALL DETECTED *****");
        webSocket.broadcastTXT("***** FALL DETECTED *****");
        Serial.println("***** FALL DETECTED *****");

        fallState = NORMAL;
      }

      break;
  }


  //String logTexto ="State:" + String(fallState) + " | LinMag:" + String(linMag,2) + " | GyroMag:" + String(gyMag,2);

  //webSocket.broadcastTXT(logTexto);

  delay(40);
}
