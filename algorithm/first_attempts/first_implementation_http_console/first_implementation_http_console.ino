#include <WebSocketsClient_Generic.h>
#include <WebSocketsServer_Generic.h>

#include <WiFi.h>
#include <WebServer.h>

#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BNO055.h>
#include <utility/imumaths.h>
#include <math.h>

const char* ssid = "";
const char* password = "";
WebServer server(80);
WebSocketsServer webSocket = WebSocketsServer(81);
Adafruit_BNO055 bno = Adafruit_BNO055(55);

const float FREEFALL_THRESH = 0.8;
const float IMPACT_THRESH = 3.5;
const float ROTATION_THRESH = 45.0;
const float INACTIVE_THRESH = 0.5;

const unsigned long FREEFALL_TIMEOUT = 1000;
const unsigned long IMPACT_WINDOW = 300;
const unsigned long INACTIVE_TIME = 3000;

enum FallState {
  NORMAL,
  FREEFALL,
  IMPACT
};

FallState fallState = NORMAL;

unsigned long stateStart = 0;
unsigned long impactTime = 0;

imu::Vector<3> preFallEuler;

const char index_html[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html>
<head><title>BNO055 Fall Detection</title></head>
<h2>Output consolaa</h2>

<script>
var gateway = `ws://${window.location.hostname}:81/`;
var websocket;

function initWebSocket() {
  websocket = new WebSocket(gateway);

  websocket.onopen = () => console.log("Connected");
  websocket.onclose = () => setTimeout(initWebSocket,2000);
  websocket.onmessage = (event) => console.log(event.data);
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

  Serial.print("Conecting");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();
  Serial.println("conectado");
  Serial.println(WiFi.localIP());

  server.on("/", HTTP_GET, []() {
    server.send(200, "text/html", index_html);
  });

  server.begin();

  webSocket.begin();
  webSocket.onEvent([](uint8_t num, WStype_t type, uint8_t * payload, size_t length){});
}

void loop() {

  server.handleClient();
  webSocket.loop();

  imu::Vector<3> linear = bno.getVector(Adafruit_BNO055::VECTOR_LINEARACCEL);
  imu::Vector<3> euler = bno.getVector(Adafruit_BNO055::VECTOR_EULER);

  float linMag = sqrt(linear.x()*linear.x() + linear.y()*linear.y() + linear.z()*linear.z());

  float eulerChange = abs(euler.x() - preFallEuler.x()) + abs(euler.y() - preFallEuler.y()) + abs(euler.z() - preFallEuler.z());

  unsigned long now = millis();

  switch (fallState) {

    case NORMAL:
      if (linMag < FREEFALL_THRESH) {
        fallState = FREEFALL;
        stateStart = now;
        preFallEuler = euler;

        webSocket.broadcastTXT("Freefall detected");
      }

    break;

    case FREEFALL:
      if (linMag > IMPACT_THRESH) {
        fallState = IMPACT;
        impactTime = now;
        webSocket.broadcastTXT("Impact detected");
      }
      if (now - stateStart > FREEFALL_TIMEOUT) {
        fallState = NORMAL;
      }
    break;

    case IMPACT:
      if (now - impactTime > IMPACT_WINDOW) {
        if (eulerChange > ROTATION_THRESH) {
          imu::Vector<3> postLinear = bno.getVector(Adafruit_BNO055::VECTOR_LINEARACCEL);
          float postMag = sqrt(postLinear.x()*postLinear.x() + postLinear.y()*postLinear.y() + postLinear.z()*postLinear.z());
          
          if (postMag < INACTIVE_THRESH) {
            webSocket.broadcastTXT("***** FALL DETECTED *****");
            webSocket.broadcastTXT("***** FALL DETECTED *****");
            webSocket.broadcastTXT("***** FALL DETECTED *****");
            webSocket.broadcastTXT("***** FALL DETECTED *****");
            webSocket.broadcastTXT("***** FALL DETECTED *****");
            webSocket.broadcastTXT("***** FALL DETECTED *****");
            Serial.println("***** FALL DETECTED *****");
          }
        }
        fallState = NORMAL;
      }

    break;
  }

  String logTexto ="State:" + String(fallState) + " | LinMag:" + String(linMag,2) + " | Rot:" + String(eulerChange,1);

  webSocket.broadcastTXT(logTexto);

  delay(50);
}