package com.example.linebot_bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class WeatherService {
    private static final Logger logger = LoggerFactory.getLogger(WeatherService.class);
    private static final String WS_VER = "UltraRobust-VERIFY-DAILY";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${cwa.apiKey}")
    private String apiKey;

    @PostConstruct
    public void _probeLoaded() {
        System.out.println(">>> WeatherService LOADED: " + WS_VER);
    }

    // 城市 -> datasetId
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

    private static final Set<String> WX_NAMES  = setOf("WeatherDescription","Wx","天氣預報綜合描述","天氣現象");
    private static final Set<String> T_NAMES   = setOf("T","Temperature","溫度");
    private static final Set<String> POP_NAMES = setOf("PoP3h","PoP6h","PoP12h","ProbabilityOfPrecipitation","3小時降雨機率");

    private static Set<String> setOf(String... s){ return new HashSet<>(Arrays.asList(s)); }

    /* 舊版：單一時段 */
    public String buildWeatherFlexMessage(String city, String town){
        return buildWeatherFlexMessage(city, town, null);
    }

    /* 三參數：支援 whenToken */
    public String buildWeatherFlexMessage(String city, String town, String whenToken) {
        logger.warn(">>> ENTER WeatherService.buildWeatherFlexMessage ver={}", WS_VER);

        // 如果使用者輸入今天/明天 → 改用 daily 查詢
        if ("今天".equals(whenToken) || "明天".equals(whenToken)) {
            return buildDailyWeatherFlex(city, town, whenToken);
        }

        String cCity = city.replace("台","臺").trim();
        String cTown = town.replace("台","臺").trim();
        String datasetId = CITY_CODE_MAP.get(cCity);
        if (datasetId == null) return textBubble("⚠️ 找不到縣市「"+cCity+"」的預報資料。");

        ZoneId tz = ZoneId.of("Asia/Taipei");
        ZonedDateTime now = ZonedDateTime.now(tz);
        ZonedDateTime target = parseWhenToken(whenToken, now, tz);
        if (target == null) return textBubble("⚠️ 時間格式錯誤");

        if (target.isAfter(now.plusHours(48))) return textBubble("⚠️ 只能查詢未來 48 小時內的時段。");

        try {
            String url = UriComponentsBuilder.fromHttpUrl("https://opendata.cwa.gov.tw/api/v1/rest/datastore/"+datasetId)
                    .queryParam("Authorization", apiKey)
                    .queryParam("format", "JSON")
                    .queryParam("elementName", "WeatherDescription,Wx,T,Temperature,PoP3h,PoP6h,PoP12h,ProbabilityOfPrecipitation")
                    .toUriString();
            String json = restTemplate.getForObject(url, String.class);
            ArrayNode locations = findAllLocationsAnyShape(objectMapper.readTree(json==null?"{}":json));
            if (locations.size()==0) return textBubble("❌ 找不到資料");

            JsonNode loc = findTown(locations, cTown);
            if (loc == null) return textBubble("⚠️ 找不到鄉鎮「"+cTown+"」");

            Interval interval = bestInterval(loc, target);
            String dateFmt  = target.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String wx  = pickValueNearTimeWithFallback(loc, WX_NAMES,  target);
            String t   = pickValueNearTimeWithFallback(loc, T_NAMES,   target);
            String pop = pickValueNearTimeWithFallback(loc, POP_NAMES, target);

            String title = "📍 " + cCity + " " + getLocationName(loc);
            String body  = "🕒 " + dateFmt + " " + interval.startFmt + "~" + interval.endFmt + "\n"
                         + "🌤️ " + safe(wx) + "\n"
                         + "🌡️ 氣溫 " + safe(t) + "°C，降雨 " + safe(pop) + "%";

            return bubbleNoHero(title, body);
        } catch (Exception e){
            logger.error("天氣查詢錯誤", e);
            return textBubble("❌ 天氣查詢失敗：" + e.getMessage());
        }
    }

    /** 48 小時預報 (3 小時一筆，共 16 筆) */
    public String build48hWeatherFlex(String city, String town) {
        String datasetId = CITY_CODE_MAP.get(city);
        if (datasetId == null) return textBubble("⚠️ 找不到縣市");

        try {
            String url = UriComponentsBuilder.fromHttpUrl("https://opendata.cwa.gov.tw/api/v1/rest/datastore/" + datasetId)
                    .queryParam("Authorization", apiKey)
                    .queryParam("format", "JSON")
                    .toUriString();
            String json = restTemplate.getForObject(url, String.class);
            ArrayNode locations = findAllLocationsAnyShape(objectMapper.readTree(json==null?"{}":json));
            JsonNode loc = findTown(locations, town);
            if (loc == null) return textBubble("⚠️ 找不到鄉鎮");

            JsonNode tElement = findFirstElementByNames(loc, T_NAMES);
            ArrayNode times = getArrayCI(tElement, "time", "Time");
            if (!isArray(times)) return textBubble("❌ 沒有時間序列");

            ZoneId tz = ZoneId.of("Asia/Taipei");
            List<String> bubbles = new ArrayList<>();
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("MM/dd HH:mm");

            for (int i = 0; i < Math.min(times.size(), 16); i++) {
                JsonNode ti = times.get(i);
                ZonedDateTime start = parseZ(getTextCI(ti, "startTime", "dataTime"), tz);
                if (start == null) continue;

                String wx = pickValueNearTimeWithFallback(loc, WX_NAMES, start);
                String temp = pickValueNearTimeWithFallback(loc, T_NAMES, start);
                String pop = pickValueNearTimeWithFallback(loc, POP_NAMES, start);

                String bubble = """
                { "type": "bubble", "size": "mega",
                  "body": { "type": "box", "layout": "vertical", "spacing": "sm",
                    "contents": [
                      { "type": "text", "text": "%s %s", "weight": "bold", "size": "lg" },
                      { "type": "text", "text": "%s", "size": "sm", "color": "#555555" },
                      { "type": "text", "text": "🌤 %s", "size": "md", "wrap": true },
                      { "type": "text", "text": "🌡 %s°C / 💧%s%%", "size": "sm" }
                    ] } }
                """.formatted(city, town,
                        start.format(dtf),
                        safe(wx), safe(temp), safe(pop));
                bubbles.add(bubble);
            }

            return "{ \"type\": \"carousel\", \"contents\": ["+String.join(",", bubbles)+"] }";
        } catch (Exception e) {
            logger.error("48h 預報錯誤", e);
            return textBubble("❌ 查詢失敗：" + e.getMessage());
        }
    }

    /** 今天 / 明天 → 固定時段 00,03,06,09,12,15,18,21 (+今天加24) */
    public String buildDailyWeatherFlex(String city, String town, String whenToken) {
        String datasetId = CITY_CODE_MAP.get(city);
        if (datasetId == null) return textBubble("⚠️ 找不到縣市");

        try {
            String url = UriComponentsBuilder.fromHttpUrl("https://opendata.cwa.gov.tw/api/v1/rest/datastore/" + datasetId)
                    .queryParam("Authorization", apiKey)
                    .queryParam("format", "JSON")
                    .toUriString();
            String json = restTemplate.getForObject(url, String.class);
            ArrayNode locations = findAllLocationsAnyShape(objectMapper.readTree(json==null?"{}":json));
            JsonNode loc = findTown(locations, town);
            if (loc == null) return textBubble("⚠️ 找不到鄉鎮");

            ZoneId tz = ZoneId.of("Asia/Taipei");
            LocalDate targetDate = LocalDate.now(tz);
            if ("明天".equals(whenToken)) targetDate = targetDate.plusDays(1);

            int[] hours = {0,3,6,9,12,15,18,21};
            List<String> bubbles = new ArrayList<>();
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("MM/dd HH:mm");

            for (int h : hours) {
                ZonedDateTime target = targetDate.atTime(h, 0).atZone(tz);
                String wx = pickValueNearTimeWithFallback(loc, WX_NAMES, target);
                String temp = pickValueNearTimeWithFallback(loc, T_NAMES, target);
                String pop = pickValueNearTimeWithFallback(loc, POP_NAMES, target);

                String bubble = """
                { "type": "bubble", "size": "mega",
                  "body": { "type": "box", "layout": "vertical", "spacing": "sm",
                    "contents": [
                      { "type": "text", "text": "%s %s", "weight": "bold", "size": "lg" },
                      { "type": "text", "text": "%s", "size": "sm", "color": "#555555" },
                      { "type": "text", "text": "🌤 %s", "size": "md", "wrap": true },
                      { "type": "text", "text": "🌡 %s°C / 💧%s%%", "size": "sm" }
                    ] } }
                """.formatted(city, town,
                        target.format(dtf), safe(wx), safe(temp), safe(pop));
                bubbles.add(bubble);
            }

            // 今天多加 24:00
            if ("今天".equals(whenToken)) {
                ZonedDateTime target = targetDate.plusDays(1).atStartOfDay(tz);
                String wx = pickValueNearTimeWithFallback(loc, WX_NAMES, target);
                String temp = pickValueNearTimeWithFallback(loc, T_NAMES, target);
                String pop = pickValueNearTimeWithFallback(loc, POP_NAMES, target);
                String bubble = """
                { "type": "bubble", "size": "mega",
                  "body": { "type": "box", "layout": "vertical", "spacing": "sm",
                    "contents": [
                      { "type": "text", "text": "%s %s", "weight": "bold", "size": "lg" },
                      { "type": "text", "text": "%s", "size": "sm", "color": "#555555" },
                      { "type": "text", "text": "🌤 %s", "size": "md", "wrap": true },
                      { "type": "text", "text": "🌡 %s°C / 💧%s%%", "size": "sm" }
                    ] } }
                """.formatted(city, town,
                        target.format(dtf), safe(wx), safe(temp), safe(pop));
                bubbles.add(bubble);
            }

            return "{ \"type\": \"carousel\", \"contents\": ["+String.join(",", bubbles)+"] }";
        } catch (Exception e) {
            logger.error("Daily 預報錯誤", e);
            return textBubble("❌ 查詢失敗：" + e.getMessage());
        }
    }

    // ===== whenToken 解析：今天 / 明天 / HH:mm / YYYY-MM-DD / 「明天 10:00」「2025-09-18 10:00」
    private ZonedDateTime parseWhenToken(String whenToken, ZonedDateTime now, ZoneId tz){
        if (whenToken == null || whenToken.isBlank() || "今天".equals(whenToken)) return now;

        // 支援「<日> <時間>」
        if (whenToken.contains(" ")) {
            String[] tt = whenToken.trim().split("\\s+");
            String d = tt[0], hm = tt.length > 1 ? tt[1] : null;
            if (hm != null && hm.matches("\\d{2}:\\d{2}")) {
                int H = Integer.parseInt(hm.substring(0, 2));
                int M = Integer.parseInt(hm.substring(3, 5));
                if ("今天".equals(d) || "明天".equals(d)) {
                    ZonedDateTime t = now.withHour(H).withMinute(M).withSecond(0).withNano(0);
                    if ("明天".equals(d)) t = t.plusDays(1);
                    return t;
                } else if (d.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    LocalDate ld = LocalDate.parse(d);
                    return ld.atTime(H, M).atZone(tz);
                }
            }
            return null; // 空白但格式不對
        }

        if ("明天".equals(whenToken)) {
            return now.plusDays(1).withHour(12).withMinute(0).withSecond(0).withNano(0);
        }
        if (whenToken.matches("\\d{2}:\\d{2}")) {
            String[] hhmm = whenToken.split(":");
            ZonedDateTime t = now.withHour(Integer.parseInt(hhmm[0])).withMinute(Integer.parseInt(hhmm[1])).withSecond(0).withNano(0);
            if (t.isBefore(now)) t = t.plusDays(1); // 今天已過，抓明天
            return t;
        }
        if (whenToken.matches("\\d{4}-\\d{2}-\\d{2}")) {
            LocalDate d = LocalDate.parse(whenToken);
            return d.atTime(12, 0).atZone(tz);
        }
        return null;
    }

    // ---------------- JSON 掃描 ----------------

    private int containersFound = 0;

    private ArrayNode findAllLocationsAnyShape(JsonNode root){
        containersFound = 0;
        ArrayNode merged = objectMapper.createArrayNode();
        dfs(root, merged);
        return merged;
    }

    private void dfs(JsonNode node, ArrayNode sink){
        if (node==null || node.isMissingNode() || node.isNull()) return;
        if (node.isObject()){
            JsonNode locArr = getCI(node,"location","Location");
            if (isArray(locArr)){
                containersFound++;
                for (JsonNode loc : locArr) sink.add(loc);
            } else if (locArr!=null && locArr.isObject()){
                containersFound++; sink.add(locArr);
            }
            node.fields().forEachRemaining(e -> dfs(e.getValue(), sink));
        }else if (node.isArray()){
            for (JsonNode c : node) dfs(c, sink);
        }
    }

    private String getLocationName(JsonNode loc){
        String v = getTextCI(loc,"locationName","LocationName","name");
        return blank(v) ? "-" : v;
    }

    // ------------- 時段 / 取值 -------------

    private static class Interval{
        String startFmt, endFmt;
        Interval(String s, String e){ startFmt=s; endFmt=e; }
        static Interval unknown(){ return new Interval("-", "-"); }
    }

    private Interval bestInterval(JsonNode loc, ZonedDateTime target){
        for (Set<String> names : List.of(WX_NAMES, T_NAMES, POP_NAMES)){
            JsonNode el = findFirstElementByNames(loc, names);
            Interval it = probeInterval(el, target);
            if (it != null) return it;
        }
        return Interval.unknown();
    }

    private Interval probeInterval(JsonNode element, ZonedDateTime target){
        if (element==null || element.isMissingNode()) return null;
        ArrayNode times = getArrayCI(element,"time","Time","times");
        if (!isArray(times) || times.size()==0) return null;

        ZoneId tz = ZoneId.of("Asia/Taipei");
        DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm");

        int best=-1; long bestDelta=Long.MAX_VALUE; String sOut="-", eOut="-";
        for (int i=0;i<times.size();i++){
            JsonNode ti = times.get(i);
            String st = getTextCI(ti,"startTime","StartTime","start_time");
            String et = getTextCI(ti,"endTime","EndTime","end_time");
            String dt = getTextCI(ti,"dataTime","DataTime","data_time");

            ZonedDateTime s=null,e=null;
            if (!blank(st) && !blank(et)){ s = parseZ(st,tz); e = parseZ(et,tz); }
            else if (!blank(dt)){ s = parseZ(dt,tz); if (s!=null) e = s.plusHours(3); }
            if (s==null || e==null) continue;

            boolean contains = !target.isBefore(s) && target.isBefore(e);
            long delta = Math.abs(Duration.between(target,s).toMinutes());
            if (contains){ best=i; sOut=s.format(hhmm); eOut=e.format(hhmm); break; }
            if (delta<bestDelta){ bestDelta=delta; best=i; sOut=s.format(hhmm); eOut=e.format(hhmm); }
        }
        if (best>=0) return new Interval(sOut,eOut);
        return null;
    }

    private String pickValueNearTimeWithFallback(JsonNode loc, Set<String> elementNames, ZonedDateTime target){
        JsonNode el = findFirstElementByNames(loc, elementNames);
        if (el==null || el.isMissingNode()) return "-";
        ArrayNode times = getArrayCI(el,"time","Time","times");
        if (!isArray(times) || times.size()==0) return "-";

        ZoneId tz = ZoneId.of("Asia/Taipei");
        int best=0; long bestDelta=Long.MAX_VALUE;

        for (int i=0;i<times.size();i++){
            JsonNode ti = times.get(i);
            String st = getTextCI(ti,"startTime","StartTime","start_time");
            String et = getTextCI(ti,"endTime","EndTime","end_time");
            String dt = getTextCI(ti,"dataTime","DataTime","data_time");

            ZonedDateTime s=null,e=null;
            if (!blank(st) && !blank(et)){ s=parseZ(st,tz); e=parseZ(et,tz); }
            else if (!blank(dt)){ s=parseZ(dt,tz); if (s!=null) e=s.plusHours(3); }
            if (s==null || e==null) continue;

            boolean contains = !target.isBefore(s) && target.isBefore(e);
            long delta = Math.abs(Duration.between(target,s).toMinutes());
            if (contains){ best=i; break; }
            if (delta<bestDelta){ bestDelta=delta; best=i; }
        }

        String val = extractFromTimeNode(times.get(best), elementNames);
        if (!blank(val)) return val;

        for (int off=1; off<=3; off++){
            int i1 = best+off, i2 = best-off;
            if (i1<times.size()){
                String v = extractFromTimeNode(times.get(i1), elementNames);
                if (!blank(v)) return v;
            }
            if (i2>=0){
                String v = extractFromTimeNode(times.get(i2), elementNames);
                if (!blank(v)) return v;
            }
        }
        return "-";
    }

    private String extractFromTimeNode(JsonNode timeNode, Set<String> elementNames){
        String raw = valueFromTimeNode(timeNode);
        String after = postProcess(elementNames, raw);
        if (!blank(after)) return after;

        if (elementNames == T_NAMES || elementNames == POP_NAMES){
            JsonNode ev = getCI(timeNode, "elementValue","ElementValue","value","parameter","Parameter");
            String num = deepFindNumber(ev);
            if (!blank(num)) return elementNames == POP_NAMES ? clampPercent(num) : toRounded(num);
        }
        return "-";
    }

    private String postProcess(Set<String> elementNames, String raw){
        if (blank(raw)) return "-";
        if (elementNames == POP_NAMES){
            String n = clampPercent(raw);
            return blank(n) ? "-" : n;
        }
        if (elementNames == T_NAMES){
            String n = toRounded(raw);
            return blank(n) ? "-" : n;
        }
        return raw;
    }

    private String clampPercent(String s){
        String n = firstInt(s);
        if (blank(n)) return "-";
        int v = Integer.parseInt(n);
        v = Math.max(0, Math.min(100, v));
        return String.valueOf(v);
    }

    private String toRounded(String s){
        String n = firstNumber(s);
        if (blank(n)) return "-";
        try{
            double d = Double.parseDouble(n);
            long r = Math.round(d);
            return String.valueOf(r);
        }catch(Exception e){ return "-"; }
    }

    private String firstInt(String s){
        if (s==null) return "";
        Matcher m = Pattern.compile("-?\\d+").matcher(s);
        return m.find() ? m.group() : "";
    }

    private String firstNumber(String s){
        if (s==null) return "";
        Matcher m = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(s);
        return m.find() ? m.group() : "";
    }

    private String valueFromTimeNode(JsonNode timeNode){
        if (timeNode==null || timeNode.isMissingNode()) return "-";
        JsonNode ev = getCI(timeNode,"elementValue","ElementValue","value","parameter","Parameter");
        if (ev==null || ev.isNull()) return "-";

        if (ev.isValueNode()) return ev.asText();

        String direct = getTextCI(ev,"weather.value","parameterValue","ParameterValue","value","Value","text","name");
        if (!blank(direct)) return direct;

        if (ev.isObject() && ev.size()==1){
            Map.Entry<String,JsonNode> only = ev.fields().next();
            String nested = preferReadable(only.getValue());
            if (!blank(nested)) return nested;
        }

        for (String k : List.of("Temperature","Wx","WeatherDescription","PoP3h","PoP6h","PoP12h","ProbabilityOfPrecipitation")){
            JsonNode c = getCI(ev,k);
            if (c!=null && !c.isMissingNode()){
                String nested = preferReadable(c);
                if (!blank(nested)) return nested;
            }
        }

        if (ev.isArray() && ev.size()>0){
            String best = bestFromArray(ev);
            if (!blank(best)) return best;
        }

        String deep = deepFindReadable(ev);
        return blank(deep) ? "-" : deep;
    }

    private String deepFindReadable(JsonNode n){
        if (n==null || n.isMissingNode()) return null;
        if (n.isValueNode()){
            String s = n.asText();
            if (!blank(s) && readabilityScore(s) >= 0) return s;
            return null;
        }
        if (n.isArray()){
            String best = null; int score = -1;
            for (JsonNode c : n){
                String got = deepFindReadable(c);
                int sc = readabilityScore(got);
                if (sc>score){ score=sc; best=got; }
            }
            return best;
        }
        if (n.isObject()){
            for (String key : List.of("value","Value","text","name","parameterValue","ParameterValue","WeatherDescription","Wx")){
                JsonNode c = getCI(n, key);
                if (c!=null){
                    String got = deepFindReadable(c);
                    if (!blank(got)) return got;
                }
            }
            Iterator<String> it = n.fieldNames();
            while (it.hasNext()){
                String k = it.next();
                String got = deepFindReadable(n.get(k));
                if (!blank(got)) return got;
            }
        }
        return null;
    }

    private String deepFindNumber(JsonNode n){
        if (n==null || n.isMissingNode()) return "";
        if (n.isValueNode()){
            String s = firstNumber(n.asText());
            return s;
        }
        if (n.isArray()){
            for (JsonNode c : n){
                String got = deepFindNumber(c);
                if (!blank(got)) return got;
            }
            return "";
        }
        if (n.isObject()){
            for (String key : List.of("value","Value","parameterValue","ParameterValue","Temperature","PoP3h","PoP6h","PoP12h","ProbabilityOfPrecipitation")){
                JsonNode c = getCI(n, key);
                if (c!=null){
                    String got = deepFindNumber(c);
                    if (!blank(got)) return got;
                }
            }
            Iterator<String> it = n.fieldNames();
            while (it.hasNext()){
                String k = it.next();
                String got = deepFindNumber(n.get(k));
                if (!blank(got)) return got;
            }
        }
        return "";
    }

    private String preferReadable(JsonNode n){
        if (n==null || n.isMissingNode()) return "-";
        if (n.isValueNode()) return n.asText();

        String v = getTextCI(n,"weather.value","parameterValue","ParameterValue","value","Value","text","name");
        if (!blank(v)) return v;

        if (n.isArray() && n.size()>0){
            return bestFromArray(n);
        }

        if (n.isObject() && n.size()==1){
            return preferReadable(n.elements().next());
        }

        String v2 = getTextCI(n,"value","Value");
        if (!blank(v2)) return v2;

        return "-";
    }

    private String bestFromArray(JsonNode arr){
        String best="-"; int score=-1;
        for (JsonNode x : arr){
            String cand = preferReadable(x);
            int s = readabilityScore(cand);
            if (s>score){ score=s; best=cand; }
        }
        return best;
    }

    private int readabilityScore(String s){
        if (blank(s)) return -1;
        int sc = Math.min(40, s.length());
        if (containsCJK(s)) sc += 50;
        if (s.matches("\\d{1,3}")) sc -= 10;
        return sc;
    }

    private boolean containsCJK(String s){
        if (blank(s)) return false;
        for (int i=0;i<s.length();i++){
            char c = s.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    private ZonedDateTime parseZ(String ts, ZoneId tz){
        try{
            if (blank(ts)) return null;
            String t = ts.replace('T',' ');
            String base = t.length()>=19 ? t.substring(0,19) : (t+":00").substring(0,19);
            LocalDateTime ldt = LocalDateTime.parse(base, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return ldt.atZone(tz);
        }catch(Exception e){ return null; }
    }

    private JsonNode findFirstElementByNames(JsonNode loc, Set<String> namesWanted){
        ArrayNode arr = getArrayCI(loc,"weatherElement","WeatherElement");
        if (!isArray(arr)) return NullNode.getInstance();

        for (JsonNode el : arr){
            String name = getTextCI(el,"elementName","ElementName","name","element");
            if (name==null) name="";
            for (String expect : namesWanted){
                if (name.equalsIgnoreCase(expect)) return el;
                if (name.replace("台","臺").equals(expect)) return el;
            }
        }
        return NullNode.getInstance();
    }

    private JsonNode findTown(ArrayNode locationArray, String inputTown){
        String n0 = norm(inputTown);
        JsonNode firstContains = null;
        for (JsonNode loc : locationArray){
            String name = getLocationName(loc);
            if (name.equals(inputTown) || name.equals(inputTown.replace("台","臺"))) return loc;
        }
        for (JsonNode loc : locationArray){
            String name = getLocationName(loc);
            if (norm(name).equals(n0)) return loc;
            if (firstContains==null && (name.contains(inputTown) || inputTown.contains(name))) firstContains = loc;
        }
        return firstContains;
    }

    private String norm(String s){
        if (s==null) return "";
        s = s.trim().replace("台","臺");
        return s.replace("區","").replace("鄉","").replace("鎮","");
    }

    private String safe(String s){ return blank(s) ? "-" : s; }
    private boolean blank(String s){ return s==null || s.isBlank() || "-".equals(s); }

    // ---- Flex（無 hero）----
    private String textBubble(String text){
        return """
        { "type": "bubble",
          "body": { "type": "box", "layout": "vertical",
            "contents": [ { "type": "text", "text": "%s", "wrap": true } ] } }
        """.formatted(j(text));
    }

    private String bubbleNoHero(String title, String body){
        return """
        { "type": "bubble",
          "body": { "type": "box", "layout": "vertical",
            "contents": [
              { "type": "text", "text": "%s", "weight": "bold", "size": "xl", "wrap": true },
              { "type": "text", "text": "%s", "size": "md", "margin": "sm", "wrap": true }
            ] } }
        """.formatted(j(title), j(body));
    }

    private String j(String s){
        if (s==null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"")
                .replace("\r","\\r").replace("\n","\\n").replace("\t","\\t");
    }

    // ===== 偵錯輸出 =====
    private void debugDumpElements(JsonNode loc){
        ArrayNode arr = getArrayCI(loc,"weatherElement","WeatherElement");
        if (!isArray(arr)){
            logger.warn("[CWA] weatherElement missing for {}", getLocationName(loc));
            return;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode el : arr) names.add(getTextCI(el,"elementName","ElementName","name","element"));
        logger.warn("[CWA] elements for {} => {}", getLocationName(loc), String.join(", ", names));

        if (arr.size()>0){
            JsonNode el0 = arr.get(0);
            List<String> el0Keys = new ArrayList<>();
            el0.fieldNames().forEachRemaining(el0Keys::add);
            logger.warn("[CWA] first element raw keys => {}", String.join("|", el0Keys));

            ArrayNode t0 = getArrayCI(el0,"time","Time","times");
            if (isArray(t0) && t0.size()>0){
                for (int i=0;i<Math.min(2,t0.size());i++){
                    JsonNode t = t0.get(i);
                    logger.warn("[CWA] sample time[{}] keys: start={}, end={}, dataTime={}, valueKeys={}",
                            i,
                            getTextCI(t,"startTime","StartTime","start_time"),
                            getTextCI(t,"endTime","EndTime","end_time"),
                            getTextCI(t,"dataTime","DataTime","data_time"),
                            keysOf(getCI(t,"elementValue","ElementValue","value","parameter","Parameter")));
                }
            }
        }
    }

    private String keysOf(JsonNode n){
        if (n==null || n.isMissingNode()) return "-";
        if (n.isArray() && n.size()>0) n = n.get(0);
        if (n.isObject()){
            List<String> ks = new ArrayList<>();
            n.fieldNames().forEachRemaining(ks::add);
            return String.join("|", ks);
        }
        return n.getNodeType().name();
    }

    private boolean isArray(JsonNode n){ return n!=null && n.isArray(); }
    private ArrayNode getArrayCI(JsonNode obj, String... keys){
        JsonNode n = getCI(obj, keys);
        return (n!=null && n.isArray()) ? (ArrayNode)n : null;
    }
    private JsonNode getCI(JsonNode obj, String... keys){
        if (obj==null || obj.isMissingNode()) return null;
        for (String k: keys){
            JsonNode n = obj.get(k);
            if (n!=null && !n.isMissingNode()) return n;
        }
        Set<String> normalized = new HashSet<>();
        for (String k: keys) normalized.add(normKey(k));
        Iterator<String> it = obj.fieldNames();
        while (it.hasNext()){
            String f = it.next();
            if (normalized.contains(normKey(f))) return obj.get(f);
        }
        return null;
    }
    private String getTextCI(JsonNode obj, String... keys){
        for (String k: keys){
            String v = getTextOne(obj, k);
            if (!blank(v)) return v;
        }
        return null;
    }
    private String getTextOne(JsonNode obj, String key){
        if (obj==null || obj.isMissingNode()) return null;
        if (key.contains(".")){
            String[] parts = key.split("\\.");
            JsonNode cur = obj;
            for (String p : parts){
                cur = getCI(cur, p);
                if (cur==null) return null;
            }
            return cur.isValueNode() ? cur.asText() : null;
        }else{
            JsonNode n = getCI(obj, key);
            return (n!=null && n.isValueNode()) ? n.asText() : null;
        }
    }
    private String normKey(String s){ return s.toLowerCase().replace("_","").trim(); }
}