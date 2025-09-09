package com.example.linebot_bank.controller;

import com.example.linebot_bank.model.Transaction;
import com.example.linebot_bank.model.TransactionType;
import com.example.linebot_bank.service.BankService;
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
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;

@RestController
@RequestMapping("/line")
public class LineWebhookController {

    private static final String ZWSP = "\u200B"; // 零寬空白：讓訊息看起來沒有文字

    private final BankService bankService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${line.channelSecret}")
    private String channelSecret;

    @Value("${line.channelAccessToken}")
    private String channelAccessToken;

    public LineWebhookController(BankService bankService) {
        this.bankService = bankService;
    }

    /** LINE Webhook 入口 */
    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(
            @RequestHeader(name = "X-Line-Signature", required = false) String signature,
            @RequestBody String body) {

        // 驗證簽章
        if (signature == null || !verifySignature(body, signature, channelSecret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Bad signature");
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode events = root.get("events");
            if (events != null && events.isArray()) {
                for (JsonNode event : events) {

                    String type = event.path("type").asText();

                    /** 個聊加好友（follow）就丟選單（只有快捷鍵） */
                    if ("follow".equals(type)) {
                        String replyToken = event.path("replyToken").asText();
                        replyMenuQuick(replyToken);
                        continue;
                    }

                    /** 被邀進群組/聊天室也丟選單（只有快捷鍵） */
                    if ("join".equals(type)) {
                        String replyToken = event.path("replyToken").asText();
                        replyMenuQuick(replyToken);
                        continue;
                    }

                    // 文字訊息
                    if ("message".equals(type)
                            && "text".equals(event.path("message").path("type").asText())) {

                        String replyToken = event.path("replyToken").asText();
                        String text = event.path("message").path("text").asText().trim();
                        String userId = event.path("source").path("userId").asText();

                        if (isMenuKeyword(text)) { replyMenuQuick(replyToken); continue; }
                        if ("存款".equals(text)) { replyAmountQuick(replyToken, "deposit"); continue; }
                        if ("提款".equals(text)) { replyAmountQuick(replyToken, "withdraw"); continue; }

                        // 直接處理「存 1000 / 提 500」→ 回 Flex
                        if (text.matches("^存\\s+\\d+(\\.\\d{1,2})?$")) {
                            try {
                                String num = text.split("\\s+")[1];
                                BigDecimal amt = new BigDecimal(num);
                                BigDecimal newBal = bankService.deposit(userId, amt, "LINE 存款");
                                String flex = buildTransactionFlex("存款", amt, newBal, "LINE 存款");
                                replyFlex(replyToken, "存款成功", flex);
                            } catch (Exception ex) {
                                replyTextWithMenu(replyToken, "存款失敗：" + ex.getMessage());
                            }
                            continue;
                        }
                        if (text.matches("^提\\s+\\d+(\\.\\d{1,2})?$")) {
                            try {
                                String num = text.split("\\s+")[1];
                                BigDecimal amt = new BigDecimal(num);
                                BigDecimal newBal = bankService.withdraw(userId, amt, "LINE 提款");
                                String flex = buildTransactionFlex("提款", amt, newBal, "LINE 提款");
                                replyFlex(replyToken, "提款成功", flex);
                            } catch (Exception ex) {
                                replyTextWithMenu(replyToken, "提款失敗：" + ex.getMessage());
                            }
                            continue;
                        }

                        // 其他指令仍用原文字回覆＋快捷鍵
                        String reply = handleCommand(userId, text);
                        if (reply == null || reply.isBlank()) {
                            replyMenuQuick(replyToken);
                        } else {
                            replyTextWithMenu(replyToken, reply);
                        }
                    }

                    // Postback（點金額按鈕）→ 直接回 Flex
                    if ("postback".equals(type)) {
                        String replyToken = event.path("replyToken").asText();
                        String data = event.path("postback").path("data").asText(); // action=deposit&amount=500
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

    /** 指令解析（仍保留打字版：餘額 / 明細 / 註冊 / 改名） */
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
        } catch (IllegalArgumentException ex) {
            return "錯誤：" + ex.getMessage();
        } catch (Exception ex) {
            ex.printStackTrace();
            return "系統發生錯誤，請稍後再試";
        }
        return null;
    }

    /** Postback：action=deposit/withdraw&amount=數字（直接回 Flex） */
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
            replyTextWithMenu(replyToken, "操作失敗：" + e.getMessage());
        }
    }

    /** 文字回覆 + 常用快捷鍵（防空字串；加入「改名」） */
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
                      {"type":"action","action":{"type":"message","label":"改名","text":"改名 你的新名字"}}
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

    /** 主選單（只有快捷鍵，無文字） */
    private void replyMenuQuick(String replyToken) {
        try {
            String url = "https://api.line.me/v2/bot/message/reply";
            String payload = """
            {
              "replyToken":"%s",
              "messages":[
                {
                  "type":"text",
                  "text":"%s",
                  "quickReply":{
                    "items":[
                      {"type":"action","action":{"type":"message","label":"查餘額","text":"餘額"}},
                      {"type":"action","action":{"type":"message","label":"存款","text":"存款"}},
                      {"type":"action","action":{"type":"message","label":"提款","text":"提款"}},
                      {"type":"action","action":{"type":"message","label":"交易明細","text":"明細"}}
                    ]
                  }
                }
              ]
            }
            """.formatted(replyToken, ZWSP); // 零寬空白：視覺上無文字

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(channelAccessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 顯示金額快捷鍵（ATM 風格） */
    private void replyAmountQuick(String replyToken, String action) {
        try {
            String title = "deposit".equals(action) ? "請選擇存款金額：" : "請選擇提款金額：";
            String actionLabel = "deposit".equals(action) ? "存 " : "提 ";
            String actionName = action; // deposit / withdraw

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

    /** 以 Flex 卡片回覆（altText 為無法顯示時的替代文字） */
    private void replyFlex(String replyToken, String altText, String flexJson) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("replyToken", replyToken);

            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("type", "flex");
            msg.put("altText", altText);
            // contents 必須是物件，不是字串
            msg.set("contents", objectMapper.readTree(flexJson));
            messages.add(msg);

            root.set("messages", messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(channelAccessToken);

            HttpEntity<String> entity =
                    new HttpEntity<>(objectMapper.writeValueAsString(root), headers);

            restTemplate.exchange(
                    "https://api.line.me/v2/bot/message/reply",
                    HttpMethod.POST, entity, String.class
            );
        } catch (Exception e) {
            e.printStackTrace();
            // 失敗則退回文字
            replyTextWithMenu(replyToken, altText);
        }
    }

    /** 建立交易摘要 Flex JSON（Bubble） */
    private String buildTransactionFlex(String type, BigDecimal amount, BigDecimal newBalance, String note) {
        String amt = amount.stripTrailingZeros().toPlainString();
        String bal = newBalance.stripTrailingZeros().toPlainString();
        String memo = (note == null || note.isBlank()) ? "-" : note;

        return """
        {
          "type": "bubble",
          "size": "mega",
          "header": {
            "type": "box",
            "layout": "vertical",
            "backgroundColor": "#E7F8ED",
            "contents": [
              { "type": "text", "text": "交易成功（%s）", "weight": "bold", "size": "lg" }
            ]
          },
          "body": {
            "type": "box",
            "layout": "vertical",
            "spacing": "sm",
            "contents": [
              { "type": "separator", "margin": "md" },
              {
                "type": "box",
                "layout": "vertical",
                "margin": "md",
                "spacing": "xs",
                "contents": [
                  {
                    "type": "box",
                    "layout": "baseline",
                    "contents": [
                      {"type":"text","text":"金額","size":"sm","color":"#888888","flex":2},
                      {"type":"text","text":"$%s","size":"sm","wrap":true,"flex":5}
                    ]
                  },
                  {
                    "type": "box",
                    "layout": "baseline",
                    "contents": [
                      {"type":"text","text":"新餘額","size":"sm","color":"#888888","flex":2},
                      {"type":"text","text":"$%s","size":"sm","wrap":true,"flex":5}
                    ]
                  },
                  {
                    "type": "box",
                    "layout": "baseline",
                    "contents": [
                      {"type":"text","text":"備註","size":"sm","color":"#888888","flex":2},
                      {"type":"text","text":"%s","size":"sm","wrap":true,"flex":5}
                    ]
                  }
                ]
              }
            ]
          },
          "footer": {
            "type": "box",
            "layout": "horizontal",
            "spacing": "md",
            "contents": [
              { "type":"button", "action":{"type":"message","label":"餘額","text":"餘額"}, "height":"sm" },
              { "type":"button", "action":{"type":"message","label":"明細","text":"明細"}, "height":"sm" },
              { "type":"button", "action":{"type":"message","label":"選單","text":"選單"}, "height":"sm" }
            ]
          }
        }
        """.formatted(type, amt, bal, memo);
    }

    /** 驗證 X-Line-Signature */
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

    private boolean isMenuKeyword(String text) {
        return "選單".equals(text) || "功能".equals(text)
                || "menu".equalsIgnoreCase(text) || "開始".equals(text);
    }
}
