package com.example.linebot_bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.StreamSupport;

@Service
public class WeatherService {

    private static final Logger logger = LoggerFactory.getLogger(WeatherService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${cwa.apiKey}")
    private String apiKey;

    /** 縣市對應 datasetId */
    private static final Map<String, String> CITY_CODE_MAP = Map.ofEntries(
            Map.entry("宜蘭縣", "F-D0047-001"), Map.entry("桃園市", "F-D0047-005"),
            Map.entry("新竹縣", "F-D0047-009"), Map.entry("苗栗縣", "F-D0047-013"),
            Map.entry("彰化縣", "F-D0047-017"), Map.entry("南投縣", "F-D0047-021"),
            Map.entry("雲林縣", "F-D0047-025"), Map.entry("嘉義縣", "F-D0047-029"),
            Map.entry("屏東縣", "F-D0047-033"), Map.entry("臺東縣", "F-D0047-037"),
            Map.entry("花蓮縣", "F-D0047-041"), Map.entry("澎湖縣", "F-D0047-045"),
            Map.entry("基隆市", "F-D0047-049"), Map.entry("新竹市", "F-D0047-053"),
            Map.entry("嘉義市", "F-D0047-057"), Map.entry("臺北市", "F-D0047-061"),
            Map.entry("高雄市", "F-D0047-065"), Map.entry("新北市", "F-D0047-069"),
            Map.entry("臺中市", "F-D0047-073"), Map.entry("臺南市", "F-D0047-077"),
            Map.entry("連江縣", "F-D0047-081"), Map.entry("金門縣", "F-D0047-085")
    );

    /**
     * 取得天氣文字資訊
     */
    public String getWeather(String city, String town) {
        try {
            String correctedCity = city.replace("台", "臺");
            String correctedTown = town.replace("台", "臺");

            logger.info("查詢天氣：城市={}, 鄉鎮={}", correctedCity, correctedTown);

            String datasetId = CITY_CODE_MAP.get(correctedCity);
            if (datasetId == null) {
                return "⚠️ 找不到縣市「" + correctedCity + "」的預報資料。";
            }

            String url = String.format(
                    "https://opendata.cwa.gov.tw/api/v1/rest/datastore/%s?Authorization=%s&elementName=溫度,3小時降雨機率,天氣現象",
                    datasetId, apiKey);

            logger.info("API 請求 URL: {}", url.replace(apiKey, "***"));

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            // 檢查 API 回應是否成功
            JsonNode successNode = root.path("success");
            if (!successNode.isMissingNode() && !successNode.asBoolean()) {
                logger.error("API 回應失敗: {}", root.path("message").asText());
                return "❌ 天氣 API 回應失敗：" + root.path("message").asText();
            }

            JsonNode recordsNode = root.path("records");
            JsonNode locationsNode = recordsNode.path("Locations");

            if (locationsNode.isMissingNode() || !locationsNode.isArray() || locationsNode.isEmpty()) {
                logger.error("找不到 Locations 節點");
                return generateNotFoundMessage(correctedCity, correctedTown);
            }

            JsonNode locationArray = locationsNode.get(0).path("Location");

            // 尋找目標鄉鎮
            JsonNode targetLocation = null;
            for (JsonNode loc : locationArray) {
                if (loc.path("LocationName").asText().equals(correctedTown)) {
                    targetLocation = loc;
                    break;
                }
            }

            if (targetLocation == null) {
                return generateNotFoundMessage(correctedCity, correctedTown);
            }

            String locationName = targetLocation.path("LocationName").asText();
            JsonNode weatherElements = targetLocation.path("WeatherElement");

            String weather = findElementValue(weatherElements, "天氣現象");
            String temperature = findElementValue(weatherElements, "溫度");
            String rainChance = findElementValue(weatherElements, "3小時降雨機率");

            logger.info("解析結果 - 天氣:{}, 溫度:{}, 降雨率:{}", weather, temperature, rainChance);

            StringBuilder result = new StringBuilder();
            result.append("📍 ").append(correctedCity).append(" ").append(locationName).append("\n\n");
            result.append("🌦️ 天氣：").append(weather).append("\n");
            result.append("🌡️ 溫度：").append(temperature).append(" °C\n");
            if (!"-".equals(rainChance) && !rainChance.isEmpty()) {
                result.append("💧 降雨機率：").append(rainChance).append(" %");
            }

            return result.toString();

        } catch (HttpClientErrorException e) {
            logger.error("HTTP 錯誤：{}", e.getMessage());
            return "❌ 天氣服務暫時無法連線，請稍後再試。";
        } catch (Exception e) {
            logger.error("天氣查詢異常", e);
            return "❌ 天氣查詢失敗：" + e.getMessage();
        }
    }

    /**
     * 產生 Flex Message JSON（bubble）
     */
    public String buildWeatherFlexMessage(String city, String town) {
        String result = getWeather(city, town);

        // 如果是錯誤訊息 → 回傳文字卡片
        if (result.startsWith("⚠️") || result.startsWith("❌")) {
            return """
            {
              "type": "bubble",
              "body": {
                "type": "box",
                "layout": "vertical",
                "contents": [
                  { "type": "text", "text": "%s", "wrap": true }
                ]
              }
            }
            """.formatted(result);
        }

        // 分析文字輸出
        String[] lines = result.split("\n");
        String location = lines[0].replace("📍 ", "").trim();
        String weather = lines[2].replace("🌦️ 天氣：", "").trim();
        String temperature = lines[3].replace("🌡️ 溫度：", "").trim();
        String rainChance = (lines.length > 4) ? lines[4].replace("💧 降雨機率：", "").trim() : "-";

        // 選擇對應圖片
        String imageUrl;
        if (weather.contains("雨")) {
            imageUrl = "https://img.icons8.com/emoji/96/cloud-with-rain-emoji.png"; // 下雨
        } else if (weather.contains("晴")) {
            imageUrl = "https://img.icons8.com/emoji/96/sun-emoji.png"; // 晴天
        } else if (weather.contains("陰")) {
            imageUrl = "https://img.icons8.com/emoji/96/cloud-emoji.png"; // 陰天
        } else if (weather.contains("雲")) {
            imageUrl = "https://raw.githubusercontent.com/visualcrossing/WeatherIcons/main/PNG/2nd_Set_Color/Partly%20Cloudy%20Day.png"; // 多雲
        } else {
            imageUrl = "https://img.icons8.com/emoji/96/rainbow-emoji.png"; // 預設
        }

        // 組成 bubble JSON
        return """
        {
          "type": "bubble",
          "hero": {
            "type": "image",
            "url": "%s",
            "size": "full",
            "aspectRatio": "20:13",
            "aspectMode": "cover"
          },
          "body": {
            "type": "box",
            "layout": "vertical",
            "contents": [
              { "type": "text", "text": "%s", "weight": "bold", "size": "xl" },
              { "type": "text", "text": "天氣：%s", "size": "md", "margin": "sm" },
              { "type": "text", "text": "溫度：%s", "size": "md", "margin": "sm" },
              { "type": "text", "text": "降雨機率：%s", "size": "md", "margin": "sm" }
            ]
          }
        }
        """.formatted(imageUrl, location, weather, temperature, rainChance);
    }

    /** 解析元素值 */
    private String findElementValue(JsonNode elements, String elementName) {
        return StreamSupport.stream(elements.spliterator(), false)
                .filter(e -> elementName.equals(e.path("ElementName").asText()))
                .findFirst()
                .map(e -> {
                    JsonNode timeArray = e.path("Time");
                    if (timeArray.isMissingNode() || !timeArray.isArray() || timeArray.isEmpty()) return "-";

                    JsonNode firstTime = timeArray.get(0);
                    JsonNode elementValueArray = firstTime.path("ElementValue");
                    if (elementValueArray.isMissingNode() || !elementValueArray.isArray() || elementValueArray.isEmpty()) return "-";

                    JsonNode elementValueNode = elementValueArray.get(0);
                    switch (elementName) {
                        case "天氣現象": return elementValueNode.path("Weather").asText("-");
                        case "溫度": return elementValueNode.path("Temperature").asText("-");
                        case "3小時降雨機率": return elementValueNode.path("ProbabilityOfPrecipitation").asText("-");
                        default: return "-";
                    }
                })
                .orElse("-");
    }

    private String generateNotFoundMessage(String city, String town) {
        return "⚠️ 在「" + city + "」找不到鄉鎮「" + town + "」的預報資料。\n💡 請檢查鄉鎮名稱是否正確。";
    }
}
