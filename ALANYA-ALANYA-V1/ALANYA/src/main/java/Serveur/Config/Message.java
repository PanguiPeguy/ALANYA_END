package Serveur.Config;

import java.io.Serializable;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum MessageType {
        TEXT,
        FILE,
        VOICE,
        DISCONNECTION,
        HEARTBEAT,
        ACK,
        EMOJI
    }

    private String sender;
    private String receiver;
    private String content;
    private MessageType type;
    private byte[] fileData;
    private String fileName;
    private int emojiId;

    // Constructeur par défaut requis pour la désérialisation
    public Message() {
    }

    public Message(String sender, String receiver, String content) {
        this.sender = sender;
        this.receiver = receiver;
        this.content = content;
        this.type = MessageType.TEXT;
    }

    public Message(String sender, String receiver, int emojiId) {
        this.sender = sender;
        this.receiver = receiver;
        this.emojiId = emojiId;
        this.type = MessageType.EMOJI;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public byte[] getFileData() {
        return fileData;
    }

    public void setFileData(byte[] fileData) {
        this.fileData = fileData;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public int getEmojiId() {
        return emojiId;
    }

    public void setEmojiId(int emojiId) {
        this.emojiId = emojiId;
    }
}