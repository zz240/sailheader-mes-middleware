/*
 * ESP32：车间设备替身——Modbus TCP 从站 + 真实传感器
 *
 * 作用：把「设备」这一层变成真的。寄存器表与 tools/shadow-site/modbus-device.cjs（软替身）一致，
 * 所以采集桥（modbus-bridge.cjs）只要把 DEVICE_HOST 从 127.0.0.1 换成这块 ESP32 的 IP 就能直接跑，
 * 其余不用改。唯一例外：软替身写的是 380V 工业电机的曲线（220V / 8A / 45℃），
 * 用 5V 的 INA219 测出来会被桥判 BAD——量程见下面的「点位量程」。
 *
 * 接线（接哪个就把对应的 HAS_* 改成 1；默认只开 DHT11）：
 *   DHT11     VCC -> 3V3，GND -> GND，DATA -> GPIO4
 *             （三针模块板载 10k 上拉；四针裸探头在 DATA 与 3V3 之间加 10k）
 *   DS18B20   DATA -> GPIO5，VCC -> 3V3，GND -> GND，DATA 与 3V3 之间加 4.7k 上拉
 *   INA219    SDA -> GPIO8，SCL -> GPIO9，VCC -> 3V3，GND -> GND（I2C 地址默认 0x40）
 *             Vin+/Vin- 串进被测回路；只测低压直流，不要接 220V
 *
 * 引脚是按 **ESP32-S3** 选的（S3 和老的 ESP32 引脚不一样）：
 *   - GPIO26~32 在 S3 上接的是板载 SPI Flash，不能用；
 *   - S3 没有 GPIO22~25，所以 I2C 用 8/9 而不是 21/22。
 * 如果你用的是老款 ESP32（不带 S3），GPIO4/5 同样空闲，这组脚也能直接用。
 *
 * 依赖库（Arduino 库管理器装）：
 *   ModbusIP_ESP8266（emelianov）——必装，只用到 holding register（FC3）
 *   下面三个**按开关才需要**：HAS_* 为 0 时对应的 #include 根本不编译，没装库也能过
 *     HAS_DHT11=1   -> DHT sensor library（Adafruit，安装时会提示一并装 Adafruit Unified Sensor）
 *     HAS_DS18B20=1 -> OneWire + DallasTemperature
 *     HAS_INA219=1  -> Adafruit INA219（会提示一并装 Adafruit BusIO）
 *
 * 寄存器表（保持寄存器，地址从 0 起）：
 *   0..1  uptime 秒（高 16 位 / 低 16 位）
 *   2     状态字：bit0 运行、bit1 传感器故障、bit2 刚重启（上电后前 5 秒）
 *   3     温度  ×100（有符号；读不到写 -1.00 作哨兵）
 *   4     电流  ×100
 *   5     电压  ×100
 *   6     轮询计数（主站每读一次自增，用来证明读到的是新值）
 *   7     湿度  ×100（DHT11；采集桥的点位表目前只映射 3~6，这一格先留着）
 *
 * 点位量程（与桥的 POINTS 表对齐）：温度 -40~200、电流 0~100A、电压 100~400V。
 * 所以 5V 供电的 INA219 电压（约 5V）会落在量程外、被桥标成 BAD——那不是接线错。
 *
 * 注意：这份固件写的时候手边没有真机，**没有在真实板子上编译验证过**。
 * 第一次烧录先看串口日志（115200）：确认 WiFi 连上、传感器读数合理，再接采集桥。
 */

// ---------------- 传感器开关（编译期生效：1 = 编进去，0 = 连库都不用装） ----------------
#define HAS_DHT11     1     // 只有 DHT11：这个填 1，下面两个保持 0
#define HAS_DS18B20   0
#define HAS_INA219    0

#include <WiFi.h>
#include <ModbusIP_ESP8266.h>   // ESP32 也用这个库（emelianov 的同一份代码，库名沿用）
#if HAS_DHT11
#include <DHT.h>
#endif
#if HAS_DS18B20
#include <OneWire.h>
#include <DallasTemperature.h>
#endif
#if HAS_INA219
#include <Wire.h>
#include <Adafruit_INA219.h>
#endif

// ---------------- 现场改这几行 ----------------
const char* WIFI_SSID = "your-ssid";
const char* WIFI_PASS = "your-password";
const uint8_t MODBUS_UNIT_ID = 1;      // 从站地址：一台设备一个（桥的 DEVICE_UNITS 里列它）

