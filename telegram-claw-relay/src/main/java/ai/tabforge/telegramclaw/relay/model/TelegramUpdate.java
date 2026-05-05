package ai.tabforge.telegramclaw.relay.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a single Update object sent by the Telegram Bot API to our webhook.
 *
 * <p>Analogy: like a tray on a conveyor belt in a sorting facility — each tray (Update)
 * has a unique sequence number (update_id) and carries exactly one item: either a message,
 * an edited message, a callback query, etc. Only one slot is filled per tray. For our
 * use case we only care about the "message" slot carrying a text message.</p>
 *
 * <p>Telegram sends one Update per webhook call to our /webhook endpoint.
 * The update_id is strictly increasing and can be used for deduplication.</p>
 *
 * <p>Full Telegram Bot API reference: https://core.telegram.org/bots/api#update</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TelegramUpdate {

    /** Strictly increasing integer; unique per bot. Used for deduplication if needed. */
    @JsonProperty("update_id")
    private long updateId;

    /**
     * Present when the update is a new incoming message.
     * Null for other update types (edited_message, callback_query, etc.).
     */
    private Message message;

    /** Present when the update is an edit of an existing message. We log but ignore these. */
    @JsonProperty("edited_message")
    private Message editedMessage;

    public long getUpdateId() { return updateId; }
    public Message getMessage() { return message; }
    public Message getEditedMessage() { return editedMessage; }

    public void setUpdateId(long updateId) { this.updateId = updateId; }
    public void setMessage(Message message) { this.message = message; }
    public void setEditedMessage(Message editedMessage) { this.editedMessage = editedMessage; }

    // ── Inner classes ─────────────────────────────────────────────────────────

    /**
     * A Telegram message — the main payload for text commands sent by Person A.
     *
     * <p>Analogy: like an envelope with a postmark — it always has a sender (from),
     * a delivery address (chat), a timestamp (date), and the letter inside (text).
     * The chat.id is what we use to send the reply back; the from.id is what we
     * check against the authorization whitelist.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {

        @JsonProperty("message_id")
        private long messageId;

        /** Who sent the message — their Telegram user ID is the authorization key. */
        private User from;

        /** The chat where the message was sent — use chat.id for sendMessage replies. */
        private Chat chat;

        /** Unix timestamp in seconds when the message was sent. */
        private long date;

        /** The text content — present only for text messages; null for photos, stickers, etc. */
        private String text;

        public long getMessageId() { return messageId; }
        public User getFrom() { return from; }
        public Chat getChat() { return chat; }
        public long getDate() { return date; }
        public String getText() { return text; }

        public void setMessageId(long messageId) { this.messageId = messageId; }
        public void setFrom(User from) { this.from = from; }
        public void setChat(Chat chat) { this.chat = chat; }
        public void setDate(long date) { this.date = date; }
        public void setText(String text) { this.text = text; }
    }

    /**
     * The Telegram user who sent the message.
     *
     * <p>Analogy: like a library card — the id is the permanent member number
     * (never changes, even if the person changes their username or display name),
     * while firstName/username are the human-readable labels that may change.
     * We store id in the authorization whitelist, never the username.</p>
     *
     * <p>Note: id is a long integer in Telegram, not a string.
     * Telegram user IDs can exceed Integer.MAX_VALUE for newer accounts.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class User {

        /** Stable Telegram user ID — primary key for authorization checks. Never changes. */
        private long id;

        @JsonProperty("is_bot")
        private boolean isBot;

        @JsonProperty("first_name")
        private String firstName;

        @JsonProperty("last_name")
        private String lastName;

        /** Optional — user may not have a username set. Do not use for authorization. */
        private String username;

        @JsonProperty("language_code")
        private String languageCode;

        public long getId() { return id; }
        public boolean isBot() { return isBot; }
        public String getFirstName() { return firstName; }
        public String getLastName() { return lastName; }
        public String getUsername() { return username; }
        public String getLanguageCode() { return languageCode; }

        public void setId(long id) { this.id = id; }
        public void setBot(boolean bot) { isBot = bot; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
        public void setUsername(String username) { this.username = username; }
        public void setLanguageCode(String languageCode) { this.languageCode = languageCode; }

        /** Returns a readable label for logging: "Marko (@marko_rs)" or just "Marko" if no username. */
        public String displayName() {
            String name = firstName != null ? firstName : "unknown";
            if (lastName != null) name += " " + lastName;
            if (username != null) name += " (@" + username + ")";
            return name;
        }
    }

    /**
     * The chat in which the message was received.
     *
     * <p>Analogy: like a mailing address — for private (1-on-1) chats, the chat.id
     * equals the user's id. This is the value we pass to Telegram's sendMessage API
     * as chat_id to deliver the reply back to Person A.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Chat {

        /** For private chats, equals the user's id. Pass this to sendMessage as chat_id. */
        private long id;

        /** "private", "group", "supergroup", or "channel". We only handle "private". */
        private String type;

        public long getId() { return id; }
        public String getType() { return type; }

        public void setId(long id) { this.id = id; }
        public void setType(String type) { this.type = type; }
    }
}
