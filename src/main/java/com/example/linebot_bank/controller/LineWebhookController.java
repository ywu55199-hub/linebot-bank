package com.example.linebot_bank.controller;

import com.example.linebot_bank.model.Transaction;
import com.example.linebot_bank.model.TransactionType;
import com.example.linebot_bank.service.BankService;
import com.example.linebot_bank.service.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/line")
public class LineWebhookController {

    private static final String ZWSP = "\u200B"; // 零寬空白

    private final BankService bankService;
    private final WeatherService weatherService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    // 改名引導狀態
    private final Map<String, Boolean> renamePending = new ConcurrentHashMap<>();

    @Value("${line.channelSecret}")
    private String channelSecret;

    @Value("${line.channelAccessToken}")
    private String channelAccessToken;

    public LineWebhookController(BankService bankService, WeatherService weatherService) {
        this.bankService = bankService;
        this.weatherService = weatherService;
    }

    /** LINE Webhook 入口 */
    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(
            @RequestHeader(name = "X-Line-Signature", required = false) String signature,
            @RequestBody String body) {

        if (signature == null || !verifySignature(body, signature, channelSecret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Bad signature");
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode events = root.get("events");
            if (events != null && events.isArray()) {
                for (JsonNode event : events) {
                    String type = event.path("type").asText();

                    // 加好友 / 被邀進群組 → 主選單
                    if ("follow".equals(type) || "join".equals(type)) {
                        String replyToken = event.path("replyToken").asText();
                        replyMenuQuick(replyToken);
                        continue;
                    }

                    // 處理文字訊息
                    if ("message".equals(type)
                            && "text".equals(event.path("message").path("type").asText())) {

                        String replyToken = event.path("replyToken").asText();
                        String text = event.path("message").path("text").asText().trim();
                        String userId = event.path("source").path("userId").asText();

                        // 改名互動模式
                        if (renamePending.getOrDefault(userId, false)) {
                            if ("取消".equals(text)) {
                                renamePending.put(userId, false);
                                replyTextWithMenu(replyToken, "已取消改名。");
                                continue;
                            }
                            if (text.isBlank()) {
                                replyError(replyToken, "名字不能是空白，請再輸入一次。或輸入「取消」退出。");
                                continue;
                            }
                            try {
                                var acc = bankService.rename(userId, text);
                                renamePending.put(userId, false);
                                replyTextWithMenu(replyToken, "✅ 改名成功：" + acc.getName());
                            } catch (Exception ex) {
                                replyError(replyToken, "改名失敗：" + ex.getMessage());
                            }
                            continue;
                        }

                        // ✅ 停用帳戶
                        if ("停用帳戶".equals(text)) {
                            try {
                                bankService.deactivateAccount(userId);
                                replyTextWithMenu(replyToken, "✅ 帳戶已停用（之後可以輸入『註冊 名字』重新啟用）");
                            } catch (Exception ex) {
                                replyError(replyToken, "停用失敗：" + ex.getMessage());
                            }
                            continue;
                        }

                        // ✅ 刪除帳戶
                        if ("刪除帳戶".equals(text)) {
                            try {
                                bankService.deleteAccount(userId);
                                replyTextWithMenu(replyToken, "✅ 帳戶已刪除（含交易紀錄）");
                            } catch (Exception ex) {
                                replyError(replyToken, "刪除失敗：" + ex.getMessage());
                            }
                            continue;
                        }

                        // ✅ 註冊帳戶
                        if (text.startsWith("註冊")) {
                            try {
                                String reply = handleCommand(userId, text);
                                replyTextWithMenu(replyToken, reply);
                            } catch (Exception ex) {
                                replyError(replyToken, "註冊失敗：" + ex.getMessage());
                            }
                            continue;
                        }

                        // ✅ 天氣查詢
                        if (text.startsWith("天氣")) {
                            String[] parts = text.split("\\s+");
                            if (parts.length < 3) {
                                replyTextWithMenu(replyToken,
                                        "⚠️ 查詢失敗，請輸入格式：天氣 縣市 鄉鎮，例如：天氣 臺北市 文山區");
                            } else {
                                String city = parts[1];
                                String town = parts[2];
                                try {
                                    String bubble = weatherService.buildWeatherFlexMessage(city, town);
                                    replyFlex(replyToken, "天氣資訊", bubble);
                                } catch (Exception ex) {
                                    replyError(replyToken, "天氣查詢失敗：" + ex.getMessage());
                                }
                            }
                            continue;
                        }

                        // ✅ 選單快捷
                        if (isMenuKeyword(text)) { replyMenuQuick(replyToken); continue; }
                        if ("存款".equals(text)) { replyAmountQuick(replyToken, "deposit"); continue; }
                        if ("提款".equals(text)) { replyAmountQuick(replyToken, "withdraw"); continue; }

                        // ✅ 改名（觸發互動模式）
                        if ("改名".equals(text)) {
                            renamePending.put(userId, true);
                            replyTextWithMenu(replyToken, "請輸入新名字（或輸入「取消」退出）：");
                            continue;
                        }

                        // ✅ 查餘額
                        if ("餘額".equals(text)) {
                            try {
                                BigDecimal bal = bankService.getBalance(userId);
                                replyFlex(replyToken, "目前餘額", buildBalanceFlex(bal));
                            } catch (Exception ex) {
                                replyError(replyToken, "查詢餘額失敗：" + ex.getMessage());
                            }
                            continue;
                        }

                        // ✅ 查交易明細
                        if ("明細".equals(text)) {
                            try {
                                List<Transaction> list = bankService.lastTransactions(userId);
                                if (list.isEmpty()) {
                                    replyTextWithMenu(replyToken, "尚無交易紀錄");
                                } else {
                                    replyFlex(replyToken, "最近交易", buildTransactionListFlex(list));
                                }
                            } catch (Exception ex) {
                                replyError(replyToken, "查詢明細失敗：" + ex.getMessage());
                            }
                            continue;
                        }

                        // ✅ 存款（文字輸入格式：存 1000）
                        if (text.matches("^存\\s+\\d+(\\.\\d{1,2})?$")) {
                            try {
                                String num = text.split("\\s+")[1];
                                BigDecimal amt = new BigDecimal(num);
                                BigDecimal newBal = bankService.deposit(userId, amt, "LINE 存款");
                                String flex = buildTransactionFlex("存款", amt, newBal, "LINE 存款");
                                replyFlex(replyToken, "存款成功", flex);
                            } catch (Exception ex) {
                                replyError(replyToken, "存款失敗：" + ex.getMessage());
                            }
                            continue;
                        }

                        // ✅ 提款（文字輸入格式：提 500）
                        if (text.matches("^提\\s+\\d+(\\.\\d{1,2})?$")) {
                            try {
                                String num = text.split("\\s+")[1];
                                BigDecimal amt = new BigDecimal(num);
                                BigDecimal newBal = bankService.withdraw(userId, amt, "LINE 提款");
                                String flex = buildTransactionFlex("提款", amt, newBal, "LINE 提款");
                                replyFlex(replyToken, "提款成功", flex);
                            } catch (Exception ex) {
                                replyError(replyToken, "提款失敗：" + ex.getMessage());
                            }
                            continue;
                        }

                        // 其他 → 呼叫 handleCommand
                        String reply = handleCommand(userId, text);
                        if (reply == null || reply.isBlank()) {
                            replyMenuQuick(replyToken);
                        } else {
                            replyTextWithMenu(replyToken, reply);
                        }
                    }

                    // ✅ Postback（處理快捷金額操作）
                    if ("postback".equals(type)) {
                        String replyToken = event.path("replyToken").asText();
                        String data = event.path("postback").path("data").asText();
                        String userId = event.path("source").path("userId").asText();
                        handlePostback(replyToken, userId, data);
                    }
                }
            }
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.ok("OK");
        }
    }

    /** Postback：處理存提款 */
    private void handlePostback(String replyToken, String userId, String data) {
        try {
            if (data == null || data.isBlank()) {
                replyTextWithMenu(replyToken, "無有效的操作");
                return;
            }
            String action = null, amountStr = null;
            for (String kv : data.split("&")) {
                String[] pair = kv.split("=", 2);
                if (pair.length == 2) {
                    if ("action".equals(pair[0])) action = pair[1];
                    if ("amount".equals(pair[0])) amountStr = pair[1];
                }
            }
            if ("deposit".equals(action) && amountStr != null) {
                BigDecimal amt = new BigDecimal(amountStr);
                BigDecimal newBal = bankService.deposit(userId, amt, "LINE 存款（快速）");
                String flex = buildTransactionFlex("存款", amt, newBal, "LINE 存款（快速）");
                replyFlex(replyToken, "存款成功", flex);
                return;
            }
            if ("withdraw".equals(action) && amountStr != null) {
                BigDecimal amt = new BigDecimal(amountStr);
                BigDecimal newBal = bankService.withdraw(userId, amt, "LINE 提款（快速）");
                String flex = buildTransactionFlex("提款", amt, newBal, "LINE 提款（快速）");
                replyFlex(replyToken, "提款成功", flex);
                return;
            }
            replyTextWithMenu(replyToken, "無效的操作");
        } catch (Exception e) {
            replyError(replyToken, "操作失敗：" + e.getMessage());
        }
    }

    /** Flex 回覆 */
    private void replyFlex(String replyToken, String altText, String bubbleJson) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("replyToken", replyToken);

            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("type", "flex");
            msg.put("altText", altText);
            msg.set("contents", objectMapper.readTree(bubbleJson));

            messages.add(msg);
            root.set("messages", messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(channelAccessToken);

            HttpEntity<String> entity =
                    new HttpEntity<>(objectMapper.writeValueAsString(root), headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    "https://api.line.me/v2/bot/message/reply",
                    HttpMethod.POST, entity, String.class
            );

            System.out.println("LINE 回應: " + response.getStatusCode() + " " + response.getBody());

        } catch (Exception e) {
            e.printStackTrace();
            replyTextWithMenu(replyToken, altText); // fallback
        }
    }

