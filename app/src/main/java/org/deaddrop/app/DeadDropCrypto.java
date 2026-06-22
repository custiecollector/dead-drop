package org.deaddrop.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class DeadDropCrypto {
    private static final SecureRandom RNG = new SecureRandom();
    private static final byte[] PACKET_MAGIC = new byte[]{'D', 'D', '0', '1'};
    private static final byte[] SIGNED_PLAIN_MAGIC = new byte[]{'D', 'D', 'S', '1'};
    private static final byte[] INVITE_MAGIC = new byte[]{'D', 'D', 'I', '1'};
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_LEN = 12;
    private static final int GROUP_KEY_LEN = 32;
    private static final int GROUP_ID_LEN = 8;
    private static final int MSG_ID_LEN = 16;
    private static final int PBKDF2_ITERATIONS = 180_000;
    private static final int MODE_ANON = 1;
    private static final int MODE_SIGNED_HANDLE = 2;

    private DeadDropCrypto() {}

    public static final class Group {
        public final String name;
        public final byte[] key;
        public final byte[] id;

        public Group(String name, byte[] key) throws GeneralSecurityException {
            this.name = sanitizeName(name);
            this.key = key.clone();
            this.id = deriveGroupId(key);
        }

        public String idHex() { return hex(id); }
    }

    public static final class ReceivedMessage {
        public final Group group;
        public final byte[] messageId;
        public final long createdAtMillis;
        public final long expiresAtMillis;
        public final String text;
        public final String mode;
        public final String senderLabel;
        public final String senderFingerprint;
        public final boolean verified;

        ReceivedMessage(Group group, byte[] messageId, long createdAtMillis, long expiresAtMillis,
                        String text, String mode, String senderLabel, String senderFingerprint, boolean verified) {
            this.group = group;
            this.messageId = messageId;
            this.createdAtMillis = createdAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            this.text = text;
            this.mode = mode;
            this.senderLabel = senderLabel;
            this.senderFingerprint = senderFingerprint;
            this.verified = verified;
        }

        public String dedupeKey() {
            return (group == null ? "signed-plain" : group.idHex()) + ":" + hex(messageId);
        }

        public String groupLabel() {
            return group == null ? "Signed plaintext" : group.name;
        }

        public String displaySender() {
            String safety = (senderFingerprint == null || senderFingerprint.isEmpty()) ? "" : " #" + senderFingerprint;
            if (senderLabel == null || senderLabel.trim().isEmpty()) return verified ? "Verified sender" + safety : "Anonymous";
            return senderLabel + (verified ? " ✓" : "") + safety;
        }
    }

    public static Group createGroup(String name) throws GeneralSecurityException {
        byte[] key = new byte[GROUP_KEY_LEN];
        RNG.nextBytes(key);
        return new Group(name, key);
    }

    public static byte[] createEncryptedPacket(Group group, String text, int ttlHours, int maxChars) throws Exception {
        byte[] plaintext = buildAnonymousPlaintext(text, maxChars);
        return createGroupCipherPacket(group, plaintext, ttlHours);
    }

    public static byte[] createSignedEncryptedPacket(Group group, String text, int ttlHours, int maxChars,
                                                     String handle, PublicKey publicKey, PrivateKey privateKey) throws Exception {
        if (group == null) throw new IllegalArgumentException("Create or join a group first.");
        String clean = cleanText(text, maxChars);
        String cleanHandle = cleanHandle(handle);
        byte[] textBytes = clean.getBytes(StandardCharsets.UTF_8);
        byte[] handleBytes = cleanHandle.getBytes(StandardCharsets.UTF_8);
        byte[] pubBytes = publicKey.getEncoded();
        long now = System.currentTimeMillis();
        long expires = now + Math.max(1, ttlHours) * 60L * 60L * 1000L;
        byte[] msgId = randomBytes(MSG_ID_LEN);
        byte[] toSign = signingBytes("DD-SIGNED-ENC-V1", group.id, msgId, now, expires, handleBytes, pubBytes, textBytes);
        byte[] sig = sign(privateKey, toSign);

        ByteArrayOutputStream plainOut = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(plainOut);
        p.writeByte(MODE_SIGNED_HANDLE);
        writeShortBytes(p, handleBytes);
        writeShortBytes(p, pubBytes);
        writeShortBytes(p, textBytes);
        writeShortBytes(p, sig);
        p.flush();
        return createGroupCipherPacket(group, plainOut.toByteArray(), now, expires, msgId);
    }

    public static byte[] createSignedPlaintextPacket(String text, int ttlHours, int maxChars,
                                                     String handle, PublicKey publicKey, PrivateKey privateKey) throws Exception {
        String clean = cleanText(text, maxChars);
        String cleanHandle = cleanHandle(handle);
        byte[] textBytes = clean.getBytes(StandardCharsets.UTF_8);
        byte[] handleBytes = cleanHandle.getBytes(StandardCharsets.UTF_8);
        byte[] pubBytes = publicKey.getEncoded();
        long now = System.currentTimeMillis();
        long expires = now + Math.max(1, ttlHours) * 60L * 60L * 1000L;
        byte[] msgId = randomBytes(MSG_ID_LEN);
        byte[] toSign = signingBytes("DD-SIGNED-PLAIN-V1", new byte[0], msgId, now, expires, handleBytes, pubBytes, textBytes);
        byte[] sig = sign(privateKey, toSign);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.write(SIGNED_PLAIN_MAGIC);
        d.write(msgId);
        d.writeLong(now);
        d.writeLong(expires);
        writeShortBytes(d, handleBytes);
        writeShortBytes(d, pubBytes);
        writeShortBytes(d, textBytes);
        writeShortBytes(d, sig);
        d.flush();
        return out.toByteArray();
    }

    public static ReceivedMessage openAnyPacket(List<Group> groups, byte[] packet) throws Exception {
        if (packet == null || packet.length < 4) throw new IllegalArgumentException("Packet too short.");
        byte[] magic = Arrays.copyOfRange(packet, 0, 4);
        if (Arrays.equals(magic, PACKET_MAGIC)) return openEncryptedPacket(groups, packet);
        if (Arrays.equals(magic, SIGNED_PLAIN_MAGIC)) return openSignedPlaintextPacket(packet);
        throw new IllegalArgumentException("Not a DeadDrop packet.");
    }

    public static ReceivedMessage openEncryptedPacket(List<Group> groups, byte[] packet) throws Exception {
        if (packet == null || packet.length < 4 + GROUP_ID_LEN + MSG_ID_LEN + 8 + 8 + NONCE_LEN + 2 + 16) {
            throw new IllegalArgumentException("Packet too short.");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, PACKET_MAGIC)) throw new IllegalArgumentException("Not a DeadDrop encrypted packet.");
        byte[] groupId = new byte[GROUP_ID_LEN];
        byte[] msgId = new byte[MSG_ID_LEN];
        byte[] nonce = new byte[NONCE_LEN];
        in.readFully(groupId);
        in.readFully(msgId);
        long created = in.readLong();
        long expires = in.readLong();
        in.readFully(nonce);
        int cipherLen = in.readUnsignedShort();
        if (cipherLen < 16 || cipherLen > 8192 || cipherLen > in.available()) throw new IllegalArgumentException("Invalid ciphertext length.");
        byte[] ciphertext = new byte[cipherLen];
        in.readFully(ciphertext);

        Group group = null;
        for (Group g : groups) {
            if (Arrays.equals(g.id, groupId)) { group = g; break; }
        }
        if (group == null) throw new IllegalArgumentException("Packet is for an unknown group.");
        if (System.currentTimeMillis() > expires) throw new IllegalArgumentException("Packet expired.");

        byte[] header = Arrays.copyOfRange(packet, 0, 4 + GROUP_ID_LEN + MSG_ID_LEN + 8 + 8 + NONCE_LEN);
        byte[] plaintext = aesGcmDecrypt(group.key, nonce, header, ciphertext);
        return parseGroupPlaintext(group, msgId, created, expires, plaintext);
    }

    private static ReceivedMessage parseGroupPlaintext(Group group, byte[] msgId, long created, long expires, byte[] plaintext) throws Exception {
        DataInputStream p = new DataInputStream(new ByteArrayInputStream(plaintext));
        int first = p.readUnsignedByte();
        if (first == MODE_ANON) {
            String text = readUtf8Short(p, 1000);
            return new ReceivedMessage(group, msgId, created, expires, text, "encrypted-anonymous", "Anonymous", "", true);
        }
        if (first == MODE_SIGNED_HANDLE) {
            byte[] handleBytes = readShortBytes(p, 80);
            byte[] pubBytes = readShortBytes(p, 512);
            byte[] textBytes = readShortBytes(p, 4096);
            byte[] sig = readShortBytes(p, 512);
            byte[] toSign = signingBytes("DD-SIGNED-ENC-V1", group.id, msgId, created, expires, handleBytes, pubBytes, textBytes);
            PublicKey pub = decodePublicKey(pubBytes);
            if (!verify(pub, toSign, sig)) throw new IllegalArgumentException("Bad signed-handle signature.");
            return new ReceivedMessage(group, msgId, created, expires,
                    new String(textBytes, StandardCharsets.UTF_8),
                    "encrypted-signed-handle",
                    new String(handleBytes, StandardCharsets.UTF_8),
                    fingerprint(pubBytes), true);
        }

        // Legacy anonymous plaintext body: [u16 textLen][text].
        int textLen = (first << 8) | p.readUnsignedByte();
        if (textLen > p.available()) throw new IllegalArgumentException("Invalid legacy plaintext length.");
        byte[] textBytes = new byte[textLen];
        p.readFully(textBytes);
        return new ReceivedMessage(group, msgId, created, expires, new String(textBytes, StandardCharsets.UTF_8),
                "encrypted-anonymous", "Anonymous", "", true);
    }

    private static ReceivedMessage openSignedPlaintextPacket(byte[] packet) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, SIGNED_PLAIN_MAGIC)) throw new IllegalArgumentException("Not a DeadDrop signed plaintext packet.");
        byte[] msgId = new byte[MSG_ID_LEN];
        in.readFully(msgId);
        long created = in.readLong();
        long expires = in.readLong();
        if (System.currentTimeMillis() > expires) throw new IllegalArgumentException("Packet expired.");
        byte[] handleBytes = readShortBytes(in, 80);
        byte[] pubBytes = readShortBytes(in, 512);
        byte[] textBytes = readShortBytes(in, 4096);
        byte[] sig = readShortBytes(in, 512);
        byte[] toSign = signingBytes("DD-SIGNED-PLAIN-V1", new byte[0], msgId, created, expires, handleBytes, pubBytes, textBytes);
        PublicKey pub = decodePublicKey(pubBytes);
        if (!verify(pub, toSign, sig)) throw new IllegalArgumentException("Bad signed-plaintext signature.");
        return new ReceivedMessage(null, msgId, created, expires, new String(textBytes, StandardCharsets.UTF_8),
                "signed-plaintext", new String(handleBytes, StandardCharsets.UTF_8), fingerprint(pubBytes), true);
    }

    public static String exportInvite(Group group, String secondFactor) throws Exception {
        if (group == null) throw new IllegalArgumentException("No group selected.");
        if (secondFactor == null || secondFactor.length() < 6) throw new IllegalArgumentException("Second factor must be at least 6 characters.");
        byte[] salt = new byte[16];
        byte[] nonce = new byte[NONCE_LEN];
        RNG.nextBytes(salt);
        RNG.nextBytes(nonce);
        byte[] wrappingKey = deriveInviteKey(secondFactor, salt);

        ByteArrayOutputStream plainOut = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(plainOut);
        p.write(INVITE_MAGIC);
        byte[] nameBytes = group.name.getBytes(StandardCharsets.UTF_8);
        p.writeShort(nameBytes.length);
        p.write(nameBytes);
        p.write(group.key);
        p.flush();
        byte[] ciphertext = aesGcmEncrypt(wrappingKey, nonce, INVITE_MAGIC, plainOut.toByteArray());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(salt);
        out.write(nonce);
        DataOutputStream d = new DataOutputStream(out);
        d.writeShort(ciphertext.length);
        d.write(ciphertext);
        d.flush();
        return "DDINV1." + b64(out.toByteArray());
    }

    public static Group importInvite(String invite, String secondFactor) throws Exception {
        if (invite == null || !invite.trim().startsWith("DDINV1.")) throw new IllegalArgumentException("Invite must start with DDINV1.");
        if (secondFactor == null || secondFactor.length() < 6) throw new IllegalArgumentException("Second factor must be at least 6 characters.");
        byte[] raw = b64d(invite.trim().substring("DDINV1.".length()));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        byte[] salt = new byte[16];
        byte[] nonce = new byte[NONCE_LEN];
        in.readFully(salt);
        in.readFully(nonce);
        int cipherLen = in.readUnsignedShort();
        if (cipherLen < 16 || cipherLen > in.available()) throw new IllegalArgumentException("Invalid invite length.");
        byte[] ciphertext = new byte[cipherLen];
        in.readFully(ciphertext);
        byte[] wrappingKey = deriveInviteKey(secondFactor, salt);
        byte[] plaintext = aesGcmDecrypt(wrappingKey, nonce, INVITE_MAGIC, ciphertext);
        DataInputStream p = new DataInputStream(new ByteArrayInputStream(plaintext));
        byte[] magic = new byte[4];
        p.readFully(magic);
        if (!Arrays.equals(magic, INVITE_MAGIC)) throw new IllegalArgumentException("Wrong second factor or bad invite.");
        int nameLen = p.readUnsignedShort();
        if (nameLen < 1 || nameLen > 80 || nameLen > p.available()) throw new IllegalArgumentException("Invalid group name.");
        byte[] nameBytes = new byte[nameLen];
        p.readFully(nameBytes);
        byte[] key = new byte[GROUP_KEY_LEN];
        p.readFully(key);
        return new Group(new String(nameBytes, StandardCharsets.UTF_8), key);
    }

    public static String serializeGroups(List<Group> groups) {
        StringBuilder sb = new StringBuilder();
        for (Group g : groups) {
            sb.append(b64(g.name.getBytes(StandardCharsets.UTF_8))).append('|').append(b64(g.key)).append('\n');
        }
        return sb.toString();
    }

    public static List<Group> deserializeGroups(String stored) throws GeneralSecurityException {
        List<Group> groups = new ArrayList<>();
        if (stored == null || stored.trim().isEmpty()) return groups;
        String[] lines = stored.split("\\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|", 2);
            if (parts.length != 2) continue;
            String name = new String(b64d(parts[0]), StandardCharsets.UTF_8);
            byte[] key = b64d(parts[1]);
            if (key.length == GROUP_KEY_LEN) groups.add(new Group(name, key));
        }
        return groups;
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    public static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] b64d(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    public static String fingerprint(byte[] publicKeyBytes) throws GeneralSecurityException {
        return hex(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(publicKeyBytes), 8));
    }

    private static byte[] createGroupCipherPacket(Group group, byte[] plaintext, int ttlHours) throws Exception {
        long now = System.currentTimeMillis();
        long expires = now + Math.max(1, ttlHours) * 60L * 60L * 1000L;
        byte[] msgId = randomBytes(MSG_ID_LEN);
        return createGroupCipherPacket(group, plaintext, now, expires, msgId);
    }

    private static byte[] createGroupCipherPacket(Group group, byte[] plaintext, long now, long expires, byte[] msgId) throws Exception {
        if (group == null) throw new IllegalArgumentException("Create or join a group first.");
        byte[] nonce = randomBytes(NONCE_LEN);
        ByteArrayOutputStream headerOut = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(headerOut);
        h.write(PACKET_MAGIC);
        h.write(group.id);
        h.write(msgId);
        h.writeLong(now);
        h.writeLong(expires);
        h.write(nonce);
        h.flush();
        byte[] header = headerOut.toByteArray();
        byte[] ciphertext = aesGcmEncrypt(group.key, nonce, header, plaintext);
        ByteArrayOutputStream packetOut = new ByteArrayOutputStream();
        packetOut.write(header);
        DataOutputStream d = new DataOutputStream(packetOut);
        d.writeShort(ciphertext.length);
        d.write(ciphertext);
        d.flush();
        return packetOut.toByteArray();
    }

    private static byte[] buildAnonymousPlaintext(String text, int maxChars) throws Exception {
        String clean = cleanText(text, maxChars);
        ByteArrayOutputStream plainOut = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(plainOut);
        p.writeByte(MODE_ANON);
        writeShortBytes(p, clean.getBytes(StandardCharsets.UTF_8));
        p.flush();
        return plainOut.toByteArray();
    }

    private static String cleanText(String text, int maxChars) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Message is empty.");
        String clean = text.trim();
        if (clean.length() > maxChars) throw new IllegalArgumentException("Message exceeds configured max length (" + maxChars + ").");
        return clean;
    }

    private static String cleanHandle(String handle) {
        String h = handle == null ? "DeadDrop" : handle.trim();
        if (h.isEmpty()) h = "DeadDrop";
        return h.length() > 32 ? h.substring(0, 32) : h;
    }

    private static String sanitizeName(String name) {
        String n = name == null ? "Field Group" : name.trim();
        if (n.isEmpty()) n = "Field Group";
        return n.length() > 80 ? n.substring(0, 80) : n;
    }

    private static byte[] deriveGroupId(byte[] key) throws GeneralSecurityException {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update("DeadDrop group id v1".getBytes(StandardCharsets.UTF_8));
        sha.update(key);
        return Arrays.copyOf(sha.digest(), GROUP_ID_LEN);
    }

    private static byte[] deriveInviteKey(String secondFactor, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(secondFactor.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    private static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    private static byte[] sign(PrivateKey privateKey, byte[] data) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        sig.update(data);
        return sig.sign();
    }

    private static boolean verify(PublicKey publicKey, byte[] data, byte[] signature) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initVerify(publicKey);
        sig.update(data);
        return sig.verify(signature);
    }

    private static PublicKey decodePublicKey(byte[] encoded) throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static byte[] signingBytes(String domain, byte[] groupId, byte[] msgId, long created, long expires,
                                       byte[] handleBytes, byte[] publicKeyBytes, byte[] textBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeUTF(domain);
        writeShortBytes(d, groupId);
        writeShortBytes(d, msgId);
        d.writeLong(created);
        d.writeLong(expires);
        writeShortBytes(d, handleBytes);
        writeShortBytes(d, publicKeyBytes);
        writeShortBytes(d, textBytes);
        d.flush();
        return out.toByteArray();
    }

    private static void writeShortBytes(DataOutputStream out, byte[] bytes) throws Exception {
        if (bytes.length > 65535) throw new IllegalArgumentException("Field too large.");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static byte[] readShortBytes(DataInputStream in, int max) throws Exception {
        int len = in.readUnsignedShort();
        if (len < 0 || len > max || len > in.available()) throw new IllegalArgumentException("Invalid field length.");
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return bytes;
    }

    private static String readUtf8Short(DataInputStream in, int max) throws Exception {
        return new String(readShortBytes(in, max * 4), StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes(int len) {
        byte[] bytes = new byte[len];
        RNG.nextBytes(bytes);
        return bytes;
    }
}
