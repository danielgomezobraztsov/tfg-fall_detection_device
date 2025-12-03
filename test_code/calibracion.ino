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

void printCalibration() {
  uint8_t sys, gyro, accel, mag;
  bno.getCalibration(&sys, &gyro, &accel, &mag);

  Serial.print("Calib -> Sys:");
  Serial.print(sys);
  Serial.print(" Gyro:");
  Serial.print(gyro);
  Serial.print(" Accel:");
  Serial.print(accel);
  Serial.print(" Mag:");
  Serial.println(mag);
}

void loop() {
  sensors_event_t event;
  bno.getEvent(&event);

  Serial.print("Heading:");
  Serial.print(event.orientation.x, 1);
  Serial.print(" Pitch:");
  Serial.print(event.orientation.y, 1);
  Serial.print(" Roll:");
  Serial.print(event.orientation.z, 1);
  Serial.print("   ");

  printCalibration();
  delay(250);
}

/*
Codigo para mostrar la orientacion (Heading, Pitch, Roll) y el estado de calibracion del sensor.
La orientacion event.orientation.x/y/z son angulos de Euler en grados (el bno055 usa los 3 sensores que tiene).
sys es un sistema de calibracion automatico del bno055 para la calibracion general del sistema. 
gyro, accel y mag son la calibracion individual de cada sensor.
Cada uno va de 0 (sin calibrar) a 3 (totalmente calibrado).
*\