    /** 驗證簽章 */
    private boolean verifySignature(String body, String signature, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(digest);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** 文字回覆（含快捷鍵） */
    private void replyTextWithMenu(String replyToken, String message) {
        try {
            if (message == null || message.isBlank()) {
                message = "請選擇功能：";
            }
            String url = "https://api.line.me/v2/bot/message/reply";
            String payload = """
            {
              "replyToken":"%s",
              "messages":[
                {
                  "type":"text",
                  "text":%s,
                  "quickReply":{
                    "items":[
                      {"type":"action","action":{"type":"message","label":"選單","text":"選單"}},
                      {"type":"action","action":{"type":"message","label":"餘額","text":"餘額"}},
                      {"type":"action","action":{"type":"message","label":"明細","text":"明細"}},
                      {"type":"action","action":{"type":"message","label":"存款","text":"存款"}},
                      {"type":"action","action":{"type":"message","label":"提款","text":"提款"}},
                      {"type":"action","action":{"type":"message","label":"改名","text":"改名"}},
                      {"type":"action","action":{"type":"message","label":"停用帳戶","text":"停用帳戶"}},
                      {"type":"action","action":{"type":"message","label":"刪除帳戶","text":"刪除帳戶"}},
                      {"type":"action","action":{"type":"message","label":"天氣","text":"天氣 臺北市 文山區"}}
                    ]
                  }
                }
              ]
            }
            """.formatted(replyToken, objectMapper.writeValueAsString(message));

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(channelAccessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 主選單 */
    private void replyMenuQuick(String replyToken) {
        try {
            String url = "https://api.line.me/v2/bot/message/reply";
            String payload = """
            {
              "replyToken":"%s",
              "messages":[
                {
                  "type":"text",
                  "text":"請選擇功能：",
                  "quickReply":{
                    "items":[
                      {"type":"action","action":{"type":"message","label":"查餘額","text":"餘額"}},
                      {"type":"action","action":{"type":"message","label":"存款","text":"存款"}},
                      {"type":"action","action":{"type":"message","label":"提款","text":"提款"}},
                      {"type":"action","action":{"type":"message","label":"交易明細","text":"明細"}},
                      {"type":"action","action":{"type":"message","label":"改名","text":"改名"}},
                      {"type":"action","action":{"type":"message","label":"停用帳戶","text":"停用帳戶"}},
                      {"type":"action","action":{"type":"message","label":"刪除帳戶","text":"刪除帳戶"}},
                      {"type":"action","action":{"type":"message","label":"天氣","text":"天氣 臺北市 文山區"}}
                    ]
                  }
                }
              ]
            }
            """.formatted(replyToken);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(channelAccessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 金額快捷鍵 */
    private void replyAmountQuick(String replyToken, String action) {
        try {
            String title = "deposit".equals(action) ? "請選擇存款金額：" : "請選擇提款金額：";
            String actionLabel = "deposit".equals(action) ? "存 " : "提 ";
            String actionName = action;

            String payload = """
            {
              "replyToken":"%s",
              "messages":[
                {
                  "type":"text",
                  "text":"%s",
                  "quickReply":{
                    "items":[
                      {"type":"action","action":{"type":"postback","label":"100","data":"action=%s&amount=100","displayText":"%s100"}},
                      {"type":"action","action":{"type":"postback","label":"500","data":"action=%s&amount=500","displayText":"%s500"}},
                      {"type":"action","action":{"type":"postback","label":"1000","data":"action=%s&amount=1000","displayText":"%s1000"}},
                      {"type":"action","action":{"type":"postback","label":"2000","data":"action=%s&amount=2000","displayText":"%s2000"}},
                      {"type":"action","action":{"type":"postback","label":"5000","data":"action=%s&amount=5000","displayText":"%s5000"}},
                      {"type":"action","action":{"type":"postback","label":"10000","data":"action=%s&amount=10000","displayText":"%s10000"}},
                      {"type":"action","action":{"type":"message","label":"返回選單","text":"選單"}}
                    ]
                  }
                }
              ]
            }
            """.formatted(
                    replyToken,
                    title,
                    actionName, actionLabel,
                    actionName, actionLabel,
                    actionName, actionLabel,
                    actionName, actionLabel,
                    actionName, actionLabel,
                    actionName, actionLabel
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(channelAccessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange("https://api.line.me/v2/bot/message/reply",
                    HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 建立交易摘要 Flex */
    private String buildTransactionFlex(String type, BigDecimal amount, BigDecimal newBalance, String note) {
        String amt = amount.stripTrailingZeros().toPlainString();
        String bal = newBalance.stripTrailingZeros().toPlainString();
        String memo = (note == null || note.isBlank()) ? "-" : note;
        String headerColor = "存款".equals(type) ? "#DFF6DD" : "#F9D6D5";

        return """
        {
          "type": "bubble",
          "size": "mega",
          "header": {
            "type": "box",
            "layout": "vertical",
            "backgroundColor": "%s",
            "contents": [
              { "type": "text", "text": "交易成功（%s）", "weight": "bold", "size": "lg", "align":"center" }
            ]
          },
          "body": {
            "type": "box",
            "layout": "vertical",
            "spacing": "md",
            "contents": [
              { "type": "text", "text": "$%s", "weight": "bold", "size": "xxl", "align": "center" },
              {
                "type": "box",
                "layout": "baseline",
                "contents": [
                  {"type":"text","text":"💰 新餘額","size":"sm","color":"#888888","flex":2},
                  {"type":"text","text":"$%s","size":"sm","flex":5}
                ]
              },
              {
                "type": "box",
                "layout": "baseline",
                "contents": [
                  {"type":"text","text":"備註","size":"sm","color":"#888888","flex":2},
                  {"type":"text","text":"%s","size":"sm","flex":5}
                ]
              }
            ]
          }
        }
        """.formatted(headerColor, type, amt, bal, memo);
    }

    /** 餘額 Flex */
    private String buildBalanceFlex(BigDecimal balance) {
        String bal = balance.stripTrailingZeros().toPlainString();
        return """
        {
          "type": "bubble",
          "body": {
            "type": "box",
            "layout": "vertical",
            "contents": [
              { "type": "text", "text": "💰 目前餘額", "weight": "bold", "size": "lg", "align":"center" },
              { "type": "text", "text": "$%s", "size": "xxl", "align":"center", "color":"#2E7D32" }
            ]
          }
        }
        """.formatted(bal);
    }

    /** 交易明細 Flex */
    private String buildTransactionListFlex(List<Transaction> list) {
        StringBuilder rows = new StringBuilder();
        int i = 0;
        for (Transaction tx : list.stream().limit(5).toList()) {
            String type = tx.getType() == TransactionType.DEPOSIT ? "存" : "提";
            String amt = tx.getAmount().stripTrailingZeros().toPlainString();
            String time = String.valueOf(tx.getCreatedAt());
            if (i++ > 0) rows.append(",");
            try {
                rows.append("""
                {
                  "type":"box",
                  "layout":"horizontal",
                  "spacing":"sm",
                  "contents":[
                    {"type":"text","text":%s,"size":"sm","flex":2},
                    {"type":"text","text":%s,"size":"sm","align":"end","flex":3}
                  ]
                }
                """.formatted(
                        objectMapper.writeValueAsString(type + " $" + amt),
                        objectMapper.writeValueAsString(time)
                ));
            } catch (Exception ignored) {}
        }

        return """
        {
          "type": "bubble",
          "body": {
            "type": "box",
            "layout": "vertical",
            "spacing": "sm",
            "contents": [
              { "type": "text", "text": "最近交易", "weight": "bold", "size": "lg" }
              %s
            ]
          }
        }
        """.formatted(rows.length() > 0 ? "," + rows : "");
    }

    /** 指令解析 */
    private String handleCommand(String userId, String text) {
        try {
            if (text.startsWith("註冊")) {
                String name = text.replaceFirst("^註冊\\s*", "");
                if (name.isBlank()) name = "用戶";
                var acc = bankService.register(userId, name);
                return "註冊成功：" + acc.getName() + "\n目前餘額：" + acc.getBalance();
            }
            if (text.startsWith("改名")) {
                String newName = text.replaceFirst("^改名\\s*", "").trim();
                if (newName.isBlank()) return "請輸入新名字，例如：改名 Alan";
                var acc = bankService.rename(userId, newName);
                return "改名成功：" + acc.getName();
            }
            if (text.equals("餘額")) {
                return "目前餘額：" + bankService.getBalance(userId);
            }
            if (text.equals("明細")) {
                List<Transaction> list = bankService.lastTransactions(userId);
                if (list.isEmpty()) return "尚無交易紀錄";
                StringJoiner sj = new StringJoiner("\n");
                list.stream().limit(5).forEach(tx -> sj.add(
                        (tx.getType() == TransactionType.DEPOSIT ? "存" : "提")
                                + " " + tx.getAmount() + " @ " + tx.getCreatedAt()));
                return "最近交易（最多 5 筆）：\n" + sj;
            }
        } catch (Exception ex) {
            return "系統發生錯誤：" + ex.getMessage();
        }
        return null;
    }

    /** 主選單判斷 */
    private boolean isMenuKeyword(String text) {
        return "選單".equals(text) || "功能".equals(text)
                || "menu".equalsIgnoreCase(text) || "開始".equals(text);
    }

    /** 錯誤回覆 */
    private void replyError(String replyToken, String message) {
        replyTextWithMenu(replyToken, "❌ " + message + "\n💡 您可以輸入「選單」查看功能");
    }
}