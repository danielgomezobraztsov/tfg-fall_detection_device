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
  imu::Vector<3> accel = bno.getVector(Adafruit_BNO055::VECTOR_ACCELEROMETER);
  imu::Vector<3> gyro  = bno.getVector(Adafruit_BNO055::VECTOR_GYROSCOPE);

  Serial.print("Accel m/s^2  X:");
  Serial.print(accel.x(), 2);
  Serial.print(" Y:");
  Serial.print(accel.y(), 2);
  Serial.print(" Z:");
  Serial.println(accel.z(), 2);

  Serial.print("Gyro rad/s  X:");
  Serial.print(gyro.x(), 2);
  Serial.print(" Y:");
  Serial.print(gyro.y(), 2);
  Serial.print(" Z:");
  Serial.println(gyro.z(), 2);

  Serial.println();
  delay(200);
}

/*
Calculo de la aceleracion en m/s^2 usando getVector(Adafruit_BNO055::VECTOR_ACCELEROMETER) y la velocidad angular en 
rad/s usando getVector(Adafruit_BNO055::VECTOR_GYROSCOPE).
Incluye ambos la gravedad (+-9.81 m/s^2 en el eje Z) y la velocidad del movimiento (accelerometro) y rotacion (gyro) del sensor.
Actua como un sensor IMU (Inertial Measurement Unit).
*/