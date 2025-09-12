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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/line")
public class LineWebhookController {

    private static final String ZWSP = "\u200B"; // 零寬空白：讓訊息看起來沒有文字

    private final BankService bankService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    // ✅【新增】改名引導狀態：按「改名」後，下一句文字視為新名字
    private final Map<String, Boolean> renamePending = new ConcurrentHashMap<>();

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

                        // ✅【新增】改名引導：若在等待新名字，這次輸入直接當作新名字
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

                        if (isMenuKeyword(text)) { replyMenuQuick(replyToken); continue; }
                        if ("存款".equals(text)) { replyAmountQuick(replyToken, "deposit"); continue; }
                        if ("提款".equals(text)) { replyAmountQuick(replyToken, "withdraw"); continue; }

                        // ✅【新增】「改名」→ 先提示輸入新名字（不用再打「改名 XXX」）
                        if ("改名".equals(text)) {
                            renamePending.put(userId, true);
                            replyTextWithMenu(replyToken, "請輸入新名字（或輸入「取消」退出）：");
                            continue;
                        }

                        // ✅【新增】餘額/明細 改為 Flex 回覆（更好看）
                        if ("餘額".equals(text)) {
                            try {
                                BigDecimal bal = bankService.getBalance(userId);
                                replyFlex(replyToken, "目前餘額", buildBalanceFlex(bal));
                            } catch (Exception ex) {
                                replyError(replyToken, "查詢餘額失敗：" + ex.getMessage());
                            }
                            continue;
                        }
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

                        // 直接處理「存 1000 / 提 500」→ 回 Flex
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

                        // 其他指令仍用原文字回覆＋快捷鍵（保留你的既有行為）
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
            // 保留你原本的「改名 XXX」寫法（與引導式並存）
            if (text.startsWith("改名")) {
                String newName = text.replaceFirst("^改名\\s*", "").trim();
                if (newName.isBlank()) return "請輸入新名字，例如：改名 Alan";
                var acc = bankService.rename(userId, newName);
                return "改名成功：" + acc.getName();
            }
            // 注意：真正的「餘額 / 明細」現在在 webhook 內已用 Flex 處理，這裡保留原文字版以相容
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
            // ✅ 錯誤訊息更親切
            replyError(replyToken, "操作失敗：" + e.getMessage());
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
                      {"type":"action","action":{"type":"message","label":"改名","text":"改名"}}
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

    /** 主選單（清楚顯示標題） */
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
                    {"type":"action","action":{"type":"message","label":"改名","text":"改名"}}
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

    /** 建立交易摘要 Flex JSON（Bubble，美化版） */
    private String buildTransactionFlex(String type, BigDecimal amount, BigDecimal newBalance, String note) {
        String amt = amount.stripTrailingZeros().toPlainString();
        String bal = newBalance.stripTrailingZeros().toPlainString();
        String memo = (note == null || note.isBlank()) ? "-" : note;

        // 根據交易類型切換顏色
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
            {
                "type": "text",
                "text": "$%s",
                "weight": "bold",
                "size": "xxl",
                "color": "#333333",
                "align": "center"
            },
            {
                "type": "box",
                "layout": "baseline",
                "spacing": "sm",
                "contents": [
                {"type":"text","text":"💰 新餘額","size":"sm","color":"#888888","flex":2},
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
        },
        "footer": {
            "type": "box",
            "layout": "horizontal",
            "spacing": "md",
            "contents": [
            { "type":"button", "style":"link", "action":{"type":"message","label":"餘額","text":"餘額"}, "height":"sm" },
            { "type":"button", "style":"link", "action":{"type":"message","label":"明細","text":"明細"}, "height":"sm" },
            { "type":"button", "style":"link", "action":{"type":"message","label":"選單","text":"選單"}, "height":"sm" }
            ]
        }
        }
        """.formatted(headerColor, type, amt, bal, memo);
    }

    /** ✅【新增】餘額 Flex */
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

    /** ✅【新增】交易明細 Flex（最多 5 筆，避免 JSON 尾逗號） */
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

    /** ✅【新增】更親切的錯誤訊息統一回覆 */
    private void replyError(String replyToken, String message) {
        replyTextWithMenu(replyToken, "❌ " + message + "\n💡 您可以輸入「選單」查看功能");
    }
}
    