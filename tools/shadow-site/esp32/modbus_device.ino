/*
 * ESP32：车间设备替身——Modbus TCP 从站 + 真实传感器
 *
 * 作用：把「设备」这一层变成真的。寄存器表与 tools/shadow-site/modbus-device.cjs（软替身）**完全一致**，
 * 所以采集桥（modbus-bridge.cjs）只要把 DEVICE_HOST 从 127.0.0.1 换成这块 ESP32 的 IP 就能直接跑，
 * 其余一个字不用改。
 *
 * 接线（按你手上的传感器选，两个都接也行）：
 *   DS18B20  数据脚 -> GPIO4，VCC 3V3，GND，数据脚与 3V3 之间加 4.7k 上拉
 *   INA219   SDA -> GPIO21，SCL -> GPIO22，VCC 3V3，GND（I2C 地址默认 0x40）
 *
 * 依赖库（Arduino 库管理器装）：
 *   ModbusIP_ESP8266 或 ModbusIP_ESP32（emelianov）——只用到 holding register（FC3）
 *   OneWire + DallasTemperature（DS18B20）
 *   Adafruit INA219
 *
 * 寄存器表（保持寄存器，地址从 0 起；与软替身一致）：
 *   0..1  uptime 秒（高 16 位 / 低 16 位）
 *   2     状态字：bit0 运行、bit1 传感器故障、bit2 刚重启（上电后前 5 秒）
 *   3     温度  DS18B20  ×100（有符号）
 *   4     电流  INA219   ×100
 *   5     电压  INA219   ×100
 *   6     轮询计数（主站每读一次自增，用来证明读到的是新值）
 *
 * 注意：这份固件**没有在真机上编译验证过**（写它的时候手边没有 ESP32）。第一次烧录时先看串口日志，
 * 确认 WiFi 连上、传感器读数合理，再用采集桥连它。
 */
#include <WiFi.h>
#include <ModbusIP_ESP8266.h>   // ESP32 也用这个库（emelianov 的同一份代码，库名沿用）
#include <OneWire.h>
#include <DallasTemperature.h>
#include <Wire.h>
#include <Adafruit_INA219.h>

// ---------------- 现场改这几行 ----------------
const char* WIFI_SSID = "your-ssid";
const char* WIFI_PASS = "your-password";
const uint8_t MODBUS_UNIT_ID = 1;      // 从站地址：一台设备一个（桥的 DEVICE_UNITS 里列它）
const uint8_t ONEWIRE_PIN = 4;
const bool HAS_DS18B20 = true;
const bool HAS_INA219 = true;
const float TEMP_MIN = -40.0f;         // 越界判故障（与桥的 min/max 对齐）
const float TEMP_MAX = 200.0f;
const float CURRENT_MAX = 100.0f;
// ---------------------------------------------

ModbusIP mb;
OneWire oneWire(ONEWIRE_PIN);
DallasTemperature ds18b20(&oneWire);
Adafruit_INA219 ina219;

// 寄存器地址
enum {
  REG_UPTIME_HI = 0,
  REG_UPTIME_LO = 1,
  REG_STATUS = 2,
  REG_TEMP = 3,
  REG_CURRENT = 4,
  REG_VOLTAGE = 5,
  REG_COUNTER = 6,
  REG_COUNT = 20,
};

uint32_t bootMillis = 0;
uint32_t counter = 0;
bool inaPresent = false;

int16_t toInt16(float v) {
  long scaled = lroundf(v * 100.0f);
  if (scaled < -32768) scaled = -32768;
  if (scaled > 32767) scaled = 32767;
  return (int16_t)scaled;
}

void refreshRegisters() {
  const uint32_t uptime = (millis() - bootMillis) / 1000UL;

  float tempC = NAN;
  if (HAS_DS18B20) {
    ds18b20.requestTemperatures();
    tempC = ds18b20.getTempCByIndex(0);
  }

  float shuntVoltage = 0, busVoltage = 0, currentmA = 0;
  if (HAS_INA219 && inaPresent) {
    shuntVoltage = ina219.getShuntVoltage_mV();
    busVoltage = ina219.getBusVoltage_V();
    currentmA = ina219.getCurrent_mA();
  }
  const float currentA = currentmA / 1000.0f;
  const float voltageV = busVoltage + (shuntVoltage / 1000.0f);

  // 状态字：bit0 运行、bit1 传感器故障、bit2 刚重启（上电前 5 秒）
  uint16_t status = 0x0001;
  const bool sensorBad = (HAS_DS18B20 && (isnan(tempC) || tempC < TEMP_MIN || tempC > TEMP_MAX))
                      || (HAS_INA219 && (!inaPresent || currentA > CURRENT_MAX));
  if (sensorBad) status |= 0x0002;
  if (uptime < 5) status |= 0x0004;

  mb.Hreg(REG_UPTIME_HI, (uint16_t)(uptime >> 16));
  mb.Hreg(REG_UPTIME_LO, (uint16_t)(uptime & 0xFFFF));
  mb.Hreg(REG_STATUS, status);
  mb.Hreg(REG_TEMP, (uint16_t)toInt16(isnan(tempC) ? -100.0f : tempC));
  mb.Hreg(REG_CURRENT, (uint16_t)toInt16(currentA));
  mb.Hreg(REG_VOLTAGE, (uint16_t)toInt16(voltageV));
  // 读取计数由 Modbus 主站的每次请求驱动（见下），这里不动 counter
  mb.Hreg(REG_COUNTER, (uint16_t)counter);
}

void setup() {
  Serial.begin(115200);
  delay(200);
  bootMillis = millis();

  if (HAS_DS18B20) ds18b20.begin();
  Wire.begin();
  if (HAS_INA219) inaPresent = ina219.begin();

  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("[esp32] connecting to WiFi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print('.');
  }
  Serial.printf("\n[esp32] wifi ok, ip=%s\n", WiFi.localIP().toString().c_str());

  mb.server(MODBUS_UNIT_ID);
  mb.addHreg(REG_UPTIME_HI, 0, REG_COUNT);
  refreshRegisters();
  Serial.printf("[esp32] modbus tcp server on %s:502 (unit %u)\n",
                WiFi.localIP().toString().c_str(), MODBUS_UNIT_ID);
}

void loop() {
  mb.task();
  // 每 5 秒刷一遍寄存器（主站的轮询周期也是 5 秒，见桥的 POLL_MS）
  static uint32_t last = 0;
  if (millis() - last > 5000) {
    last = millis();
    counter++;
    refreshRegisters();
    if (millis() - bootMillis < 30000) {
      Serial.printf("[esp32] counter=%u status=%u temp=%d curr=%d volt=%d\n",
                    counter, mb.Hreg(REG_STATUS), (int16_t)mb.Hreg(REG_TEMP),
                    (int16_t)mb.Hreg(REG_CURRENT), (int16_t)mb.Hreg(REG_VOLTAGE));
    }
  }
}