const uint8_t DHT_PIN = 4;
const uint8_t ONEWIRE_PIN = 5;

const float TEMP_MIN = -40.0f;         // 越界判故障（与桥的 min/max 对齐）
const float TEMP_MAX = 200.0f;
const float CURRENT_MAX = 100.0f;
// ---------------------------------------------

ModbusIP mb;
#if HAS_DHT11
DHT dht(DHT_PIN, DHT11);
#endif
#if HAS_DS18B20
OneWire oneWire(ONEWIRE_PIN);
DallasTemperature ds18b20(&oneWire);
#endif
#if HAS_INA219
Adafruit_INA219 ina219;
#endif

// 寄存器地址
enum {
  REG_UPTIME_HI = 0,
  REG_UPTIME_LO = 1,
  REG_STATUS = 2,
  REG_TEMP = 3,
  REG_CURRENT = 4,
  REG_VOLTAGE = 5,
  REG_COUNTER = 6,
  REG_HUMIDITY = 7,
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
  float humidityPct = NAN;
  bool sensorBad = false;

#if HAS_DHT11
  {
    const float t = dht.readTemperature();
    const float h = dht.readHumidity();
    if (isnan(t) || isnan(h)) {
      sensorBad = true;
    } else {
      tempC = t;
      humidityPct = h;
    }
  }
#endif

#if HAS_DS18B20
  ds18b20.requestTemperatures();
  {
    const float t = ds18b20.getTempCByIndex(0);
    if (isnan(t)) sensorBad = true;
    else tempC = t;                    // 两个温度都接时以 DS18B20 为准（更准）
  }
#endif

  float shuntVoltage = 0, busVoltage = 0, currentmA = 0;
#if HAS_INA219
  if (!inaPresent) {
    sensorBad = true;
  } else {
    shuntVoltage = ina219.getShuntVoltage_mV();
    busVoltage = ina219.getBusVoltage_V();
    currentmA = ina219.getCurrent_mA();
  }
#endif
  const float currentA = currentmA / 1000.0f;
  const float voltageV = busVoltage + (shuntVoltage / 1000.0f);

  if (!isnan(tempC) && (tempC < TEMP_MIN || tempC > TEMP_MAX)) sensorBad = true;
  if (inaPresent && currentA > CURRENT_MAX) sensorBad = true;

  // 状态字：bit0 运行、bit1 传感器故障、bit2 刚重启（上电前 5 秒）
  uint16_t status = 0x0001;
  if (sensorBad) status |= 0x0002;
  if (uptime < 5) status |= 0x0004;

  mb.Hreg(REG_UPTIME_HI, (uint16_t)(uptime >> 16));
  mb.Hreg(REG_UPTIME_LO, (uint16_t)(uptime & 0xFFFF));
  mb.Hreg(REG_STATUS, status);
  mb.Hreg(REG_TEMP, (uint16_t)toInt16(isnan(tempC) ? -100.0f : tempC));
  mb.Hreg(REG_CURRENT, (uint16_t)toInt16(currentA));
  mb.Hreg(REG_VOLTAGE, (uint16_t)toInt16(voltageV));
  mb.Hreg(REG_HUMIDITY, (uint16_t)toInt16(isnan(humidityPct) ? -100.0f : humidityPct));
  // 读取计数由主站的每次请求驱动（见下），这里不动 counter
  mb.Hreg(REG_COUNTER, (uint16_t)counter);
}

void setup() {
  Serial.begin(115200);
  delay(200);
  bootMillis = millis();

#if HAS_DHT11
  dht.begin();
#endif
#if HAS_DS18B20
  ds18b20.begin();
#endif
#if HAS_INA219
  Wire.begin();
  inaPresent = ina219.begin();
#endif

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
  // 每 5 秒刷一遍寄存器（主站的轮询周期也是 5 秒，见桥的 POLL_MS）；DHT11 最快 1 秒一次，5 秒没问题
  static uint32_t last = 0;
  if (millis() - last > 5000) {
    last = millis();
    counter++;
    refreshRegisters();
    if (millis() - bootMillis < 30000) {
      Serial.printf("[esp32] counter=%u status=%u temp=%d hum=%d curr=%d volt=%d\n",
                    counter, mb.Hreg(REG_STATUS), (int16_t)mb.Hreg(REG_TEMP),
                    (int16_t)mb.Hreg(REG_HUMIDITY), (int16_t)mb.Hreg(REG_CURRENT),
                    (int16_t)mb.Hreg(REG_VOLTAGE));
    }
  }
}
