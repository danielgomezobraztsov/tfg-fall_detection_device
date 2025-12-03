#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BNO055.h>
#include <utility/imumaths.h>
  
Adafruit_BNO055 bno = Adafruit_BNO055(55);

void setup(void) 
{
  Serial.begin(9600);
  Serial.println("Orientation Sensor Test"); Serial.println("");
  
  if(!bno.begin())
  {
    Serial.print("Ooops, no BNO055 detected ... Check your wiring or I2C ADDR!");
    while(1);
  }
  
  delay(1000);
    
  bno.setExtCrystalUse(true);
}

void loop() {
  imu::Quaternion q = bno.getQuat();

  Serial.print("qW:");
  Serial.print(q.w(), 4);
  Serial.print(" qX:");
  Serial.print(q.x(), 4);
  Serial.print(" qY:");
  Serial.print(q.y(), 4);
  Serial.print(" qZ:");
  Serial.println(q.z(), 4);

  delay(50);
}

/*
usa la funcion getQuat() y imprime w,z,x,y que crean la orientacion quaternion.
esto es una manera de representar la orintacion en 3D y creo que no usa los angulo de Euler (que al parecer tiene sus problemas)
interesante para representar graficos 3D, pero sobre todo para calcular movimientos mas complejos que puede ser util para la deteccion de caidas.
*/
